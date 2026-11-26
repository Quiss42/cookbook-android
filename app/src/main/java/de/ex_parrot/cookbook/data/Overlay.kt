package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.JsonObject

/*
 * Pure functions that show queued offline changes in lists, kept separate so
 * they can be unit tested.
 */

internal fun categoryOf(recipe: JsonObject?): String =
    recipe?.string("recipeCategory")?.trim().orEmpty().ifEmpty { Category.UNCATEGORIZED_LIST_NAME }

private fun matches(listName: String?, category: String) = listName == null || listName == category

/** Applies queued changes to a recipe list ([category] null = all recipes). */
internal fun overlayStubs(base: List<RecipeStub>, category: String?, changes: List<PendingChange>): List<RecipeStub> {
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
        // In "all recipes", changed recipes are already part of the list; only new ones are added.
        .filter { it.type == PendingChange.Type.CREATE || category != null }
        .forEach { result += RecipeStub(it.recipeId, it.name, it.recipe?.string("keywords").orEmpty(), null) }
    return result.sortedBy { it.name.lowercase() }
}

/** Adjusts category counts for queued changes. */
internal fun overlayCategories(base: List<Category>, changes: List<PendingChange>): List<Category> {
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

/** True if the server already has the content of [mine] (after a timed-out save). */
internal fun sameContent(mine: JsonObject?, server: JsonObject): Boolean {
    if (mine == null) return false
    val keys = listOf("name", "description", "recipeCategory", "recipeIngredient", "recipeInstructions", "tool")
    fun norm(o: JsonObject, k: String) = o[k]?.toString()?.trim().orEmpty()
    return keys.all { norm(mine, it) == norm(server, it) }
}
