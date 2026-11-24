package de.ex_parrot.cookbook.data

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.Request
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

/** Everything that depends on the logged-in account. */
class Session(private val context: Context, val account: Account) {
    val client = Http.authenticated(account)
    val offlineStore = OfflineStore(context, account)
    val files = NextcloudFiles(account, client)
    val repository = CookbookRepository(CookbookApi(account, client, offlineStore), offlineStore, files)
    /** Uploads queued changes as soon as Android reports a connection. */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var syncJob: kotlinx.coroutines.Job? = null
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

    /**
     * Updates the offline copy in the background. Without [force], it runs at
     * most every 10 minutes.
     */
    fun requestSync(force: Boolean = false) {
        if (syncJob?.isActive == true) return
        val last = offlineStore.status.value.lastSync ?: 0
        val hasPending = offlineStore.pending.changes.value.any { !it.conflict }
        if (!force && !hasPending && System.currentTimeMillis() - last < 10 * 60 * 1000) return
        syncJob = scope.launch {
            offlineStore.setSyncing(true)
            try {
                // Upload offline changes first, then refresh the offline copy.
                repository.uploadPending()
                repository.syncOffline { stub ->
                    // Keep small pictures for lists and covers on the phone as well.
                    imageLoader.enqueue(
                        coil3.request.ImageRequest.Builder(context)
                            .data(repository.imageUrl(stub.id))
                            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                            .build(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Next attempt on the next start or refresh.
            } finally {
                offlineStore.setSyncing(false)
            }
        }
    }

    /** Stops background work and deletes the offline copy (on logout). */
    fun close(clearOffline: Boolean) {
        runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (clearOffline) offlineStore.clear()
    }

    // Last, so that everything used by the callbacks is initialized.
    init {
        repository.onImagesOutdated = ::forgetImages
        repository.onChangeQueued = { requestSync(force = true) }
        repository.downloadImage = { url -> ImageTools.prepare(context, ImageTools.download(Http.client(false), url)) }
        runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
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
}

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: Session) : SessionState
}

class SessionManager(context: Context, private val accountStore: AccountStore, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    val state: StateFlow<SessionState> = accountStore.account
        .distinctUntilChanged()
        .map { account -> if (account == null) SessionState.LoggedOut else SessionState.LoggedIn(Session(appContext, account)) }
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    suspend fun login(account: Account) = accountStore.save(account)

    suspend fun logout() {
        (state.value as? SessionState.LoggedIn)?.session?.let { session ->
            session.revokeAppPassword()
            session.close(clearOffline = true)
            session.imageLoader.diskCache?.clear()
        }
        accountStore.clear()
    }
}
