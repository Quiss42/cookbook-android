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
class OfflineStore(private val dir: File) {
    constructor(context: Context, account: Account) : this(dirFor(context, account))

    /** Set on logout: from then on nothing is written, so a running upload can't bring the folder back. */
    @Volatile
    var closed = false
        private set

    private val responses = File(dir, "responses").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lastSyncFile = File(dir, "last_sync")
    private val idMapFile = File(dir, "uploaded_ids.json")

    /** Changes made offline, waiting for upload. */
    val pending = PendingStore(File(dir, "pending.json"), File(dir, "pending_images")) { closed }

    private val _offline = MutableStateFlow(false)
    /** True while the shown data comes from the phone because the server is unreachable. */
    val offline: StateFlow<Boolean> = _offline

    private val _status = MutableStateFlow(OfflineStatus(readIndex().size, readLastSync()))
    val status: StateFlow<OfflineStatus> = _status

    private fun fileFor(url: HttpUrl) = File(responses, sha1(url.encodedPath + "?" + url.encodedQuery.orEmpty()))

    suspend fun write(url: HttpUrl, body: String) = withContext(Dispatchers.IO) {
        if (!closed) fileFor(url).writeTextAtomically(body)
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
        if (closed) return@withContext
        indexFile.writeTextAtomically(Json.encodeToString(index))
        _status.value = _status.value.copy(recipeCount = index.size)
    }

    /** Offline ids of uploaded recipes -> server ids, so old links keep working after a restart. */
    fun readIdMap(): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(idMapFile.readText()) }.getOrDefault(emptyMap())

    fun writeIdMap(map: Map<String, String>) {
        if (closed) return
        idMapFile.writeTextAtomically(Json.encodeToString(map))
    }

    private fun readLastSync(): Long? = runCatching { lastSyncFile.readText().trim().toLong() }.getOrNull()

    suspend fun markSynced() = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        val now = System.currentTimeMillis()
        lastSyncFile.writeTextAtomically(now.toString())
        _status.value = _status.value.copy(lastSync = now)
    }

    fun setSyncing(syncing: Boolean) {
        _status.value = _status.value.copy(syncing = syncing)
    }

    /** Deletes everything stored for this account (on logout). */
    fun clear() {
        closed = true
        // Same lock as PendingStore.update, so no queue write can recreate the folder meanwhile.
        synchronized(pending) { dir.deleteRecursively() }
    }

    companion object {
        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun root(context: Context) = File(context.filesDir, "offline")

        fun dirFor(context: Context, account: Account) = File(root(context), sha1("${account.server}|${account.loginName}"))

        /** Number of changes still waiting in [account]'s folder (without opening a session). */
        fun pendingCount(context: Context, account: Account): Int =
            PendingStore(File(dirFor(context, account), "pending.json"), File(dirFor(context, account), "pending_images")) { true }
                .changes.value.size

        /** Deletes the offline folders of all other accounts, e.g. after logging in with a different one. */
        fun removeOtherAccounts(context: Context, keep: Account) {
            val keepDir = dirFor(context, keep)
            root(context).listFiles()?.filter { it.isDirectory && it != keepDir }?.forEach { it.deleteRecursively() }
        }
    }
}
