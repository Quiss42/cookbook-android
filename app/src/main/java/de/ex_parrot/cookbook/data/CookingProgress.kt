package de.ex_parrot.cookbook.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Ticked-off ingredients and steps of one recipe. */
@Serializable
data class CookingProgress(
    val ingredients: Set<Int> = emptySet(),
    val steps: Set<Int> = emptySet(),
    /** Sizes of the lists when saved: if the recipe changed, the marks no longer fit. */
    val ingredientCount: Int = 0,
    val stepCount: Int = 0,
    val savedAt: Long = 0,
)

/**
 * Remembers what was ticked off per recipe, so leaving the recipe or a
 * restart of the app while cooking keeps it. Forgotten after [MAX_AGE_MILLIS].
 */
class CookingProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("cooking_progress", Context.MODE_PRIVATE)

    fun load(recipeId: String, ingredientCount: Int, stepCount: Int): CookingProgress {
        val stored = prefs.getString(recipeId, null)
            ?.let { runCatching { Json.decodeFromString<CookingProgress>(it) }.getOrNull() }
            ?: return CookingProgress()
        val fresh = System.currentTimeMillis() - stored.savedAt < MAX_AGE_MILLIS
        return if (fresh && stored.ingredientCount == ingredientCount && stored.stepCount == stepCount) {
            stored
        } else {
            CookingProgress()
        }
    }

    fun save(recipeId: String, progress: CookingProgress) {
        val editor = prefs.edit()
        if (progress.ingredients.isEmpty() && progress.steps.isEmpty()) {
            editor.remove(recipeId)
        } else {
            editor.putString(recipeId, Json.encodeToString(progress.copy(savedAt = System.currentTimeMillis())))
        }
        // Drop old entries of other recipes.
        val limit = System.currentTimeMillis() - MAX_AGE_MILLIS
        prefs.all.forEach { (key, value) ->
            val savedAt = (value as? String)?.let { runCatching { Json.decodeFromString<CookingProgress>(it).savedAt }.getOrNull() }
            if (key != recipeId && (savedAt == null || savedAt < limit)) editor.remove(key)
        }
        editor.apply()
    }

    companion object {
        const val MAX_AGE_MILLIS = 12L * 60 * 60 * 1000
    }
}
