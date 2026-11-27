package de.ex_parrot.cookbook.data

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** HTTP error with the status code, e.g. 401 for revoked app passwords. */
class HttpException(val code: Int, message: String) : IOException(message)

/**
 * The connection broke after the request had started to be sent, so the server
 * may have received and applied it. Counts as "not reachable" otherwise.
 */
class RequestMayHaveArrivedException(cause: IOException) : IOException(cause.message, cause)

/** Records whether a request got as far as being sent; see [SendTracker]. */
internal class SendState {
    @Volatile
    var started = false
}

/**
 * Network interceptor: runs only once a connection (including TLS) exists,
 * right before the request is written. Marks the request's [SendState].
 */
internal object SendTracker : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        chain.request().tag(SendState::class.java)?.started = true
        return chain.proceed(chain.request())
    }
}

object Http {
    const val USER_AGENT = "Cookbook (Android)"

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) return@addInterceptor chain.proceed(request)
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .addNetworkInterceptor(SendTracker)
            .build()
    }

    /** Confirmed certificates of self-signed servers; set up in [init]. */
    lateinit var pins: CertificatePins
        private set

    fun init(filesDir: File) {
        pins = CertificatePins(File(filesDir, "certificate_pins.json"))
    }

    /** Plain client with the usual certificate checks. */
    fun client(): OkHttpClient = base

    /**
     * Client for [server]. With [ownCertificate] (self-signed setups), a
     * certificate the system does not trust is accepted only if the user
     * confirmed its fingerprint at login (see [probeCertificate]). Any other
     * certificate is refused and reported to [onCertificateProblem].
     */
    fun client(server: String, ownCertificate: Boolean, onCertificateProblem: () -> Unit = {}): OkHttpClient {
        if (!ownCertificate) return base
        val host = server.toHttpUrl().host
        val trustManager = PinningTrustManager(host, pins, onCertificateProblem)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { name, session ->
                // A self-signed certificate rarely names the host; the confirmed fingerprint is what counts.
                defaultVerifier.verify(name, session) ||
                    (name == host && runCatching {
                        pins.matches(host, session.peerCertificates.first() as X509Certificate)
                    }.getOrDefault(false))
            }
            .build()
    }

    /**
     * Client that sends the app password, but only to the account's own server.
     * [onUnauthorized] is called when the server refuses it (HTTP 401).
     */
    fun authenticated(
        account: Account,
        onUnauthorized: () -> Unit = {},
        onCertificateProblem: () -> Unit = {},
    ): OkHttpClient =
        client(account.server, account.trustAllCerts, onCertificateProblem).newBuilder()
            .addInterceptor(AuthInterceptor(account, onUnauthorized))
            .build()

    /**
     * Connects to [server] and returns the SHA-256 fingerprint of its
     * certificate if the system does not trust it (for the user to confirm),
     * or null if it is trusted anyway.
     */
    suspend fun probeCertificate(server: String): String? = withContext(Dispatchers.IO) {
        val capture = CapturingTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(capture), SecureRandom())
        }
        val client = base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, capture)
            .hostnameVerifier { _, _ -> true }
            .build()
        try {
            client.newCall(Request.Builder().url("$server/status.php").head().build()).execute().close()
        } catch (e: IOException) {
            // Refused on purpose below if the certificate is not trusted; other errors count.
            if (capture.untrustedFingerprint == null) throw e
        }
        capture.untrustedFingerprint
    }
}

/** Thrown when a self-signed server presents a certificate other than the confirmed one. */
class CertificateChangedException : CertificateException("The server certificate has changed")

/** Thrown when a self-signed certificate has not been confirmed (yet). */
class UnconfirmedCertificateException : CertificateException("The server certificate has not been confirmed")

/** Host -> SHA-256 fingerprint of its confirmed self-signed certificate. */
class CertificatePins(private val file: File) {
    private var map: Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap())

    @Synchronized
    fun pinned(host: String): String? = map[host]

    @Synchronized
    fun pin(host: String, fingerprint: String) {
        map = map + (host to fingerprint)
        save()
    }

    /** Forgets the pin, e.g. on a new login, so a renewed certificate can be confirmed. */
    @Synchronized
    fun forget(host: String) {
        if (host !in map) return
        map = map - host
        save()
    }

    fun matches(host: String, certificate: X509Certificate) = pinned(host) == fingerprint(certificate)

    private fun save() {
        runCatching { file.writeTextAtomically(Json.encodeToString(map)) }
    }

    companion object {
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

        /** "ab12cd…" -> "AB:12:CD:…" for showing to the user. */
        fun format(fingerprint: String): String = fingerprint.uppercase().chunked(2).joinToString(":")
    }
}

private fun systemTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

/** Uses the system's checks; otherwise accepts only the confirmed certificate. */
// Delegates to the system first; only adds the user-confirmed fingerprint.
@SuppressLint("CustomX509TrustManager")
private class PinningTrustManager(
    private val host: String,
    private val pins: CertificatePins,
    private val onProblem: () -> Unit,
) : X509TrustManager {
    private val system = systemTrustManager()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
            return
        } catch (_: CertificateException) {
            // Not trusted by the system: check the confirmed fingerprint below.
        }
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        when (pins.pinned(host)) {
            CertificatePins.fingerprint(leaf) -> Unit
            null -> {
                onProblem()
                throw UnconfirmedCertificateException()
            }
            else -> {
                onProblem()
                throw CertificateChangedException()
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** Remembers the fingerprint of a certificate the system does not trust, and refuses it. */
// Delegates to the system first; only adds the user-confirmed fingerprint.
@SuppressLint("CustomX509TrustManager")
private class CapturingTrustManager : X509TrustManager {
    private val system = systemTrustManager()

    @Volatile
    var untrustedFingerprint: String? = null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            untrustedFingerprint = chain.firstOrNull()?.let(CertificatePins::fingerprint)
            throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

private class AuthInterceptor(account: Account, private val onUnauthorized: () -> Unit) : Interceptor {
    private val host = account.server.toHttpUrl().host
    private val credentials = Credentials.basic(account.loginName, account.appPassword)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != host) return chain.proceed(request)
        val response = chain.proceed(
            request.newBuilder()
                .header("Authorization", credentials)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build(),
        )
        if (response.code == 401) onUnauthorized()
        return response
    }
}

/**
 * Executes the request on the IO dispatcher and returns the body, or throws
 * [HttpException]. If the connection breaks after sending had started, the
 * error is wrapped in [RequestMayHaveArrivedException].
 */
suspend fun OkHttpClient.fetch(request: Request): String = withContext(Dispatchers.IO) {
    val state = SendState()
    val call = newCall(request.newBuilder().tag(SendState::class.java, state).build())
    val response = try {
        call.execute()
    } catch (e: IOException) {
        throw if (state.started) RequestMayHaveArrivedException(e) else e
    }
    response.use {
        val body = try {
            it.body.string()
        } catch (e: IOException) {
            // The answer broke off: the request itself was sent completely.
            throw RequestMayHaveArrivedException(e)
        }
        if (!it.isSuccessful) {
            throw HttpException(it.code, "HTTP ${it.code}: ${summarizeBody(body)}")
        }
        body
    }
}

/** Shortens error bodies: the <title> of HTML error pages, otherwise the start of the text. */
private fun summarizeBody(body: String): String {
    val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
    return (title ?: body).trim().take(300)
}
