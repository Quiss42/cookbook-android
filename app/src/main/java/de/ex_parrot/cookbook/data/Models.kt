package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration

/** Category as listed by the server. [name] "*" means "uncategorized". */
data class Category(val name: String, val recipeCount: Int) {
    val isUncategorized: Boolean get() = name == UNCATEGORIZED_LIST_NAME

    companion object {
        const val UNCATEGORIZED_LIST_NAME = "*"
        /** Name used when requesting the recipes of the uncategorized "category". */
        const val UNCATEGORIZED_QUERY_NAME = "_"
    }
}

data class RecipeStub(
    val id: String,
    val name: String,
    val keywords: String,
    val dateModified: String?,
)

/**
 * A full recipe. The raw JSON is kept so that fields this app does not know
 * about survive an edit unchanged.
 */
data class Recipe(val raw: JsonObject) {
    val id: String get() = raw.string("id")
    val name: String get() = raw.string("name")
    val description: String get() = raw.string("description")
    val url: String get() = raw.string("url")
    val category: String get() = raw.string("recipeCategory")
    val keywords: String get() = raw.string("keywords")
    val recipeYield: Int? get() = raw.int("recipeYield")
    val dateModified: String? get() = raw.string("dateModified").ifEmpty { null }
    val prepTime: Duration? get() = parseIsoDuration(raw.string("prepTime"))
    val cookTime: Duration? get() = parseIsoDuration(raw.string("cookTime"))
    val totalTime: Duration? get() = parseIsoDuration(raw.string("totalTime"))
    val tools: List<String> get() = raw.stringList("tool")
    val ingredients: List<String> get() = raw.stringList("recipeIngredient")
    val instructions: List<String> get() = raw.stringList("recipeInstructions")

    /** Nutrition values by schema.org key (e.g. "calories"), empty values removed. */
    val nutrition: List<Pair<String, String>>
        get() = (raw["nutrition"] as? JsonObject)
            ?.entries
            ?.filter { !it.key.startsWith("@") }
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .orEmpty()
}

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
}

/** Accepts a list of strings, a list of HowToStep-like objects or a single string. */
internal fun JsonObject.stringList(key: String): List<String> = when (val element = this[key]) {
    is JsonArray -> element.mapNotNull { it.textValue() }
    is JsonPrimitive -> listOfNotNull(element.contentOrNull)
    else -> emptyList()
}.map { it.trim() }.filter { it.isNotEmpty() }

private fun JsonElement.textValue(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> string("text").ifEmpty { string("name") }.ifEmpty { null }
    else -> null
}

internal fun categoryFrom(json: JsonObject) = Category(
    name = json.string("name"),
    recipeCount = json.int("recipe_count") ?: 0,
)

internal fun recipeStubFrom(json: JsonObject) = RecipeStub(
    id = json.string("recipe_id").ifEmpty { json.string("id") },
    name = json.string("name"),
    keywords = json.string("keywords"),
    dateModified = json.string("dateModified").ifEmpty { null },
)

/** Parses ISO 8601 durations like "PT1H30M"; returns null for empty or zero values. */
fun parseIsoDuration(value: String): Duration? {
    if (value.isBlank()) return null
    return runCatching { Duration.parse(value.trim()) }.getOrNull()?.takeIf { !it.isZero && !it.isNegative }
}

fun Duration.toIsoString(): String = "PT${toHours()}H${toMinutes() % 60}M"
