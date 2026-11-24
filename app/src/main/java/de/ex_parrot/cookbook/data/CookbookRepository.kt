package de.ex_parrot.cookbook.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    /** Offline ids of recipes that have been uploaded meanwhile -> their server ids. */
    private val uploadedIds = ConcurrentHashMap<String, String>()
    private val uploadMutex = Mutex()

    private val pending get() = store?.pending

    /** Called when a recipe's stored pictures are outdated (set by the session). */
    var onImagesOutdated: (recipeId: String) -> Unit = {}

    /** Called after a change was queued, so the session can try to upload it. */
    var onChangeQueued: () -> Unit = {}

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
    ): SaveResult {
        val queue = pending
        val target = id?.let(::resolveId)
        // Keep the order: if this recipe already has queued changes, queue this one too.
        if (queue != null && target != null && (target.startsWith(LOCAL_RECIPE_PREFIX) || queue.forRecipe(target) != null)) {
            return enqueueSave(target, recipe, localImage, baseModified, fromCategory)
        }
        return try {
            val savedId = saveOnline(target, recipe, localImage?.absolutePath)
            invalidate(savedId)
            SaveResult(savedId, queued = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (queue == null || !isUnreachable(e)) throw e
            enqueueSave(target, recipe, localImage, baseModified, fromCategory)
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
    ): SaveResult {
        val queue = pending!!
        val image = localImage?.let(queue::keepImage)
        val name = recipe.string("name")
        val existing = id?.let(queue::forRecipe)
        val resultId: String
        when {
            id == null -> {
                resultId = "$LOCAL_RECIPE_PREFIX${System.currentTimeMillis()}"
                queue.update {
                    it + PendingChange(queue.nextId(), PendingChange.Type.CREATE, resultId, name, recipe, imageFile = image)
                }
            }
            existing != null && existing.type != PendingChange.Type.DELETE -> {
                resultId = id
                // Merge into the queued change; keep its original base for the conflict check.
                existing.imageFile?.takeIf { image != null }?.let { File(it).delete() }
                queue.update { list ->
                    list.map { c ->
                        if (c.id == existing.id) c.copy(name = name, recipe = recipe, imageFile = image ?: c.imageFile, error = null) else c
                    }
                }
            }
            else -> {
                resultId = id
                queue.update {
                    it + PendingChange(
                        queue.nextId(), PendingChange.Type.UPDATE, id, name, recipe,
                        imageFile = image, baseModified = baseModified, fromCategory = fromCategory,
                    )
                }
            }
        }
        invalidate(resultId)
        onChangeQueued()
        return SaveResult(resultId, queued = true)
    }

    /** Deletes a recipe; without a connection the deletion is queued. Returns true if queued. */
    suspend fun deleteRecipe(id: String, name: String, category: String, baseModified: String?): Boolean {
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
                                c.copy(type = PendingChange.Type.DELETE, recipe = null, imageFile = null, conflict = false, error = null)
                            } else c
                        }
                    }
                }
            }
            invalidate(target)
            onChangeQueued()
            return existing.type != PendingChange.Type.CREATE
        }
        return try {
            api.deleteRecipe(target)
            invalidate(target)
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (queue == null || !isUnreachable(e)) throw e
            queue.update {
                it + PendingChange(
                    queue.nextId(), PendingChange.Type.DELETE, target, name,
                    baseModified = baseModified, fromCategory = category,
                )
            }
            invalidate(target)
            onChangeQueued()
            true
        }
    }

    suspend fun importRecipe(url: String): Recipe = api.importRecipe(url).also { invalidate(it.id) }

    // ---------------------------------------------------------------- upload

    /**
     * Uploads queued changes in order. Stops at the first "not reachable" error.
     * Recipes changed on the server meanwhile are marked as conflicts.
     */
    suspend fun uploadPending() {
        val queue = pending ?: return
        uploadMutex.withLock {
            for (snapshot in queue.changes.value) {
                val change = queue.changes.value.firstOrNull { it.id == snapshot.id } ?: continue
                if (change.conflict) continue
                try {
                    upload(change)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isUnreachable(e)) return
                    markError(change, e)
                }
            }
        }
    }

    private suspend fun upload(change: PendingChange) {
        val queue = pending!!
        when (change.type) {
            PendingChange.Type.CREATE -> {
                val newId = saveOnline(null, change.recipe!!, change.imageFile)
                uploadedIds[change.recipeId] = newId
                queue.remove(change)
                invalidate(newId)
            }
            PendingChange.Type.UPDATE -> {
                val server = fetchServerVersion(change.recipeId)
                when {
                    // Deleted on the server meanwhile: keep the edit as a new recipe.
                    server == null -> {
                        val newId = saveOnline(null, change.recipe!!, change.imageFile)
                        uploadedIds[change.recipeId] = newId
                        queue.remove(change)
                        invalidate(change.recipeId)
                        invalidate(newId)
                    }
                    changedOnServer(change, server) -> markConflict(change)
                    else -> {
                        saveOnline(change.recipeId, change.recipe!!, change.imageFile)
                        queue.remove(change)
                        invalidate(change.recipeId)
                    }
                }
            }
            PendingChange.Type.DELETE -> {
                val server = fetchServerVersion(change.recipeId)
                when {
                    server == null -> queue.remove(change)
                    changedOnServer(change, server) -> markConflict(change)
                    else -> {
                        api.deleteRecipe(change.recipeId)
                        queue.remove(change)
                        invalidate(change.recipeId)
                    }
                }
            }
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

    private fun markError(change: PendingChange, e: Throwable) {
        val message = (e.message ?: e.javaClass.simpleName).take(200)
        pending?.update { list -> list.map { if (it.id == change.id) it.copy(error = message) else it } }
    }

    /** Applies the user's decision for a conflicting change (needs a connection). */
    suspend fun resolveConflict(change: PendingChange, choice: ConflictChoice) {
        val queue = pending ?: return
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
                    saveOnline(change.recipeId, change.recipe!!, change.imageFile)
                }
                queue.remove(change)
            }
            ConflictChoice.BOTH -> {
                if (change.type == PendingChange.Type.UPDATE) {
                    val copy = JsonObject(change.recipe!! + ("name" to JsonPrimitive("${change.name} (Handy)")))
                    invalidate(saveOnline(null, copy, change.imageFile))
                }
                queue.remove(change)
            }
        }
        invalidate(change.recipeId)
    }

    /** Throws away a queued change. */
    fun discardPending(change: PendingChange) {
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
        return overlayCategories(base)
            .sortedWith(compareBy<Category> { it.isUncategorized }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private suspend fun allRecipesBase(refresh: Boolean): List<RecipeStub> {
        if (!refresh) allRecipes?.let { return it }
        return api.allRecipes().also { allRecipes = it }
    }

    suspend fun allRecipes(refresh: Boolean = false): List<RecipeStub> = overlayStubs(allRecipesBase(refresh), null)

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
                if (overlayStubs(emptyList(), category).isEmpty()) throw e
                emptyList()
            }
        }
        return overlayStubs(base, category)
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

    suspend fun search(query: String): List<RecipeStub> = overlayStubs(api.search(query), null)
        .filter { stub -> stub.id.startsWith(LOCAL_RECIPE_PREFIX).not() || stub.name.contains(query, ignoreCase = true) }

    private fun categoryOf(recipe: JsonObject?): String =
        recipe?.string("recipeCategory")?.trim().orEmpty().ifEmpty { Category.UNCATEGORIZED_LIST_NAME }

    private fun matches(listName: String?, category: String) = listName == null || listName == category

    /** Shows queued changes in recipe lists ([category] null = all recipes). */
    private fun overlayStubs(base: List<RecipeStub>, category: String?): List<RecipeStub> {
        val changes = pending?.changes?.value.orEmpty()
        if (changes.isEmpty()) return base.sortedBy { it.name.lowercase() }
        val byId = changes.associateBy { it.recipeId }
        val result = base.mapNotNull { stub ->
            val change = byId[stub.id] ?: return@mapNotNull stub
            when (change.type) {
                PendingChange.Type.DELETE -> null
                else -> if (matches(category, categoryOf(change.recipe))) {
                    stub.copy(name = change.name, keywords = change.recipe?.string("keywords").orEmpty())
                } else null
            }
        }.toMutableList()
        changes.filter { it.type != PendingChange.Type.DELETE && result.none { s -> s.id == it.recipeId } }
            .filter { matches(category, categoryOf(it.recipe)) }
            .filter { it.type == PendingChange.Type.CREATE || category != null }
            .forEach { result += RecipeStub(it.recipeId, it.name, it.recipe?.string("keywords").orEmpty(), null) }
        return result.sortedBy { it.name.lowercase() }
    }

    /** Adjusts category counts for queued changes. */
    private fun overlayCategories(base: List<Category>): List<Category> {
        val changes = pending?.changes?.value.orEmpty()
        if (changes.isEmpty()) return base
        val counts = LinkedHashMap<String, Int>()
        base.forEach { counts[it.name] = it.recipeCount }
        fun add(name: String?, delta: Int) {
            if (name == null) return
            counts[name] = (counts[name] ?: 0) + delta
        }
        changes.forEach { change ->
            val from = change.fromCategory?.trim()?.ifEmpty { Category.UNCATEGORIZED_LIST_NAME }
            when (change.type) {
                PendingChange.Type.CREATE -> add(categoryOf(change.recipe), 1)
                PendingChange.Type.DELETE -> add(from, -1)
                PendingChange.Type.UPDATE -> {
                    val to = categoryOf(change.recipe)
                    if (from != null && from != to) {
                        add(from, -1)
                        add(to, 1)
                    }
                }
            }
        }
        return counts.filter { it.value > 0 }.map { Category(it.key, it.value) }
    }

    // ---------------------------------------------------------------- sync

    /**
     * Brings the offline copy up to date: downloads every recipe that is new or
     * changed since the last sync (by dateModified) and forgets deleted ones.
     * Returns the current recipe list, or null if the server was unreachable.
     */
    suspend fun syncOffline(onRecipe: (RecipeStub) -> Unit = {}): List<RecipeStub>? {
        val store = store ?: return null
        val stubs = allRecipesBase(refresh = true)
        if (store.offline.value) return null // list came from the phone, not the server
        categories(refresh = true)
        val index = store.readIndex().toMutableMap()
        val limit = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
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
                            // Fetching stores the answer as the offline copy.
                            runCatching { fetchRecipe(stub.id, refresh = true) }.onSuccess {
                                // Only count it if it really came from the server.
                                if (!store.offline.value) synchronized(index) { index[stub.id] = modified }
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
        return stubs
    }

    fun imageUrl(recipeId: String, size: String = "thumb") = api.imageUrl(resolveId(recipeId), size)
}
