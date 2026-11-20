package de.ex_parrot.cookbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class LoginFlowStart(val poll: Poll, val login: String) {
    @Serializable
    data class Poll(val token: String, val endpoint: String)
}

@Serializable
data class LoginFlowResult(val server: String, val loginName: String, val appPassword: String)

/** Nextcloud Login Flow v2: the user logs in in the browser, the app polls for an app password. */
class LoginFlow(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(server: String): LoginFlowStart {
        val request = Request.Builder()
            .url("$server/index.php/login/v2")
            .post(ByteArray(0).toRequestBody())
            .build()
        return json.decodeFromString(client.fetch(request))
    }

    /** Returns the result once the user has granted access, or null while still waiting. */
    suspend fun poll(start: LoginFlowStart): LoginFlowResult? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(start.poll.endpoint)
            .post(FormBody.Builder().add("token", start.poll.token).build())
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> json.decodeFromString<LoginFlowResult>(response.body!!.string())
                response.code == 404 -> null
                else -> throw HttpException(response.code, "Login polling failed: HTTP ${response.code}")
            }
        }
    }
}
