package de.ex_parrot.cookbook.ui.recipe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.ui.components.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class RecipeViewModel(
    private val repository: CookbookRepository,
    private val recipeId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<Recipe>>(Loadable.Loading)
    val state: StateFlow<Loadable<Recipe>> = _state

    /** Indices of ingredients / steps the user has ticked off while cooking. */
    val checkedIngredients = mutableStateListOf<Int>()
    val doneSteps = mutableStateListOf<Int>()

    /** Set after a successful delete; the screen then closes. */
    var deleted by mutableStateOf(false)
        private set
    var actionError by mutableStateOf<Throwable?>(null)

    init {
        load(refresh = false)
        viewModelScope.launch { repository.changes.drop(1).collect { if (!deleted) load(refresh = false) } }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                repository.deleteRecipe(recipeId)
                deleted = true
            } catch (e: kotlinx.coroutines.CancellationException) {
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
                _state.value = Loadable.Ready(repository.recipe(recipeId, refresh))
            } catch (e: kotlinx.coroutines.CancellationException) {
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
    }

    fun imageUrl(size: String, version: String?) = repository.imageUrl(recipeId, size, version)
}
