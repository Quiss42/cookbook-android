package de.ex_parrot.cookbook.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.Category
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.RecipeStub
import de.ex_parrot.cookbook.ui.components.Loadable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException

/** Pseudo category name for "all recipes". */
const val ALL_RECIPES = "@all@"

data class CategoryOverview(
    val categories: List<Category>,
    val totalCount: Int,
)

class CategoriesViewModel(private val repository: CookbookRepository) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<CategoryOverview>>(Loadable.Loading)
    val state: StateFlow<Loadable<CategoryOverview>> = _state

    /** Recipe whose photo represents each category (null = no recipes / not loaded). */
    private val _coverIds = MutableStateFlow<Map<String, RecipeStub?>>(emptyMap())
    val coverIds: StateFlow<Map<String, RecipeStub?>> = _coverIds

    init {
        load(refresh = false)
        viewModelScope.launch { repository.changes.drop(1).collect { load(refresh = false) } }
    }

    fun load(refresh: Boolean = true) {
        viewModelScope.launch {
            val current = _state.value
            _state.value = if (current is Loadable.Ready && refresh) current.copy(refreshing = true) else
                if (current is Loadable.Ready) current else Loadable.Loading
            try {
                val categories = repository.categories(refresh)
                _state.value = Loadable.Ready(CategoryOverview(categories, categories.sumOf { it.recipeCount }))
                loadCovers(categories, refresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = if (current is Loadable.Ready) current.copy(refreshing = false) else Loadable.Failed(e)
            }
        }
    }

    private suspend fun loadCovers(categories: List<Category>, refresh: Boolean) {
        val limit = Semaphore(4)
        val names = listOf(ALL_RECIPES) + categories.map { it.name }
        names.map { name ->
            viewModelScope.async {
                limit.withPermit {
                    val first = runCatching {
                        if (name == ALL_RECIPES) repository.allRecipes(refresh) else repository.recipesInCategory(name, refresh)
                    }.getOrNull()?.firstOrNull()
                    _coverIds.update { it + (name to first) }
                }
            }
        }.awaitAll()
    }
}

class RecipeListViewModel(
    private val repository: CookbookRepository,
    private val category: String,
) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<List<RecipeStub>>>(Loadable.Loading)
    val state: StateFlow<Loadable<List<RecipeStub>>> = _state

    init {
        load(refresh = false)
        viewModelScope.launch { repository.changes.drop(1).collect { load(refresh = false) } }
    }

    fun load(refresh: Boolean = true) {
        viewModelScope.launch {
            val current = _state.value
            if (current is Loadable.Ready) _state.value = current.copy(refreshing = refresh) else _state.value = Loadable.Loading
            try {
                val recipes = if (category == ALL_RECIPES) repository.allRecipes(refresh)
                else repository.recipesInCategory(category, refresh)
                _state.value = Loadable.Ready(recipes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = if (current is Loadable.Ready) current.copy(refreshing = false) else Loadable.Failed(e)
            }
        }
    }
}
