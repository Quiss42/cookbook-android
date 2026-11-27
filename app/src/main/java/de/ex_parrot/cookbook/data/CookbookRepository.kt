package de.ex_parrot.cookbook.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Outcome of saving a recipe. */
data class SaveResult(val id: String, val queued: Boolean)

/** How the user resolves a conflict between an offline change and the server. */
enum class ConflictChoice { MINE, SERVER, BOTH }

/**
 * In-memory cache in front of [CookbookApi], plus offline editing: changes
 * that cannot reach the server are queued in [OfflineStore.pending], shown as
 * if saved, and uploaded later.
 */
class CookbookRepository(
    val api: CookbookApi,
    private val store: OfflineStore? = null,
    private val files: NextcloudFiles? = null,
) {
    private var categories: List<Category>? = null
    private var allRecipes: List<RecipeStub>? = null
    private val categoryRecipes = ConcurrentHashMap<String, List<RecipeStub>>()
    private val recipes = ConcurrentHashMap<String, Recipe>()

    /** Offline ids of recipes that have been uploaded meanwhile -> their server ids (persisted). */
    private val uploadedIds = ConcurrentHashMap<String, String>(store?.readIdMap().orEmpty())
    private val uploadMutex = Mutex()

    private val pending get() = store?.pending
    private val pendingChanges get() = pending?.changes?.value.orEmpty()

    /** Called when a recipe's stored pictures are outdated (set by the session). */
    var onImagesOutdated: (recipeId: String) -> Unit = {}

    /** Called after a change was queued, so it gets uploaded when possible. */
    var onChangeQueued: () -> Unit = {}

    /** Downloads a photo from the web on the phone and prepares it (set by the session). */
    var downloadImage: suspend (url: String) -> File = { throw NoImageException() }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Messages about background uploads, e.g. a photo that could not be downloaded. */
    val notices: SharedFlow<String> = _notices

    private val _changes = MutableStateFlow(0)
    /** Incremented whenever recipes are created, changed or deleted. */
    val changes: StateFlow<Int> = _changes

    private fun invalidate(recipeId: String? = null) {
        categories = null
        allRecipes = null
        categoryRecipes.clear()
        if (recipeId != null) {
            recipes.remove(recipeId)
            onImagesOutdated(recipeId)
        }
        _changes.value++
    }

    private fun resolveId(id: String) = uploadedIds[id] ?: id

    private fun rememberUploaded(localId: String, serverId: String) {
        uploadedIds[localId] = serverId
        runCatching { store?.writeIdMap(uploadedIds.toMap()) }
    }

    // ---------------------------------------------------------------- saving

    /**
     * Saves a new ([id] null) or existing recipe. [localImage] is a prepared
     * photo to attach. Without a connection the change is queued.
     */
    suspend fun saveRecipe(
        id: String?,
        recipe: JsonObject,
        localImage: File?,
        baseModified: String?,
        fromCategory: String?,
        /** Web address of a photo still to be downloaded by the phone. */
        webImage: String? = null,
    ): SaveResult = withContext(Dispatchers.IO) {
        val queue = pending
        val target = id?.let(::resolveId)
        // Keep the order: if this recipe already has queued changes, queue this one too.
        if (queue != null && target != null && (target.startsWith(LOCAL_RECIPE_PREFIX) || queue.forRecipe(target) != null)) {
            return@withContext enqueueSave(target, recipe, localImage, baseModified, fromCategory, webImage)
        }
        // Before the first request: a timed-out create can only have produced recipes after this.
        val started = System.currentTimeMillis()
        try {
            // A web photo is fetched now; if that fails for lack of connection, everything is queued.
            val photo = localImage ?: webImage?.let { downloadImage(it) }
            val savedId = saveOnline(target, recipe, photo?.absolutePath)
            invalidate(savedId)
            SaveResult(savedId, queued = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (queue == null || !isUnreachable(e)) throw e
            enqueueSave(
                target, recipe, localImage, baseModified, fromCategory, webImage,
                uncertain = mayHaveReachedServer(e), createdAt = started,
            )
        }
    }

    /** Uploads the photo (if any) to a temporary file, saves the recipe and removes the temporary file. */
    private suspend fun saveOnline(id: String?, recipe: JsonObject, imageFile: String?): String {
        var tempPath: String? = null
        try {
            var json = recipe
            if (imageFile != null && files != null) {
                // Cookbook copies files given by path into the recipe folder.
                val path = "/.cookbook-upload-${System.currentTimeMillis()}.jpg"
                files.upload(path, File(imageFile).readBytes(), "image/jpeg")
                tempPath = path
                json = JsonObject(json + ("image" to JsonPrimitive(path)))
            }
            return if (id == null) api.createRecipe(JsonObject(json - "id")) else api.updateRecipe(id, json)
        } finally {
            tempPath?.let { path -> runCatching { files?.delete(path) } }
        }
    }

    private fun enqueueSave(
        id: String?,
        recipe: JsonObject,
        localImage: File?,
        baseModified: String?,
        fromCategory: String?,
        webImage: String? = null,
        uncertain: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
    ): SaveResult {
        val queue = pending!!
        val image = localImage?.let(queue::keepImage)
        val newPhoto = image != null || webImage != null
        val name = recipe.string("name")
        val existing = id?.let(queue::forRecipe)
        val now = createdAt
        val resultId: String
        when {
            id == null -> {
                resultId = "$LOCAL_RECIPE_PREFIX$now"
                queue.update {
                    it + PendingChange(
                        queue.nextId(), PendingChange.Type.CREATE, resultId, name, recipe,
                        imageFile = image, imageWebUrl = webImage, createdAt = now, uncertain = uncertain,
                    )
                }
            }
            existing != null && existing.type != PendingChange.Type.DELETE -> {
                resultId = id
                // Merge into the queued change; keep its original base for the conflict check.
                existing.imageFile?.takeIf { newPhoto }?.let { File(it).delete() }
                queue.update { list ->
                    list.map { c ->
                        if (c.id == existing.id) {
                            c.copy(
                                name = name,
                                recipe = recipe,
                                // A newly chosen photo replaces the queued one.
                                imageFile = if (newPhoto) image else c.imageFile,
                                imageWebUrl = if (newPhoto) webImage else c.imageWebUrl,
                                error = null,
                            )
                        } else c
                    }
                }
            }
            else -> {
                resultId = id
                queue.update {
                    it + PendingChange(
                        queue.nextId(), PendingChange.Type.UPDATE, id, name, recipe,
                        imageFile = image, imageWebUrl = webImage,
                        baseModified = baseModified, fromCategory = fromCategory,
                        createdAt = now, uncertain = uncertain,
                    )
                }
            }
        }
        invalidate(resultId)
        onChangeQueued()
        return SaveResult(resultId, queued = true)
    }

    /** Deletes a recipe; without a connection the deletion is queued. Returns true if queued. */
    suspend fun deleteRecipe(id: String, name: String, category: String, baseModified: String?): Boolean =
        withContext(Dispatchers.IO) {
            val queue = pending
            val target = resolveId(id)
            val existing = queue?.forRecipe(target)
            if (queue != null && existing != null) {
                when (existing.type) {
                    // Never uploaded: just forget it.
                    PendingChange.Type.CREATE -> queue.remove(existing)
                    else -> {
                        existing.imageFile?.let { File(it).delete() }
                        queue.update { list ->
                            list.map { c ->
                                if (c.id == existing.id) {
                                    c.copy(
                                        type = PendingChange.Type.DELETE, recipe = null, imageFile = null,
                                        imageWebUrl = null, conflict = false, error = null,
                                    )
                                } else c
                            }
                        }
                    }
                }
                invalidate(target)
                onChangeQueued()
                return@withContext existing.type != PendingChange.Type.CREATE
            }
            try {
                api.deleteRecipe(target)
                invalidate(target)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (queue == null || !isUnreachable(e)) throw e
                queue.update {
                    it + PendingChange(
                        queue.nextId(), PendingChange.Type.DELETE, target, name,
                        baseModified = baseModified, fromCategory = category, createdAt = System.currentTimeMillis(),
                    )
                }
                invalidate(target)
                onChangeQueued()
                true
            }
        }

    suspend fun importRecipe(url: String): Recipe = api.importRecipe(url).also { invalidate(it.id) }

    // ---------------------------------------------------------------- upload

    /** True while queued changes (other than conflicts) are waiting. */
    fun hasUploadsWaiting(): Boolean = pendingChanges.any { !it.conflict }

    /**
     * Uploads queued changes in order. Stops at the first "not reachable" error.
     * Recipes changed on the server meanwhile are marked as conflicts.
     */
    suspend fun uploadPending() = withContext(Dispatchers.IO) {
        val queue = pending ?: return@withContext
        uploadMutex.withLock {
            for (snapshot in queue.changes.value) {
                val change = queue.current(snapshot) ?: continue
                if (change.conflict) continue
                try {
                    upload(change)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isUnreachable(e)) {
                        // The request may have arrived although the answer did not: check next time.
                        if (mayHaveReachedServer(e)) markUncertain(change)
                        return@withLock
                    }
                    markError(change, e)
                }
            }
        }
    }

    /**
     * The photo to upload with [change]: the stored file, or the web photo
     * downloaded now. A web photo that cannot be found is skipped (with a
     * notice) so the rest of the change still gets uploaded.
     */
    private suspend fun photoFor(change: PendingChange): String? {
        change.imageFile?.let { return it }
        val url = change.imageWebUrl ?: return null
        return try {
            downloadImage(url).absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isUnreachable(e)) throw e
            _notices.tryEmit("${change.name}: $url")
            null
        }
    }

    private suspend fun upload(change: PendingChange) {
        when (change.type) {
            PendingChange.Type.CREATE -> {
                // After a timed-out attempt the recipe may already exist on the server.
                val newId = (if (change.uncertain) findCreatedEarlier(change) else null)
                    ?: saveOnline(null, change.recipe!!, photoFor(change))
                finishCreate(change, newId)
            }
            PendingChange.Type.UPDATE -> {
                val server = fetchServerVersion(change.recipeId)
                when {
                    // Deleted on the server meanwhile: keep the edit as a new recipe.
                    server == null -> {
                        val newId = saveOnline(null, change.recipe!!, photoFor(change))
                        invalidate(change.recipeId)
                        finishCreate(change, newId)
                    }
                    // Unchanged since editing started: nothing has arrived, so upload normally.
                    !changedOnServer(change, server) -> {
                        saveOnline(change.recipeId, change.recipe!!, photoFor(change))
                        finishUpdate(change, null)
                    }
                    // Changed: maybe by our own earlier, timed-out save. Only if every edited
                    // field matches and no photo is still waiting; otherwise the user decides.
                    change.uncertain && !hasPendingPhoto(change) && sameContent(change.recipe, server.raw) ->
                        finishUpdate(change, server.dateModified)
                    else -> markConflict(change)
                }
            }
            PendingChange.Type.DELETE -> {
                val server = fetchServerVersion(change.recipeId)
                when {
                    server == null -> pending!!.removeIfUnchanged(change)
                    changedOnServer(change, server) -> markConflict(change)
                    else -> {
                        api.deleteRecipe(change.recipeId)
                        pending!!.removeIfUnchanged(change)
                        invalidate(change.recipeId)
                    }
                }
            }
        }
    }

    private fun hasPendingPhoto(change: PendingChange) = change.imageFile != null || change.imageWebUrl != null

    /**
     * The recipe an earlier, timed-out attempt may have created: same name,
     * created on the server after the change was made and with the same
     * content. An older recipe that just has the same name never matches.
     */
    private suspend fun findCreatedEarlier(change: PendingChange): String? {
        val candidates = api.allRecipesFromServer().filter { it.name == change.name }
        for (stub in candidates) {
            val server = fetchServerVersion(stub.id) ?: continue
            val created = parseServerTime(server.raw.string("dateCreated")) ?: continue
            if (created >= change.createdAt - CLOCK_TOLERANCE_MILLIS && sameContent(change.recipe, server.raw)) {
                return stub.id
            }
        }
        return null
    }

    /**
     * After the recipe of a queued CREATE exists on the server: forget the
     * entry, or, if it was edited again during the upload, keep that edit as an
     * UPDATE of the new recipe.
     */
    private suspend fun finishCreate(change: PendingChange, newId: String) {
        val queue = pending!!
        rememberUploaded(change.recipeId, newId)
        if (queue.removeIfUnchanged(change)) {
            change.imageFile?.let { File(it).delete() }
        } else {
            val base = fetchServerVersion(newId)?.dateModified
            convertRemaining(change) { it.copy(type = PendingChange.Type.UPDATE, recipeId = newId, baseModified = base) }
        }
        invalidate(newId)
    }

    /** After an UPDATE was saved: forget it, or keep a newer edit with an updated conflict base. */
    private suspend fun finishUpdate(change: PendingChange, knownModified: String?) {
        val queue = pending!!
        if (queue.removeIfUnchanged(change)) {
            change.imageFile?.let { File(it).delete() }
        } else {
            val base = knownModified ?: fetchServerVersion(change.recipeId)?.dateModified
            convertRemaining(change) { it.copy(baseModified = base) }
        }
        invalidate(change.recipeId)
    }

    private fun convertRemaining(uploaded: PendingChange, transform: (PendingChange) -> PendingChange) {
        val queue = pending!!
        val current = queue.current(uploaded) ?: return
        // The uploaded photo is no longer needed if the newer edit has its own.
        uploaded.imageFile?.takeIf { it != current.imageFile }?.let { File(it).delete() }
        queue.update { list ->
            list.map { if (it.id == uploaded.id) transform(it).copy(uncertain = false, error = null, conflict = false) else it }
        }
    }

    /** The recipe as it is on the server right now, or null if it no longer exists. */
    private suspend fun fetchServerVersion(id: String): Recipe? = try {
        api.recipeFresh(id)
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

    private fun changedOnServer(change: PendingChange, server: Recipe): Boolean =
        change.baseModified != null && server.dateModified != null && server.dateModified != change.baseModified

    private fun markConflict(change: PendingChange) {
        pending?.update { list -> list.map { if (it.id == change.id) it.copy(conflict = true, error = null) else it } }
        invalidate(change.recipeId)
    }

    private fun markUncertain(change: PendingChange) {
        pending?.update { list -> list.map { if (it.id == change.id) it.copy(uncertain = true) else it } }
    }

    private fun markError(change: PendingChange, e: Throwable) {
        val message = (e.message ?: e.javaClass.simpleName).take(200)
        pending?.update { list -> list.map { if (it.id == change.id) it.copy(error = message) else it } }
    }

    /**
     * Applies the user's decision for a conflicting change (needs a connection).
     * [copySuffix] is appended to the name of the copy for [ConflictChoice.BOTH].
     */
    suspend fun resolveConflict(change: PendingChange, choice: ConflictChoice, copySuffix: String) =
        withContext(Dispatchers.IO) {
            val queue = pending ?: return@withContext
            uploadMutex.withLock {
                // Already resolved (e.g. a second tap): nothing to do.
                if (queue.current(change)?.conflict != true) return@withLock
                when (choice) {
                    ConflictChoice.SERVER -> queue.remove(change)
                    ConflictChoice.MINE -> {
                        if (change.type == PendingChange.Type.DELETE) {
                            try {
                                api.deleteRecipe(change.recipeId)
                            } catch (e: HttpException) {
                                if (e.code != 404) throw e
                            }
                        } else {
                            saveOnline(change.recipeId, change.recipe!!, photoFor(change))
                        }
                        queue.remove(change)
                    }
                    ConflictChoice.BOTH -> {
                        if (change.type == PendingChange.Type.UPDATE) {
                            val copy = JsonObject(change.recipe!! + ("name" to JsonPrimitive("${change.name} $copySuffix")))
                            invalidate(saveOnline(null, copy, photoFor(change)))
                        }
                        queue.remove(change)
                    }
                }
                invalidate(change.recipeId)
            }
        }

    /** Throws away a queued change. */
    suspend fun discardPending(change: PendingChange) = withContext(Dispatchers.IO) {
        pending?.remove(change)
        invalidate(change.recipeId)
    }

    // ---------------------------------------------------------------- reading

    /** Existing keywords, most used first. */
    suspend fun keywordNames(): List<String> =
        runCatching { api.keywords() }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<Category> { it.recipeCount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it.name }

    suspend fun categoryNames(): List<String> =
        runCatching { categories() }.getOrDefault(emptyList()).filterNot { it.isUncategorized }.map { it.name }

    suspend fun categories(refresh: Boolean = false): List<Category> {
        val base = if (!refresh && categories != null) {
            categories!!
        } else {
            if (refresh) categoryRecipes.clear()
            api.categories().also { categories = it }
        }
        return overlayCategories(base, pendingChanges)
            .sortedWith(compareBy<Category> { it.isUncategorized }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private suspend fun allRecipesBase(refresh: Boolean): List<RecipeStub> {
        if (!refresh) allRecipes?.let { return it }
        return api.allRecipes().also { allRecipes = it }
    }

    suspend fun allRecipes(refresh: Boolean = false): List<RecipeStub> =
        overlayStubs(allRecipesBase(refresh), null, pendingChanges)

    /** Recipes of a category; pass [Category.UNCATEGORIZED_LIST_NAME] for uncategorized ones. */
    suspend fun recipesInCategory(category: String, refresh: Boolean = false): List<RecipeStub> {
        val base = if (!refresh && categoryRecipes[category] != null) {
            categoryRecipes[category]!!
        } else {
            val queryName = if (category == Category.UNCATEGORIZED_LIST_NAME) Category.UNCATEGORIZED_QUERY_NAME else category
            try {
                api.recipesInCategory(queryName).also { categoryRecipes[category] = it }
            } catch (e: Throwable) {
                // A category that only exists through offline changes has no stored list.
                if (overlayStubs(emptyList(), category, pendingChanges).isEmpty()) throw e
                emptyList()
            }
        }
        return overlayStubs(base, category, pendingChanges)
    }

    private suspend fun fetchRecipe(id: String, refresh: Boolean): Recipe {
        if (!refresh) recipes[id]?.let { return it }
        return api.recipe(id).also { recipes[id] = it }
    }

    suspend fun recipe(id: String, refresh: Boolean = false): Recipe {
        val target = resolveId(id)
        pending?.forRecipe(target)?.let { change ->
            change.recipe?.let { return Recipe(JsonObject(it + ("id" to JsonPrimitive(target)))) }
        }
        return fetchRecipe(target, refresh)
    }

    /** Photo of a queued change that is not on the server yet. */
    fun localImage(recipeId: String): File? =
        pending?.forRecipe(resolveId(recipeId))?.imageFile?.let(::File)?.takeIf { it.exists() }

    suspend fun search(query: String): List<RecipeStub> = overlayStubs(api.search(query), null, pendingChanges)
        .filter { stub -> !stub.id.startsWith(LOCAL_RECIPE_PREFIX) || stub.name.contains(query, ignoreCase = true) }

    // ---------------------------------------------------------------- sync

    /**
     * Brings the offline copy up to date: downloads every recipe that is new or
     * changed since the last sync (by dateModified) and forgets deleted ones.
     * Only answers that really came from the server are counted.
     * Returns the current recipe list, or null if the server was unreachable.
     */
    suspend fun syncOffline(onRecipe: (RecipeStub) -> Unit = {}): List<RecipeStub>? = withContext(Dispatchers.IO) {
        val store = store ?: return@withContext null
        val stubs = try {
            api.allRecipesFromServer().also { allRecipes = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isUnreachable(e)) return@withContext null else throw e
        }
        runCatching { categories(refresh = true) }
        val index = store.readIndex().toMutableMap()
        val limit = Semaphore(4)
        coroutineScope {
            stubs.map { stub ->
                async {
                    limit.withPermit {
                        val modified = stub.dateModified.orEmpty()
                        val known = synchronized(index) { index[stub.id] }
                        if (known != null && known != modified) {
                            // Changed elsewhere (e.g. in the web interface): the photo may be new too.
                            onImagesOutdated(stub.id)
                        }
                        if (known != modified || modified.isEmpty()) {
                            runCatching { api.recipeFromServer(stub.id) }.onSuccess { recipe ->
                                recipes[stub.id] = recipe
                                synchronized(index) { index[stub.id] = modified }
                            }
                        }
                        onRecipe(stub)
                    }
                }
            }.awaitAll()
        }
        val current = stubs.map { it.id }.toSet()
        index.keys.filter { it !in current }.forEach { id ->
            store.delete(api.recipeUrl(id))
            index.remove(id)
        }
        store.writeIndex(index)
        store.markSynced()
        stubs
    }

    fun imageUrl(recipeId: String, size: String = "thumb") = api.imageUrl(resolveId(recipeId), size)

    private companion object {
        /** Allowed difference between the clocks of phone and server. */
        const val CLOCK_TOLERANCE_MILLIS = 10L * 60 * 1000
    }
}
