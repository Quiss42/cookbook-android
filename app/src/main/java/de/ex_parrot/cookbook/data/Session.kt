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
import okio.Path.Companion.toOkioPath

/** Everything that depends on the logged-in account. */
class Session(context: Context, val account: Account) {
    val client = Http.authenticated(account)
    val repository = CookbookRepository(CookbookApi(account, client))
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

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
        (state.value as? SessionState.LoggedIn)?.session?.revokeAppPassword()
        accountStore.clear()
    }
}
