package de.ex_parrot.cookbook.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.CloudEntry
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.Http
import de.ex_parrot.cookbook.data.ImageTools
import de.ex_parrot.cookbook.data.NextcloudFiles
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
}

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

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val id: String) : SaveState
    data class Failed(val error: Throwable) : SaveState
}

class EditRecipeViewModel(
    private val repository: CookbookRepository,
    val files: NextcloudFiles,
    private val appContext: Context,
    private val recipeId: String?,
) : ViewModel() {
    val isNew = recipeId == null

    private val _loadState = MutableStateFlow<Loadable<Unit>>(if (isNew) Loadable.Ready(Unit) else Loadable.Loading)
    val loadState: StateFlow<Loadable<Unit>> = _loadState

    var saveState by mutableStateOf<SaveState>(SaveState.Idle)
        private set

    private var original: JsonObject = JsonObject(emptyMap())
    private var snapshot: String = ""

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var category by mutableStateOf("")
    var keywords by mutableStateOf("")
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
    val tools: SnapshotStateList<String> = mutableStateListOf()
    val ingredients: SnapshotStateList<String> = mutableStateListOf()
    val instructions: SnapshotStateList<String> = mutableStateListOf()

    var categorySuggestions by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch { categorySuggestions = repository.categoryNames() }
        if (recipeId != null) load(recipeId) else snapshot = currentState()
    }

    fun load(id: String = recipeId!!) {
        viewModelScope.launch {
            _loadState.value = Loadable.Loading
            try {
                fill(repository.recipe(id, refresh = true))
                _loadState.value = Loadable.Ready(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _loadState.value = Loadable.Failed(e)
            }
        }
    }

    private fun fill(recipe: Recipe) {
        original = recipe.raw
        name = recipe.name
        description = recipe.description
        category = recipe.category
        keywords = recipe.keywords
        url = recipe.url
        originalImage = (recipe.raw["image"] as? JsonPrimitive)?.content.orEmpty()
        hasOriginalImage = originalImage.isNotBlank()
        originalImageVersion = recipe.dateModified
        servings = recipe.recipeYield?.toString().orEmpty()
        prepTime.set(recipe.prepTime)
        cookTime.set(recipe.cookTime)
        totalTime.set(recipe.totalTime)
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
        if (recipeId != null && hasOriginalImage) repository.imageUrl(recipeId, "full", originalImageVersion) else null

    fun selectCloudImage(entry: CloudEntry) {
        imageSelection = ImageSelection.Cloud(entry.path, entry.fileId)
    }

    fun removeImage() {
        imageSelection = ImageSelection.Removed
    }

    fun resetImage() {
        imageSelection = ImageSelection.Keep
    }

    /** Downloads the image (or a page's preview image) on the phone. */
    fun useWebImage(url: String) = prepareImage {
        val bytes = ImageTools.download(Http.client(false), url.trim())
        ImageTools.prepare(appContext, bytes)
    }

    /** Uses a photo from the camera or gallery. */
    fun useLocalImage(uri: Uri) = prepareImage { ImageTools.prepare(appContext, uri) }

    private fun prepareImage(block: suspend () -> File) {
        viewModelScope.launch {
            imageBusy = true
            imageError = null
            try {
                imageSelection = ImageSelection.Local(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                imageError = e
            } finally {
                imageBusy = false
            }
        }
    }

    val canSave: Boolean get() = name.isNotBlank() && saveState != SaveState.Saving

    private fun toJson(): JsonObject {
        val fields = original.toMutableMap()
        fun putString(key: String, value: String) { fields[key] = JsonPrimitive(value.trim()) }
        fun putList(key: String, values: List<String>) {
            fields[key] = JsonArray(values.map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
        }
        putString("name", name)
        putString("description", description)
        putString("recipeCategory", category)
        putString("keywords", keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        putString("url", url)
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
            var tempPath: String? = null
            saveState = try {
                val image = when (val selection = imageSelection) {
                    ImageSelection.Keep -> originalImage
                    ImageSelection.Removed -> ""
                    is ImageSelection.Cloud -> selection.path
                    is ImageSelection.Local -> {
                        // Cookbook copies files given by path into the recipe folder,
                        // so a temporary upload is enough.
                        val path = "/.cookbook-upload-${System.currentTimeMillis()}.jpg"
                        files.upload(path, selection.file.readBytes(), "image/jpeg")
                        tempPath = path
                        path
                    }
                }
                val json = JsonObject(toJson() + ("image" to JsonPrimitive(image)))
                val id = if (recipeId == null) repository.createRecipe(json) else repository.updateRecipe(recipeId, json)
                originalImage = image
                imageSelection = ImageSelection.Keep
                snapshot = currentState()
                SaveState.Saved(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                SaveState.Failed(e)
            } finally {
                tempPath?.let { path -> runCatching { files.delete(path) } }
            }
        }
    }

    fun dismissError() {
        if (saveState is SaveState.Failed) saveState = SaveState.Idle
    }
}
