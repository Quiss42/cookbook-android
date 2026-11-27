package de.ex_parrot.cookbook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** Prefix of ids for recipes created offline and not yet uploaded. */
const val LOCAL_RECIPE_PREFIX = "local-"

/**
 * The server answered with something that is not the expected JSON, e.g. the
 * login page of a hotel Wi-Fi. Treated like "not reachable".
 */
class InvalidResponseException(message: String) : IOException(message)

/** True for errors meaning "server not reachable" (no network, proxy/server down, captive portal). */
fun isUnreachable(e: Throwable): Boolean =
    e is IOException && e !is ContentException && !isCertificateProblem(e) &&
        (e !is HttpException || e.code in 502..504)

/** The connection worked, but the certificate was refused: retrying will not help. */
private fun isCertificateProblem(e: Throwable): Boolean =
    e is SSLPeerUnverifiedException ||
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.any { it is CertificateException }

/**
 * True if a request may have reached the server although no answer came back:
 * the connection was established and sending had started (see [fetch]).
 * Failures while connecting (DNS, connect timeout, TLS) do not count.
 */
fun mayHaveReachedServer(e: Throwable): Boolean = e is RequestMayHaveArrivedException

/** A change made without connection, waiting to be uploaded. */
@Serializable
data class PendingChange(
    val id: Long,
    val type: Type,
    /** Server id, or a [LOCAL_RECIPE_PREFIX] id for recipes created offline. */
    val recipeId: String,
    val name: String,
    /** Full recipe JSON for CREATE and UPDATE. */
    val recipe: JsonObject? = null,
    /** Photo from camera/gallery to upload with the recipe. */
    val imageFile: String? = null,
    /** Web address of a photo the phone downloads when uploading (chosen while offline). */
    val imageWebUrl: String? = null,
    /** dateModified of the recipe when editing started (conflict check). */
    val baseModified: String? = null,
    /** Category before the change (for adjusting category counts). */
    val fromCategory: String? = null,
    /** The recipe was also changed on the server; the user has to decide. */
    val conflict: Boolean = false,
    /** Last upload error other than "not reachable". */
    val error: String? = null,
    /** When the change was made (epoch millis). */
    val createdAt: Long = 0,
    /**
     * An earlier attempt timed out after sending: the server may already have
     * applied it. Checked before uploading again to avoid duplicates.
     */
    val uncertain: Boolean = false,
) {
    @Serializable
    enum class Type { CREATE, UPDATE, DELETE }
}

/**
 * Persisted queue of [PendingChange]s, in the order they were made.
 * [isClosed] turns writes into no-ops after the account was logged out.
 */
class PendingStore(
    private val file: File,
    private val imageDir: File,
    private val isClosed: () -> Boolean = { false },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _changes = MutableStateFlow(load())
    val changes: StateFlow<List<PendingChange>> = _changes

    private fun load(): List<PendingChange> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<PendingChange>>(file.readText())
        } catch (e: Exception) {
            // Damaged: keep it for inspection instead of overwriting it with the next change.
            file.keepAsBad()
            emptyList()
        }
    }

    @Synchronized
    fun update(block: (List<PendingChange>) -> List<PendingChange>) {
        if (isClosed()) return
        val updated = block(_changes.value)
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(updated))
        _changes.value = updated
    }

    /**
     * Removes [change] only if the queued entry is still exactly as it was
     * uploaded. Returns false if it was changed meanwhile (e.g. edited again
     * during the upload); then it stays queued.
     */
    @Synchronized
    fun removeIfUnchanged(change: PendingChange): Boolean {
        val current = _changes.value.firstOrNull { it.id == change.id } ?: return true
        if (current != change) return false
        update { list -> list.filterNot { it.id == change.id } }
        return true
    }

    fun current(change: PendingChange): PendingChange? = _changes.value.firstOrNull { it.id == change.id }

    fun forRecipe(recipeId: String): PendingChange? = _changes.value.firstOrNull { it.recipeId == recipeId }

    fun remove(change: PendingChange, deleteImage: Boolean = true) {
        update { list -> list.filterNot { it.id == change.id } }
        if (deleteImage) change.imageFile?.let { File(it).delete() }
    }

    /** Copies a prepared photo to permanent storage (the cache may be cleared). */
    fun keepImage(source: File): String {
        check(!isClosed()) { "Logged out" }
        imageDir.mkdirs()
        val target = File(imageDir, "pending_${System.currentTimeMillis()}.jpg")
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun nextId(): Long = System.nanoTime()
}
