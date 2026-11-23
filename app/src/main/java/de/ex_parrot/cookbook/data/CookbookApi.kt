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
class CookbookApi(account: Account, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Going through index.php lets Nextcloud handle every request directly.
    // Without it, the web server's URL rewriting must route the request, and some
    // setups reject PUT/DELETE there with "405 Method Not Allowed".
    private val base: HttpUrl = "${account.server}/index.php/apps/cookbook".toHttpUrl()

    private fun url(vararg segments: String): HttpUrl =
        base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun getJson(url: HttpUrl) =
        json.parseToJsonElement(client.fetch(Request.Builder().url(url).get().build()))

    private suspend fun getArray(url: HttpUrl): List<JsonObject> =
        (getJson(url) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** Checks the credentials and that the Cookbook app is installed. */
    suspend fun checkConnection() {
        getJson(url("api", "version"))
    }

    suspend fun categories(): List<Category> =
        getArray(url("api", "v1", "categories")).map(::categoryFrom)

    suspend fun recipesInCategory(category: String): List<RecipeStub> =
        getArray(url("api", "v1", "category", category)).map(::recipeStubFrom)

    suspend fun allRecipes(): List<RecipeStub> =
        getArray(url("api", "v1", "recipes")).map(::recipeStubFrom)

    suspend fun search(query: String): List<RecipeStub> =
        getArray(url("api", "v1", "search", query)).map(::recipeStubFrom)

    suspend fun recipe(id: String): Recipe =
        Recipe(getJson(url("api", "v1", "recipes", id)) as JsonObject)

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
    /** [version] (e.g. dateModified) only changes the URL so cached images are refreshed. */
    fun imageUrl(recipeId: String, size: String = "thumb", version: String? = null): String =
        url("api", "v1", "recipes", recipeId, "image").newBuilder()
            .addQueryParameter("size", size)
            .apply { if (!version.isNullOrBlank()) addQueryParameter("v", version.hashCode().toUInt().toString(16)) }
            .build()
            .toString()
}
