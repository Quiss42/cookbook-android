package de.ex_parrot.cookbook.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.ui.components.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration

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
    var imageUrl by mutableStateOf("")
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
        imageUrl = (recipe.raw["image"] as? JsonPrimitive)?.content.orEmpty()
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

    val hasChanges: Boolean get() = currentState() != snapshot

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
        putString("image", imageUrl)
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
                val json = toJson()
                val id = if (recipeId == null) repository.createRecipe(json) else repository.updateRecipe(recipeId, json)
                snapshot = currentState()
                SaveState.Saved(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                SaveState.Failed(e)
            }
        }
    }

    fun dismissError() {
        if (saveState is SaveState.Failed) saveState = SaveState.Idle
    }
}
