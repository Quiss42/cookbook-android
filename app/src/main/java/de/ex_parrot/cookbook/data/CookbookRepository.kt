package de.ex_parrot.cookbook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Thin in-memory cache in front of [CookbookApi]. */
class CookbookRepository(val api: CookbookApi) {
    private var categories: List<Category>? = null
    private var allRecipes: List<RecipeStub>? = null
    private val categoryRecipes = ConcurrentHashMap<String, List<RecipeStub>>()
    private val recipes = ConcurrentHashMap<String, Recipe>()

    private val _changes = MutableStateFlow(0)
    /** Incremented whenever recipes are created, changed or deleted. */
    val changes: StateFlow<Int> = _changes

    private fun invalidate(recipeId: String? = null) {
        categories = null
        allRecipes = null
        categoryRecipes.clear()
        if (recipeId != null) recipes.remove(recipeId)
        _changes.value++
    }

    suspend fun createRecipe(recipe: JsonObject): String = api.createRecipe(recipe).also { invalidate(it) }

    suspend fun updateRecipe(id: String, recipe: JsonObject): String =
        api.updateRecipe(id, recipe).also { invalidate(id) }

    suspend fun deleteRecipe(id: String) {
        api.deleteRecipe(id)
        invalidate(id)
    }

    suspend fun importRecipe(url: String): Recipe = api.importRecipe(url).also { invalidate(it.id) }

    /** Existing keywords, most used first. */
    suspend fun keywordNames(): List<String> =
        runCatching { api.keywords() }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<Category> { it.recipeCount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it.name }

    suspend fun categoryNames(): List<String> =
        runCatching { categories() }.getOrDefault(emptyList()).filterNot { it.isUncategorized }.map { it.name }

    suspend fun categories(refresh: Boolean = false): List<Category> {
        if (!refresh) categories?.let { return it }
        if (refresh) categoryRecipes.clear()
        return api.categories()
            .sortedWith(compareBy<Category> { it.isUncategorized }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .also { categories = it }
    }

    suspend fun allRecipes(refresh: Boolean = false): List<RecipeStub> {
        if (!refresh) allRecipes?.let { return it }
        return api.allRecipes().sortedBy { it.name.lowercase() }.also { allRecipes = it }
    }

    /** Recipes of a category; pass [Category.UNCATEGORIZED_LIST_NAME] for uncategorized ones. */
    suspend fun recipesInCategory(category: String, refresh: Boolean = false): List<RecipeStub> {
        if (!refresh) categoryRecipes[category]?.let { return it }
        val queryName = if (category == Category.UNCATEGORIZED_LIST_NAME) Category.UNCATEGORIZED_QUERY_NAME else category
        return api.recipesInCategory(queryName).sortedBy { it.name.lowercase() }
            .also { categoryRecipes[category] = it }
    }

    suspend fun recipe(id: String, refresh: Boolean = false): Recipe {
        if (!refresh) recipes[id]?.let { return it }
        return api.recipe(id).also { recipes[id] = it }
    }

    suspend fun search(query: String): List<RecipeStub> = api.search(query)

    fun imageUrl(recipeId: String, size: String = "thumb", version: String? = null) = api.imageUrl(recipeId, size, version)
}
