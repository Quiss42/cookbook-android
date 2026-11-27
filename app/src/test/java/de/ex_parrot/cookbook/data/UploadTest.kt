package de.ex_parrot.cookbook.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Upload of offline changes against a small fake of the Cookbook API:
 * timeouts, edits during an upload and conflicts.
 */
class UploadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cookbook = FakeCookbook()
    private val server = MockWebServer()
    private lateinit var store: OfflineStore
    private lateinit var repository: CookbookRepository

    @Before
    fun setUp() {
        server.dispatcher = cookbook
        server.start()
        store = OfflineStore(folder.newFolder("offline"))
        repository = repositoryFor(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repositoryFor(serverUrl: String): CookbookRepository {
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            .connectTimeout(1, TimeUnit.SECONDS)
            .addNetworkInterceptor(SendTracker)
            .build()
        val api = CookbookApi(Account(serverUrl, "ingo", "secret"), client, store)
        return CookbookRepository(api, store)
    }

    private fun recipe(name: String, ingredients: List<String> = listOf("Mehl"), keywords: String = "") = buildJsonObject {
        put("name", name)
        put("keywords", keywords)
        put("recipeIngredient", JsonArray(ingredients.map(::JsonPrimitive)))
    }

    private fun queue(change: PendingChange) = store.pending.update { it + change }

    private fun create(name: String, recipe: JsonObject, uncertain: Boolean = false) = PendingChange(
        id = 1, type = PendingChange.Type.CREATE, recipeId = "local-1", name = name, recipe = recipe,
        createdAt = System.currentTimeMillis(), uncertain = uncertain,
    )

    private fun update(id: String, recipe: JsonObject, base: String?, uncertain: Boolean = false) = PendingChange(
        id = 2, type = PendingChange.Type.UPDATE, recipeId = id, name = recipe["name"].toString().trim('"'),
        recipe = recipe, baseModified = base, createdAt = System.currentTimeMillis(), uncertain = uncertain,
    )

    @Test
    fun offlineRecipe_withOlderRecipeOfSameName_isUploadedAsNewRecipe() = runBlocking {
        // An older "Pizza" with even the same content must not be mistaken for the new one.
        cookbook.add("1", recipe("Pizza"), created = "2020-01-01T10:00:00+0000")
        queue(create("Pizza", recipe("Pizza"), uncertain = true))

        repository.uploadPending()

        assertEquals(1, cookbook.count("POST /recipes"))
        assertEquals(2, cookbook.recipes.size)
        assertTrue(store.pending.changes.value.isEmpty())
        assertEquals("100", store.readIdMap()["local-1"])
    }

    @Test
    fun timedOutCreate_thatArrived_isNotCreatedTwice() = runBlocking {
        queue(create("Pizza", recipe("Pizza")))
        cookbook.timeoutAfterNextWrite = true

        repository.uploadPending()
        // The server saved it, but the answer never came: the change stays, marked as uncertain.
        assertTrue(store.pending.changes.value.single().uncertain)

        repository.uploadPending()

        assertEquals(1, cookbook.count("POST /recipes"))
        assertEquals(1, cookbook.recipes.size)
        assertTrue(store.pending.changes.value.isEmpty())
        assertEquals("100", store.readIdMap()["local-1"])
    }

    @Test
    fun connectionFailure_isNotUncertain() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/").toString().trimEnd('/')
        closed.shutdown()
        val offlineRepository = repositoryFor(url)
        queue(create("Pizza", recipe("Pizza")))

        offlineRepository.uploadPending()

        // Nothing was sent, so a later attempt must simply create the recipe.
        assertFalse(store.pending.changes.value.single().uncertain)
    }

    @Test
    fun uncertainUpdate_whenServerUnchanged_isUploaded() = runBlocking {
        // Only the keywords changed; the earlier attempt did not reach the server.
        val modified = cookbook.add("7", recipe("Brot"))
        queue(update("7", recipe("Brot", keywords = "Frühstück"), base = modified, uncertain = true))

        repository.uploadPending()

        assertEquals(1, cookbook.count("PUT /recipes/7"))
        assertEquals("Frühstück", cookbook.recipes.getValue("7").string("keywords"))
        assertTrue(store.pending.changes.value.isEmpty())
    }

    @Test
    fun timedOutUpdate_thatArrived_isNeitherRepeatedNorAConflict() = runBlocking {
        val modified = cookbook.add("7", recipe("Brot"))
        queue(update("7", recipe("Brot", keywords = "Frühstück"), base = modified))
        cookbook.timeoutAfterNextWrite = true

        repository.uploadPending()
        assertTrue(store.pending.changes.value.single().uncertain)

        repository.uploadPending()

        assertEquals(1, cookbook.count("PUT /recipes/7"))
        assertTrue(store.pending.changes.value.isEmpty())
    }

    @Test
    fun uncertainUpdate_withOtherServerChanges_becomesConflict() = runBlocking {
        val modified = cookbook.add("7", recipe("Brot"))
        queue(update("7", recipe("Brot", keywords = "Frühstück"), base = modified, uncertain = true))
        // Someone else changed the recipe in the web interface meanwhile.
        cookbook.put("7", recipe("Brot", ingredients = listOf("Roggenmehl")))

        repository.uploadPending()

        // Not overwritten: the user decides.
        assertEquals(0, cookbook.count("PUT /recipes/7"))
        assertTrue(store.pending.changes.value.single().conflict)
    }

    @Test
    fun serverChangedMeanwhile_becomesConflict() = runBlocking {
        cookbook.add("7", recipe("Brot"))
        queue(update("7", recipe("Brot", keywords = "Frühstück"), base = "2020-01-01T00:00:00+0000"))

        repository.uploadPending()

        assertEquals(0, cookbook.count("PUT /recipes/7"))
        assertTrue(store.pending.changes.value.single().conflict)
    }

    @Test
    fun editDuringCreateUpload_isKeptAndUploadedAsUpdate() = runBlocking {
        queue(create("Pizza", recipe("Pizza")))
        cookbook.onWrite = {
            // The user saves the recipe again while it is being uploaded.
            store.pending.update { list ->
                list.map { it.copy(name = "Pizza Margherita", recipe = recipe("Pizza Margherita")) }
            }
        }

        repository.uploadPending()
        cookbook.onWrite = null

        val remaining = store.pending.changes.value.single()
        assertEquals(PendingChange.Type.UPDATE, remaining.type)
        assertEquals("100", remaining.recipeId)
        assertEquals(cookbook.recipes.getValue("100").string("dateModified"), remaining.baseModified)

        repository.uploadPending()

        assertEquals("Pizza Margherita", cookbook.recipes.getValue("100").string("name"))
        assertEquals(1, cookbook.count("POST /recipes"))
        assertTrue(store.pending.changes.value.isEmpty())
    }
}

