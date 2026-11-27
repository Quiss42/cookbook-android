package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

/** Edited fields compared after a timed-out save; the image path is rewritten by the server. */
private val COMPARED_KEYS = EDITED_RECIPE_KEYS - "image"

private fun normalized(element: JsonElement?): String = when (element) {
    null, JsonNull -> ""
    is JsonPrimitive -> element.content.trim()
    is JsonArray -> element.joinToString("\u0000") { normalized(it) }
    is JsonObject -> element.entries
        .filter { !it.key.startsWith("@") && normalized(it.value).isNotEmpty() }
        .sortedBy { it.key }
        .joinToString("\u0000") { "${it.key}=${normalized(it.value)}" }
}

/**
 * True if the server already has the content of [mine] (after a timed-out
 * save): every edited field (see [EDITED_RECIPE_KEYS]) except the image matches.
 */
internal fun sameContent(mine: JsonObject?, server: JsonObject): Boolean {
    if (mine == null) return false
    return COMPARED_KEYS.all { normalized(mine[it]) == normalized(server[it]) }
}

/** Reads the server's timestamps ("2026-11-27T10:15:00+0000" and similar) as epoch millis. */
internal fun parseServerTime(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching { return OffsetDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")).toInstant().toEpochMilli() }
    runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }
    return text.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
}
