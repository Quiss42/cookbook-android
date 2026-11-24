package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Client for the Nextcloud Cookbook app's REST API (/apps/cookbook/api). */
class CookbookApi(
    account: Account,
    private val client: OkHttpClient,
    /** Optional offline copy: GET answers are stored and used when the server is unreachable. */
    private val store: OfflineStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Going through index.php lets Nextcloud handle every request directly.
    // Without it, the web server's URL rewriting must route the request, and some
    // setups reject PUT/DELETE there with "405 Method Not Allowed".
    private val base: HttpUrl = "${account.server}/index.php/apps/cookbook".toHttpUrl()

    private fun url(vararg segments: String): HttpUrl =
        base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun getJson(url: HttpUrl, cache: Boolean = true): kotlinx.serialization.json.JsonElement {
        val text = try {
            client.fetch(Request.Builder().url(url).get().build()).also { body ->
                if (cache && store != null) {
                    store.write(url, body)
                    store.markOnline()
                }
            }
        } catch (e: java.io.IOException) {
            // No connection (or the server/proxy is down): fall back to the stored answer.
            val unreachable = e !is HttpException || e.code in 502..504
            val stored = if (cache && unreachable) store?.read(url) else null
            stored?.also { store?.markOffline() } ?: throw e
        }
        return json.parseToJsonElement(text)
    }

    private suspend fun getArray(url: HttpUrl, cache: Boolean = true): List<JsonObject> =
        (getJson(url, cache) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** URL of a full recipe, also used as key of its offline copy. */
    fun recipeUrl(id: String): HttpUrl = url("api", "v1", "recipes", id)

    /** Checks the credentials and that the Cookbook app is installed. */
    suspend fun checkConnection() {
        getJson(url("api", "version"), cache = false)
    }

    suspend fun categories(): List<Category> =
        getArray(url("api", "v1", "categories")).map(::categoryFrom)

    suspend fun recipesInCategory(category: String): List<RecipeStub> =
        getArray(url("api", "v1", "category", category)).map(::recipeStubFrom)

    suspend fun allRecipes(): List<RecipeStub> =
        getArray(url("api", "v1", "recipes")).map(::recipeStubFrom)

    /** All keywords with their recipe counts (same shape as categories). */
    suspend fun keywords(): List<Category> =
        getArray(url("api", "v1", "keywords"), cache = false).map(::categoryFrom)

    suspend fun search(query: String): List<RecipeStub> =
        getArray(url("api", "v1", "search", query), cache = false).map(::recipeStubFrom)

    suspend fun recipe(id: String): Recipe =
        Recipe(getJson(recipeUrl(id)) as JsonObject)

    /** The recipe straight from the server, never the offline copy (for conflict checks). */
    suspend fun recipeFresh(id: String): Recipe =
        Recipe(getJson(recipeUrl(id), cache = false) as JsonObject)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun send(method: String, url: HttpUrl, body: JsonObject?): String {
        val requestBody = body?.toString()?.toRequestBody(jsonMediaType)
        return client.fetch(Request.Builder().url(url).method(method, requestBody).build())
    }

    /** Reads the id the server returns after saving (a bare number or a JSON value). */
    private fun parseId(response: String): String {
        val trimmed = response.trim()
        return runCatching { (json.parseToJsonElement(trimmed) as? JsonPrimitive)?.contentOrNull }.getOrNull()
            ?: trimmed.trim('"')
    }

    /** Creates a recipe and returns its new id. */
    suspend fun createRecipe(recipe: JsonObject): String =
        parseId(send("POST", url("api", "v1", "recipes"), recipe))

    /** Updates a recipe and returns its id. */
    suspend fun updateRecipe(id: String, recipe: JsonObject): String =
        parseId(send("PUT", url("api", "v1", "recipes", id), recipe)).ifEmpty { id }

    suspend fun deleteRecipe(id: String) {
        send("DELETE", url("api", "v1", "recipes", id), null)
    }

    /** Lets the server import a recipe from a web page. */
    suspend fun importRecipe(pageUrl: String): Recipe =
        Recipe(json.parseToJsonElement(send("POST", url("api", "v1", "import"), buildJsonObject { put("url", pageUrl) })) as JsonObject)

    /** Image URL; [size] is "thumb" (250 px), "thumb16" or "full". */
    /**
     * Stable image URL per recipe and size. It doubles as the key of the stored
     * picture, so changed pictures are removed explicitly (see CookbookRepository).
     */
    fun imageUrl(recipeId: String, size: String = "thumb"): String =
        url("api", "v1", "recipes", recipeId, "image").newBuilder()
            .addQueryParameter("size", size)
            .build()
            .toString()
}