/** In-memory Cookbook API: /api/v1/recipes with GET, POST, PUT and DELETE. */
private class FakeCookbook : Dispatcher() {
    val recipes = linkedMapOf<String, JsonObject>()
    private val requests = mutableListOf<String>()
    private var nextId = 100
    private var tick = 0L

    /** Apply the next write, then let the client time out before answering. */
    @Volatile
    var timeoutAfterNextWrite = false

    /** Called while a write is being handled (before the answer is sent). */
    @Volatile
    var onWrite: (() -> Unit)? = null

    @Synchronized
    fun count(request: String) = requests.count { it == request }

    @Synchronized
    private fun stamp(): String = Instant.now().plusSeconds(tick++).toString()

    /** Adds a recipe as if created earlier; returns its dateModified. */
    @Synchronized
    fun add(id: String, recipe: JsonObject, created: String = stamp()): String {
        val modified = stamp()
        recipes[id] = JsonObject(recipe + mapOf(
            "id" to JsonPrimitive(id),
            "dateCreated" to JsonPrimitive(created),
            "dateModified" to JsonPrimitive(modified),
        ))
        return modified
    }

    /** Changes a recipe directly on the server (e.g. in the web interface). */
    @Synchronized
    fun put(id: String, recipe: JsonObject) {
        val created = recipes.getValue(id).string("dateCreated")
        add(id, recipe, created)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringAfter("/api/v1").substringBefore('?')
        val method = request.method.orEmpty()
        synchronized(this) { requests += "$method $path" }
        val id = path.removePrefix("/recipes/").takeIf { path.startsWith("/recipes/") }
        val body = request.body.readUtf8().takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as JsonObject }
        return when {
            method == "GET" && path == "/recipes" -> synchronized(this) {
                json(JsonArray(recipes.values.map { r ->
                    buildJsonObject {
                        put("recipe_id", r.string("id"))
                        put("name", r.string("name"))
                        put("dateModified", r.string("dateModified"))
                    }
                }))
            }
            method == "GET" && id != null -> synchronized(this) { recipes[id] }?.let(::json) ?: MockResponse().setResponseCode(404)
            method == "POST" && path == "/recipes" -> {
                val newId = synchronized(this) { (nextId++).toString().also { add(it, body!!) } }
                written(newId)
            }
            method == "PUT" && id != null -> {
                synchronized(this) { put(id, body!!) }
                written(id)
            }
            method == "DELETE" && id != null -> {
                synchronized(this) { recipes.remove(id) }
                written(id)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun written(id: String): MockResponse {
        onWrite?.invoke()
        if (timeoutAfterNextWrite) {
            timeoutAfterNextWrite = false
            Thread.sleep(2_000) // longer than the client's read timeout
        }
        return MockResponse().setBody(id)
    }

    private fun json(element: kotlinx.serialization.json.JsonElement) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(element.toString())
}
