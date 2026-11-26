package de.ex_parrot.cookbook.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.isUnreachable
import de.ex_parrot.cookbook.data.localizeDecimals
import de.ex_parrot.cookbook.ui.importer.normalizeWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import de.ex_parrot.cookbook.data.CloudEntry
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.Http
import de.ex_parrot.cookbook.data.ImageTools
import de.ex_parrot.cookbook.data.NextcloudFiles
import de.ex_parrot.cookbook.data.EDITED_RECIPE_KEYS
import de.ex_parrot.cookbook.data.SERVER_RECIPE_KEYS
import de.ex_parrot.cookbook.data.SERVING_SIZE_KEY
import de.ex_parrot.cookbook.data.SHOWN_NUTRITION_KEYS
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.ui.components.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Duration

/** What should happen with the recipe image when saving. */
sealed interface ImageSelection {
    /** Leave the image as it is. */
    data object Keep : ImageSelection
    data object Removed : ImageSelection
    /** A file in the user's Nextcloud; Cookbook copies it into the recipe folder. */
    data class Cloud(val path: String, val fileId: String?) : ImageSelection
    /** A prepared JPEG on the phone; uploaded temporarily when saving. */
    data class Local(val file: File) : ImageSelection
    /** A web photo that could not be downloaded yet (no connection); fetched when saving/uploading. */
    data class Web(val url: String) : ImageSelection
}

/** Everything being edited, kept in the saved state so it survives Android ending the app. */
@Serializable
private data class EditorDraft(
    val original: String,
    val originalModified: String?,
    val originalCategory: String?,
    val originalImage: String,
    val originalImageVersion: String?,
    val snapshot: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val url: String,
    val servings: String,
    val times: List<String>,
    val nutrition: Map<String, String>,
    val removedKeys: List<String>,
    val removedNutritionKeys: List<String>,
    val tools: List<String>,
    val ingredients: List<String>,
    val instructions: List<String>,
    /** Image choice: kind ("keep", "removed", "cloud", "local", "web") and value. */
    val imageKind: String,
    val imageValue: String? = null,
    val imageFileId: String? = null,
)

/** Hours and minutes as edited in two text fields. */
class DurationField {
    var hours by mutableStateOf("")
    var minutes by mutableStateOf("")

    fun set(duration: Duration?) {
        val h = duration?.toHours() ?: 0
        val m = (duration?.toMinutes() ?: 0) % 60
        hours = if (h > 0) h.toString() else ""
        minutes = if (m > 0) m.toString() else ""
    }

    /** ISO 8601 value for the server, or "" if empty. */
    fun toIso(): String {
        val h = hours.trim().toLongOrNull() ?: 0
        val m = minutes.trim().toLongOrNull() ?: 0
        val total = h * 60 + m
        return if (total <= 0) "" else "PT${total / 60}H${total % 60}M"
    }
}

/** A value stored in the recipe that the app does not show. */
data class HiddenEntry(
    /** Top-level recipe key, or the key inside "nutrition" if [isNutrition]. */
    val key: String,
    val isNutrition: Boolean,
    val preview: String,
)

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    /** [queued]: no connection, the change will be uploaded later. */
    data class Saved(val id: String, val queued: Boolean = false) : SaveState
    data class Failed(val error: Throwable) : SaveState
}

