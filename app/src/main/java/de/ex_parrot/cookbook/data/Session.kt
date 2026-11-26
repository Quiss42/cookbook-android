package de.ex_parrot.cookbook.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okio.Path.Companion.toOkioPath

/** Everything that depends on the logged-in account. Create it off the main thread (reads files). */
class Session(private val context: Context, val account: Account) {
    private val _loginExpired = MutableStateFlow(false)
    /** True once the server refused the app password (revoked or expired). */
    val loginExpired: StateFlow<Boolean> = _loginExpired

    val client = Http.authenticated(account, onUnauthorized = { _loginExpired.value = true })
    val offlineStore = OfflineStore(context, account)
    val files = NextcloudFiles(account, client)
    val repository = CookbookRepository(CookbookApi(account, client, offlineStore), offlineStore, files)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

    /** Refreshes the offline copy as soon as Android reports a connection. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestSync()
        }
    }

    /** Removes a recipe's stored pictures from memory and disk so they are loaded again. */
    fun forgetImages(recipeId: String) {
        listOf("thumb", "full", "thumb16").forEach { size ->
            val url = repository.imageUrl(recipeId, size)
            imageLoader.memoryCache?.let { cache ->
                cache.keys.filter { it.key == url }.forEach { cache.remove(it) }
            }
            imageLoader.diskCache?.remove(url)
        }
    }

    /**
     * Uploads queued changes and updates the offline copy in the background.
     * Without [force], the offline copy is refreshed at most every 10 minutes.
     */
    @Synchronized
    fun requestSync(force: Boolean = false) {
        if (syncJob?.isActive == true) return
        val last = offlineStore.status.value.lastSync ?: 0
        if (!force && !repository.hasUploadsWaiting() && System.currentTimeMillis() - last < 10 * 60 * 1000) return
        syncJob = scope.launch {
            offlineStore.setSyncing(true)
            try {
                // Upload offline changes first, then refresh the offline copy.
                repository.uploadPending()
                if (repository.hasUploadsWaiting()) UploadWorker.schedule(context)
                repository.syncOffline { stub ->
                    // Keep small pictures for lists and covers on the phone as well.
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(repository.imageUrl(stub.id))
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Next attempt on the next start, connection or refresh.
            } finally {
                offlineStore.setSyncing(false)
            }
        }
    }

    /** Stops background work; [clearOffline] also deletes the offline copy and queued changes. */
    fun close(clearOffline: Boolean) {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        scope.coroutineContext[Job]?.cancel()
        if (clearOffline) {
            UploadWorker.cancel(context)
            offlineStore.clear()
        }
    }

    /** Revokes the app password on the server (best effort). */
    suspend fun revokeAppPassword() {
        runCatching {
            client.fetch(
                Request.Builder()
                    .url("${account.server}/ocs/v2.php/core/apppassword")
                    .delete()
                    .build(),
            )
        }
    }

    // Last, so that everything used by the callbacks is initialized.
    init {
        repository.onImagesOutdated = ::forgetImages
        repository.onChangeQueued = {
            // WorkManager uploads even if the app is closed before a connection is back.
            UploadWorker.schedule(context)
            requestSync(force = true)
        }
        repository.downloadImage = { url -> ImageTools.prepare(context, ImageTools.download(Http.client(), url)) }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
        if (repository.hasUploadsWaiting()) UploadWorker.schedule(context)
    }
}

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: Session) : SessionState
}

class SessionManager(context: Context, private val accountStore: AccountStore, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

    /** Server of an expired login, offered again on the login screen. */
    var reloginHint: Account? = null
        private set

    val state: StateFlow<SessionState> = accountStore.account
        .distinctUntilChanged()
        .map { account -> if (account == null) SessionState.LoggedOut else SessionState.LoggedIn(Session(appContext, account)) }
        // Creating a session reads files: keep it off the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    suspend fun login(account: Account) {
        accountStore.save(account)
        reloginHint = null
    }

    /** Logs out at once; the app password is revoked in the background. */
    suspend fun logout() {
        val session = (state.value as? SessionState.LoggedIn)?.session
        accountStore.clear()
        reloginHint = null
        session ?: return
        scope.launch(Dispatchers.IO) { withTimeoutOrNull(15_000) { session.revokeAppPassword() } }
        withContext(Dispatchers.IO) {
            session.close(clearOffline = true)
            session.imageLoader.diskCache?.clear()
        }
    }

    /**
     * For an expired login: back to the login screen, but the offline copy and
     * queued changes stay, so they are uploaded after logging in again.
     */
    suspend fun relogin() {
        val session = (state.value as? SessionState.LoggedIn)?.session
        reloginHint = session?.account
        accountStore.clear()
        session?.let { withContext(Dispatchers.IO) { it.close(clearOffline = false) } }
    }
}
