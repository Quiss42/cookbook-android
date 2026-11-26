package de.ex_parrot.cookbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** HTTP error with the status code, e.g. 401 for revoked app passwords. */
class HttpException(val code: Int, message: String) : IOException(message)

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
            .build()
    }

    /** Pinned certificates of self-signed servers; set up in [init]. */
    lateinit var pins: CertificatePins
        private set

    fun init(filesDir: File) {
        pins = CertificatePins(File(filesDir, "certificate_pins.json"))
    }

    /** Plain client with the usual certificate checks. */
    fun client(): OkHttpClient = base

    /**
     * Client for [server]. With [ownCertificate] (self-signed setups), a
     * certificate the system does not trust is accepted on first use and then
     * pinned: a different certificate later is refused.
     */
    fun client(server: String, ownCertificate: Boolean): OkHttpClient {
        if (!ownCertificate) return base
        val host = server.toHttpUrl().host
        val trustManager = PinningTrustManager(host, pins)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { name, session ->
                // A self-signed certificate rarely names the host; the pin is what counts.
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
    fun authenticated(account: Account, onUnauthorized: () -> Unit = {}): OkHttpClient =
        client(account.server, account.trustAllCerts).newBuilder()
            .addInterceptor(AuthInterceptor(account, onUnauthorized))
            .build()
}

/** Thrown when a pinned self-signed server suddenly presents a different certificate. */
class CertificateChangedException : CertificateException("The server certificate has changed")

/** Host -> SHA-256 fingerprint of its accepted self-signed certificate. */
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

    /** Forgets the pin, e.g. on a new login, so a renewed certificate can be accepted. */
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
    }
}

/** Uses the system's checks; otherwise trusts the certificate on first use and pins it. */
private class PinningTrustManager(private val host: String, private val pins: CertificatePins) : X509TrustManager {
    private val system: X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
            return
        } catch (_: CertificateException) {
            // Not trusted by the system: check the pin below.
        }
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val fingerprint = CertificatePins.fingerprint(leaf)
        when (pins.pinned(host)) {
            null -> pins.pin(host, fingerprint)
            fingerprint -> Unit
            else -> throw CertificateChangedException()
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

/** Executes the request on the IO dispatcher and returns the body, or throws [HttpException]. */
suspend fun OkHttpClient.fetch(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw HttpException(response.code, "HTTP ${response.code}: ${summarizeBody(body)}")
        }
        body
    }
}

/** Shortens error bodies: the <title> of HTML error pages, otherwise the start of the text. */
private fun summarizeBody(body: String): String {
    val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
    return (title ?: body).trim().take(300)
}
