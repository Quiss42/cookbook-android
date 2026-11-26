package de.ex_parrot.cookbook.ui.recipe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.CookingProgress
import de.ex_parrot.cookbook.data.CookingProgressStore
import kotlinx.coroutines.CancellationException
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.ui.components.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class RecipeViewModel(
    private val repository: CookbookRepository,
    private val recipeId: String,
    private val progressStore: CookingProgressStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<Recipe>>(Loadable.Loading)
    val state: StateFlow<Loadable<Recipe>> = _state

    /** Indices of ingredients / steps the user has ticked off while cooking (remembered per recipe). */
    val checkedIngredients = mutableStateListOf<Int>()
    val doneSteps = mutableStateListOf<Int>()
    private var progressLoaded = false

    /** Set after a successful delete; the screen then closes. */
    var deleted by mutableStateOf(false)
        private set
    var actionError by mutableStateOf<Throwable?>(null)

    init {
        load(refresh = false)
        viewModelScope.launch { repository.changes.drop(1).collect { if (!deleted) load(refresh = false) } }
    }

    /** True if the deletion was queued because there is no connection. */
    var deletionQueued by mutableStateOf(false)
        private set

    fun delete() {
        val recipe = (_state.value as? Loadable.Ready)?.value ?: return
        viewModelScope.launch {
            try {
                deletionQueued = repository.deleteRecipe(recipeId, recipe.name, recipe.category, recipe.dateModified)
                deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actionError = e
            }
        }
    }

    fun load(refresh: Boolean = true) {
        viewModelScope.launch {
            val current = _state.value
            if (current is Loadable.Ready) _state.value = current.copy(refreshing = refresh) else _state.value = Loadable.Loading
            try {
                val recipe = repository.recipe(recipeId, refresh)
                if (!progressLoaded) restoreProgress(recipe)
                _state.value = Loadable.Ready(recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = if (current is Loadable.Ready) current.copy(refreshing = false) else Loadable.Failed(e)
            }
        }
    }

    fun toggleIngredient(index: Int) = toggle(checkedIngredients, index)
    fun toggleStep(index: Int) = toggle(doneSteps, index)

    private fun toggle(list: MutableList<Int>, index: Int) {
        if (!list.remove(index)) list.add(index)
        saveProgress()
    }

    private fun restoreProgress(recipe: Recipe) {
        progressLoaded = true
        val progress = progressStore?.load(recipeId, recipe.ingredients.size, recipe.instructions.size) ?: return
        checkedIngredients.addAll(progress.ingredients)
        doneSteps.addAll(progress.steps)
    }

    private fun saveProgress() {
        val recipe = (_state.value as? Loadable.Ready)?.value ?: return
        progressStore?.save(
            recipeId,
            CookingProgress(
                ingredients = checkedIngredients.toSet(),
                steps = doneSteps.toSet(),
                ingredientCount = recipe.ingredients.size,
                stepCount = recipe.instructions.size,
            ),
        )
    }

    /** A photo waiting for upload is shown from the phone. */
    fun imageUrl(size: String): String =
        repository.localImage(recipeId)?.let { "file://${it.absolutePath}" } ?: repository.imageUrl(recipeId, size)
}