class EditRecipeViewModel(
    private val repository: CookbookRepository,
    val files: NextcloudFiles,
    private val appContext: Context,
    private val recipeId: String?,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val isNew = recipeId == null

    private val _loadState = MutableStateFlow<Loadable<Unit>>(if (isNew) Loadable.Ready(Unit) else Loadable.Loading)
    val loadState: StateFlow<Loadable<Unit>> = _loadState

    var saveState by mutableStateOf<SaveState>(SaveState.Idle)
        private set

    private var original: JsonObject = JsonObject(emptyMap())
    private var originalModified: String? = null
    private var originalCategory: String? = null
    private var snapshot: String = ""

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var category by mutableStateOf("")
    val keywords: SnapshotStateList<String> = mutableStateListOf()
    var url by mutableStateOf("")
    /** The "image" value stored in the recipe when it was loaded. */
    private var originalImage = ""
    var hasOriginalImage by mutableStateOf(false)
        private set
    var originalImageVersion by mutableStateOf<String?>(null)
        private set
    var imageSelection by mutableStateOf<ImageSelection>(ImageSelection.Keep)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    var imageError by mutableStateOf<Throwable?>(null)
    var servings by mutableStateOf("")
    val prepTime = DurationField()
    val cookTime = DurationField()
    val totalTime = DurationField()
    /** Editable nutrition values by key (see [SHOWN_NUTRITION_KEYS]). */
    val nutrition = mutableStateMapOf<String, String>()
    private val removedKeys = mutableStateListOf<String>()
    private val removedNutritionKeys = mutableStateListOf<String>()
    val tools: SnapshotStateList<String> = mutableStateListOf()
    val ingredients: SnapshotStateList<String> = mutableStateListOf()
    val instructions: SnapshotStateList<String> = mutableStateListOf()

    var categorySuggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var keywordSuggestions by mutableStateOf<List<String>>(emptyList())
        private set

    fun toggleKeyword(keyword: String) {
        val existing = keywords.firstOrNull { it.equals(keyword, ignoreCase = true) }
        if (existing != null) keywords.remove(existing) else keywords.add(keyword.trim())
    }

    /** Adds a typed keyword (commas split several). Returns true if something was added. */
    fun addKeywords(text: String): Boolean {
        var added = false
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { word ->
            if (keywords.none { it.equals(word, ignoreCase = true) }) {
                // Reuse the spelling of an existing keyword if there is one.
                keywords.add(keywordSuggestions.firstOrNull { it.equals(word, ignoreCase = true) } ?: word)
                added = true
            }
        }
        return added
    }

    init {
        viewModelScope.launch { categorySuggestions = repository.categoryNames() }
        viewModelScope.launch { keywordSuggestions = repository.keywordNames() }
        val restored = savedState.get<String>(DRAFT_KEY)?.let { runCatching { restore(it) }.isSuccess } == true
        when {
            restored -> _loadState.value = Loadable.Ready(Unit)
            recipeId != null -> load(recipeId)
            else -> snapshot = currentState()
        }
        keepDraft()
    }

    /** Writes the editor content to the saved state whenever it changes. */
    @OptIn(FlowPreview::class)
    private fun keepDraft() {
        viewModelScope.launch {
            snapshotFlow { if (_loadState.value is Loadable.Ready) draftJson() else null }
                .debounce(300)
                .collect { draft -> if (draft != null) savedState[DRAFT_KEY] = draft }
        }
    }

    private fun draftJson(): String {
        val (kind, value, fileId) = when (val selection = imageSelection) {
            ImageSelection.Keep -> Triple("keep", null, null)
            ImageSelection.Removed -> Triple("removed", null, null)
            is ImageSelection.Cloud -> Triple("cloud", selection.path, selection.fileId)
            is ImageSelection.Local -> Triple("local", selection.file.path, null)
            is ImageSelection.Web -> Triple("web", selection.url, null)
        }
        val draft = EditorDraft(
            original = original.toString(),
            originalModified = originalModified,
            originalCategory = originalCategory,
            originalImage = originalImage,
            originalImageVersion = originalImageVersion,
            snapshot = snapshot,
            name = name,
            description = description,
            category = category,
            keywords = keywords.toList(),
            url = url,
            servings = servings,
            times = listOf(prepTime, cookTime, totalTime).flatMap { listOf(it.hours, it.minutes) },
            nutrition = nutrition.toMap(),
            removedKeys = removedKeys.toList(),
            removedNutritionKeys = removedNutritionKeys.toList(),
            tools = tools.toList(),
            ingredients = ingredients.toList(),
            instructions = instructions.toList(),
            imageKind = kind,
            imageValue = value,
            imageFileId = fileId,
        )
        return Json.encodeToString(EditorDraft.serializer(), draft)
    }

    private fun restore(text: String) {
        val draft = Json.decodeFromString(EditorDraft.serializer(), text)
        original = Json.parseToJsonElement(draft.original) as JsonObject
        originalModified = draft.originalModified
        originalCategory = draft.originalCategory
        originalImage = draft.originalImage
        hasOriginalImage = originalImage.isNotBlank()
        originalImageVersion = draft.originalImageVersion
        name = draft.name
        description = draft.description
        category = draft.category
        keywords.replaceWith(draft.keywords)
        url = draft.url
        servings = draft.servings
        listOf(prepTime, cookTime, totalTime).forEachIndexed { i, field ->
            field.hours = draft.times.getOrElse(i * 2) { "" }
            field.minutes = draft.times.getOrElse(i * 2 + 1) { "" }
        }
        nutrition.clear()
        nutrition.putAll(draft.nutrition)
        removedKeys.replaceWith(draft.removedKeys)
        removedNutritionKeys.replaceWith(draft.removedNutritionKeys)
        tools.replaceWith(draft.tools)
        ingredients.replaceWith(draft.ingredients)
        instructions.replaceWith(draft.instructions)
        imageSelection = when (draft.imageKind) {
            "removed" -> ImageSelection.Removed
            "cloud" -> ImageSelection.Cloud(draft.imageValue.orEmpty(), draft.imageFileId)
            "local" -> File(draft.imageValue.orEmpty()).takeIf { it.exists() }?.let { ImageSelection.Local(it) }
                ?: ImageSelection.Keep
            "web" -> ImageSelection.Web(draft.imageValue.orEmpty())
            else -> ImageSelection.Keep
        }
        snapshot = draft.snapshot
    }

    fun load(id: String = recipeId!!) {
        viewModelScope.launch {
            _loadState.value = Loadable.Loading
            try {
                fill(repository.recipe(id, refresh = true))
                _loadState.value = Loadable.Ready(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _loadState.value = Loadable.Failed(e)
            }
        }
    }

    private fun fill(recipe: Recipe) {
        original = recipe.raw
        originalModified = recipe.dateModified
        originalCategory = recipe.category
        name = recipe.name
        description = recipe.description
        category = recipe.category
        keywords.replaceWith(recipe.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        url = recipe.url
        originalImage = (recipe.raw["image"] as? JsonPrimitive)?.content.orEmpty()
        hasOriginalImage = originalImage.isNotBlank()
        originalImageVersion = recipe.dateModified
        servings = recipe.recipeYield?.toString().orEmpty()
        prepTime.set(recipe.prepTime)
        cookTime.set(recipe.cookTime)
        totalTime.set(recipe.totalTime)
        nutrition.clear()
        recipe.nutrition.forEach { (key, value) ->
            // Shown with the phone's decimal separator, stored with "." (see toJson).
            if (key in SHOWN_NUTRITION_KEYS) nutrition[key] = localizeDecimals(value)
        }
        removedKeys.clear()
        removedNutritionKeys.clear()
        tools.replaceWith(recipe.tools)
        ingredients.replaceWith(recipe.ingredients)
        instructions.replaceWith(recipe.instructions)
        snapshot = currentState()
    }

    private fun SnapshotStateList<String>.replaceWith(items: List<String>) {
        clear()
        addAll(items)
    }

    private fun currentState(): String = toJson().toString()

    val hasChanges: Boolean get() = currentState() != snapshot || imageSelection != ImageSelection.Keep

    fun originalImageUrl(): String? =
        if (recipeId != null && hasOriginalImage) repository.imageUrl(recipeId, "full") else null

    fun selectCloudImage(entry: CloudEntry) {
        imageSelection = ImageSelection.Cloud(entry.path, entry.fileId)
    }

    fun removeImage() {
        imageSelection = ImageSelection.Removed
    }

    fun resetImage() {
        imageSelection = ImageSelection.Keep
    }

    /** Downloads the image (or a page's preview image) on the phone; offline, the address is remembered. */
    fun useWebImage(url: String) {
        val address = url.trim()
        viewModelScope.launch {
            imageBusy = true
            imageError = null
            try {
                imageSelection = ImageSelection.Local(ImageTools.prepare(appContext, ImageTools.download(Http.client(), address)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isUnreachable(e)) {
                    imageSelection = ImageSelection.Web(address)
                } else {
                    imageError = e
                }
            } finally {
                imageBusy = false
            }
        }
    }

    /** Uses a photo from the camera or gallery. */
    fun useLocalImage(uri: Uri) = prepareImage { ImageTools.prepare(appContext, uri) }

    private fun prepareImage(block: suspend () -> File) {
        viewModelScope.launch {
            imageBusy = true
            imageError = null
            try {
                imageSelection = ImageSelection.Local(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                imageError = e
            } finally {
                imageBusy = false
            }
        }
    }

    val canSave: Boolean get() = name.isNotBlank() && saveState != SaveState.Saving

    /** Values in the recipe that the app neither shows nor edits. */
    val hiddenEntries: List<HiddenEntry>
        get() {
            val topLevel = original.entries
                .filter { (key, _) -> key !in EDITED_RECIPE_KEYS && key !in SERVER_RECIPE_KEYS && key !in removedKeys }
                .map { (key, value) -> HiddenEntry(key, isNutrition = false, preview = previewOf(value)) }
            val nutritionEntries = (original["nutrition"] as? JsonObject)?.entries.orEmpty()
                .filter { (key, value) ->
                    !key.startsWith("@") && key !in SHOWN_NUTRITION_KEYS && key != SERVING_SIZE_KEY &&
                        key !in removedNutritionKeys &&
                        previewOf(value).isNotBlank()
                }
                .map { (key, value) -> HiddenEntry(key, isNutrition = true, preview = previewOf(value)) }
            return nutritionEntries + topLevel
        }

    private fun previewOf(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        is JsonObject -> value.entries.joinToString(", ") { "${it.key}: ${(it.value as? JsonPrimitive)?.content ?: it.value}" }
    }.take(200)

    fun removeHidden(entry: HiddenEntry) {
        if (entry.isNutrition) removedNutritionKeys.add(entry.key) else removedKeys.add(entry.key)
    }

    fun removeAllHidden() {
        hiddenEntries.forEach(::removeHidden)
    }

    private fun toJson(): JsonObject {
        val fields = original.toMutableMap()
        removedKeys.forEach { fields.remove(it) }
        // Added by the server on every read; not part of the recipe.
        fields.remove("printImage")
        val nutritionFields = ((original["nutrition"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        removedNutritionKeys.forEach { nutritionFields.remove(it) }
        SHOWN_NUTRITION_KEYS.forEach { key ->
            // Stored with "." as in schema.org; shown with the phone's separator.
            val value = localizeDecimals(nutrition[key]?.trim().orEmpty(), separator = '.')
            if (value.isEmpty()) nutritionFields.remove(key) else nutritionFields[key] = JsonPrimitive(value)
        }
        // The values are per serving, so state that whenever there are any.
        if (SHOWN_NUTRITION_KEYS.any { !nutrition[it].isNullOrBlank() }) {
            nutritionFields[SERVING_SIZE_KEY] = JsonPrimitive(appContext.getString(R.string.nutrition_serving_size_value))
        }
        nutritionFields["@type"] = JsonPrimitive("NutritionInformation")
        fields["nutrition"] = JsonObject(nutritionFields)
        fun putString(key: String, value: String) { fields[key] = JsonPrimitive(value.trim()) }
        fun putList(key: String, values: List<String>) {
            fields[key] = JsonArray(values.map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
        }
        putString("name", name)
        putString("description", description)
        putString("recipeCategory", category)
        putString("keywords", keywords.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        putString("url", if (url.isBlank()) "" else normalizeWebUrl(url))
        putString("image", originalImage)
        fields["recipeYield"] = JsonPrimitive(servings.trim().toIntOrNull() ?: 1)
        putString("prepTime", prepTime.toIso())
        putString("cookTime", cookTime.toIso())
        putString("totalTime", totalTime.toIso())
        putList("tool", tools)
        putList("recipeIngredient", ingredients)
        putList("recipeInstructions", instructions)
        return JsonObject(fields)
    }

    fun save() {
        if (!canSave) return
        viewModelScope.launch {
            saveState = SaveState.Saving
            saveState = try {
                val selection = imageSelection
                val image = when (selection) {
                    ImageSelection.Keep -> originalImage
                    ImageSelection.Removed -> ""
                    is ImageSelection.Cloud -> selection.path
                    // The photo is uploaded by the repository (now or, offline, later).
                    is ImageSelection.Local -> originalImage
                    is ImageSelection.Web -> originalImage
                }
                val json = JsonObject(toJson() + ("image" to JsonPrimitive(image)))
                val result = repository.saveRecipe(
                    id = recipeId,
                    recipe = json,
                    localImage = (selection as? ImageSelection.Local)?.file,
                    baseModified = originalModified,
                    fromCategory = originalCategory,
                    webImage = (selection as? ImageSelection.Web)?.url,
                )
                originalImage = image
                imageSelection = ImageSelection.Keep
                snapshot = currentState()
                // Saved: nothing left to restore.
                savedState.remove<String>(DRAFT_KEY)
                SaveState.Saved(result.id, result.queued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SaveState.Failed(e)
            }
        }
    }

    fun dismissError() {
        if (saveState is SaveState.Failed) saveState = SaveState.Idle
    }

    private companion object {
        const val DRAFT_KEY = "editor_draft"
    }
}
