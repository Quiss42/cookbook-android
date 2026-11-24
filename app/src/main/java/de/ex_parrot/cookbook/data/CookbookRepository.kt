package de.ex_parrot.cookbook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

/** Thin in-memory cache in front of [CookbookApi]. */
class CookbookRepository(val api: CookbookApi, private val store: OfflineStore? = null) {
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

    /**
     * Brings the offline copy up to date: downloads every recipe that is new or
     * changed since the last sync (by dateModified) and forgets deleted ones.
     * Returns the current recipe list, or null if the server was unreachable.
     */
    suspend fun syncOffline(onRecipe: (RecipeStub) -> Unit = {}): List<RecipeStub>? {
        val store = store ?: return null
        val stubs = allRecipes(refresh = true)
        if (store.offline.value) return null // list came from the phone, not the server
        categories(refresh = true)
        val index = store.readIndex().toMutableMap()
        val limit = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            stubs.map { stub ->
                async {
                    limit.withPermit {
                        val modified = stub.dateModified.orEmpty()
                        if (index[stub.id] != modified || modified.isEmpty()) {
                            // Fetching stores the answer as the offline copy.
                            runCatching { recipe(stub.id, refresh = true) }.onSuccess {
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

    fun imageUrl(recipeId: String, size: String = "thumb", version: String? = null) = api.imageUrl(recipeId, size, version)
}
