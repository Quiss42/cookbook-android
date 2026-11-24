package de.ex_parrot.cookbook.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import java.io.File
import java.security.MessageDigest

/** Stored state of the offline copy. */
data class OfflineStatus(
    /** Number of full recipes stored on the phone. */
    val recipeCount: Int = 0,
    /** Time of the last complete sync (epoch millis), or null if never synced. */
    val lastSync: Long? = null,
    val syncing: Boolean = false,
)

/**
 * Keeps the server's answers as files on the phone, so recipes can be read
 * without a connection. One folder per account.
 */
class OfflineStore(context: Context, account: Account) {
    private val dir = File(context.filesDir, "offline/${sha1("${account.server}|${account.loginName}")}").apply { mkdirs() }
    private val responses = File(dir, "responses").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lastSyncFile = File(dir, "last_sync")

    private val _offline = MutableStateFlow(false)
    /** True while the shown data comes from the phone because the server is unreachable. */
    val offline: StateFlow<Boolean> = _offline

    private val _status = MutableStateFlow(OfflineStatus(readIndex().size, readLastSync()))
    val status: StateFlow<OfflineStatus> = _status

    private fun fileFor(url: HttpUrl) = File(responses, sha1(url.encodedPath + "?" + url.encodedQuery.orEmpty()))

    suspend fun write(url: HttpUrl, body: String) = withContext(Dispatchers.IO) {
        val target = fileFor(url)
        val temp = File(target.path + ".tmp")
        temp.writeText(body)
        temp.renameTo(target)
        Unit
    }

    suspend fun read(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        fileFor(url).takeIf { it.exists() }?.readText()
    }

    suspend fun delete(url: HttpUrl) = withContext(Dispatchers.IO) { fileFor(url).delete(); Unit }

    fun markOnline() { _offline.value = false }
    fun markOffline() { _offline.value = true }

    /** Recipe id -> dateModified of the stored full recipes. */
    fun readIndex(): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(indexFile.readText()) }.getOrDefault(emptyMap())

    suspend fun writeIndex(index: Map<String, String>) = withContext(Dispatchers.IO) {
        indexFile.writeText(Json.encodeToString(index))
        _status.value = _status.value.copy(recipeCount = index.size)
    }

    private fun readLastSync(): Long? = runCatching { lastSyncFile.readText().trim().toLong() }.getOrNull()

    suspend fun markSynced() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        lastSyncFile.writeText(now.toString())
        _status.value = _status.value.copy(lastSync = now)
    }

    fun setSyncing(syncing: Boolean) {
        _status.value = _status.value.copy(syncing = syncing)
    }

    /** Deletes everything stored for this account (on logout). */
    fun clear() {
        dir.deleteRecursively()
    }

    private companion object {
        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
