package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Client for the Nextcloud Cookbook app's REST API (/apps/cookbook/api). */
class CookbookApi(account: Account, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base: HttpUrl = "${account.server}/apps/cookbook".toHttpUrl()

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

    /** Image URL; [size] is "thumb" (250 px), "thumb16" or "full". */
    fun imageUrl(recipeId: String, size: String = "thumb"): String =
        url("api", "v1", "recipes", recipeId, "image").newBuilder()
            .addQueryParameter("size", size)
            .build()
            .toString()
}
