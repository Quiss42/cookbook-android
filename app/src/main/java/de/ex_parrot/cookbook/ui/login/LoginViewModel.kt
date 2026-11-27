package de.ex_parrot.cookbook.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.data.Account
import de.ex_parrot.cookbook.data.CertificatePins
import de.ex_parrot.cookbook.data.CookbookApi
import kotlinx.coroutines.CompletableDeferred
import de.ex_parrot.cookbook.data.Http
import de.ex_parrot.cookbook.data.LoginFlow
import de.ex_parrot.cookbook.data.SessionManager
import de.ex_parrot.cookbook.data.normalizeServerUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import de.ex_parrot.cookbook.data.HttpException
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException

sealed interface LoginStep {
    data object Idle : LoginStep
    data object Working : LoginStep
    data class WaitingForBrowser(val loginUrl: String) : LoginStep
    data class Failed(val error: Throwable) : LoginStep
    /** The server's self-signed certificate: the user compares and confirms the fingerprint. */
    data class ConfirmCertificate(val fingerprint: String) : LoginStep
    /** Logging in with another account than the expired one would drop its waiting offline changes. */
    data class ConfirmOtherAccount(val previousAccount: String, val waitingChanges: Int) : LoginStep
}

class LoginViewModel(private val sessionManager: SessionManager) : ViewModel() {
    var server by mutableStateOf("")
    var trustAllCerts by mutableStateOf(false)
    var useAppPassword by mutableStateOf(false)
    var username by mutableStateOf("")
    var appPassword by mutableStateOf("")
    var step by mutableStateOf<LoginStep>(LoginStep.Idle)
        private set

    init {
        // After an expired login: offer the same server again.
        sessionManager.reloginHint?.let {
            server = it.server
            trustAllCerts = it.trustAllCerts
        }
    }

    private val openUrlChannel = Channel<String>(Channel.BUFFERED)
    /** URLs the screen should open in the browser. */
    val openUrl = openUrlChannel.receiveAsFlow()

    private var job: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null

    /** Shows [question] and waits for [decide]. */
    private suspend fun ask(question: LoginStep): Boolean {
        val answer = CompletableDeferred<Boolean>()
        decision = answer
        step = question
        return answer.await().also { step = LoginStep.Working }
    }

    /** The user's answer to a [LoginStep.ConfirmCertificate] or [LoginStep.ConfirmOtherAccount]. */
    fun decide(accept: Boolean) {
        decision?.complete(accept)
        decision = null
    }

    val canSubmit: Boolean
        get() = server.isNotBlank() && (!useAppPassword || (username.isNotBlank() && appPassword.isNotBlank()))

    fun submit() {
        if (!canSubmit) return
        val serverUrl = normalizeServerUrl(server)
        job?.cancel()
        job = viewModelScope.launch {
            step = LoginStep.Working
            try {
                if (trustAllCerts && !confirmCertificate(serverUrl)) {
                    step = LoginStep.Idle
                    return@launch
                }
                if (useAppPassword) {
                    finish(Account(serverUrl, username.trim(), appPassword.trim(), trustAllCerts))
                } else {
                    browserLogin(serverUrl)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                step = LoginStep.Failed(e)
            }
        }
    }

    private suspend fun browserLogin(serverUrl: String) {
        val flow = LoginFlow(Http.client(serverUrl, trustAllCerts))
        val start = flow.start(serverUrl)
        step = LoginStep.WaitingForBrowser(start.login)
        openUrlChannel.send(start.login)
        // Nextcloud keeps a login flow open for 20 minutes. While the browser is
        // in front, Android may freeze this app, so single failed polls are
        // expected: keep trying and only give up on real server errors.
        val deadline = System.currentTimeMillis() + 20 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val result = try {
                flow.poll(start)
            } catch (e: IOException) {
                if (e is HttpException) throw e
                null
            }
            if (result != null) {
                step = LoginStep.Working
                finish(Account(serverUrl, result.loginName, result.appPassword, trustAllCerts))
                return
            }
        }
        step = LoginStep.Idle
    }

    /**
     * For self-signed setups: shows the certificate's fingerprint and pins it
     * once confirmed. A new login replaces an earlier (e.g. renewed) certificate.
     * Returns false if the user declined.
     */
    private suspend fun confirmCertificate(serverUrl: String): Boolean {
        val host = URI(serverUrl).host
        Http.pins.forget(host)
        val fingerprint = Http.probeCertificate(serverUrl) ?: return true // trusted by the system anyway
        if (!ask(LoginStep.ConfirmCertificate(CertificatePins.format(fingerprint)))) return false
        Http.pins.pin(host, fingerprint)
        return true
    }

    private suspend fun finish(loggedIn: Account) {
        val previous = sessionManager.reloginHint
        // Same account as the expired login (ignoring case): keep its spelling, so its offline folder is used.
        val sameAccount = previous != null &&
            previous.server.equals(loggedIn.server, ignoreCase = true) &&
            previous.loginName.equals(loggedIn.loginName, ignoreCase = true)
        val account = if (sameAccount) loggedIn.copy(server = previous.server, loginName = previous.loginName) else loggedIn
        if (previous != null && !sameAccount && sessionManager.reloginWaitingChanges > 0) {
            val name = "${previous.loginName} @ ${runCatching { URI(previous.server).host }.getOrDefault(previous.server)}"
            if (!ask(LoginStep.ConfirmOtherAccount(name, sessionManager.reloginWaitingChanges))) {
                step = LoginStep.Idle
                return
            }
        }
        val api = CookbookApi(account, Http.authenticated(account))
        // The first request after returning from the browser may hit a stale
        // connection; retry network errors a few times.
        var attempt = 0
        while (true) {
            try {
                api.checkConnection()
                break
            } catch (e: IOException) {
                if (e is HttpException || ++attempt >= 3) throw e
                delay(1_500)
            }
        }
        sessionManager.login(account)
    }

    fun reopenBrowser() {
        (step as? LoginStep.WaitingForBrowser)?.let { openUrlChannel.trySend(it.loginUrl) }
    }

    fun cancel() {
        job?.cancel()
        step = LoginStep.Idle
    }
}
