package de.ex_parrot.cookbook.data

import java.util.concurrent.ConcurrentHashMap

/** Thin in-memory cache in front of [CookbookApi]. */
class CookbookRepository(val api: CookbookApi) {
    private var categories: List<Category>? = null
    private var allRecipes: List<RecipeStub>? = null
    private val categoryRecipes = ConcurrentHashMap<String, List<RecipeStub>>()
    private val recipes = ConcurrentHashMap<String, Recipe>()

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

    fun imageUrl(recipeId: String, size: String = "thumb") = api.imageUrl(recipeId, size)
}
