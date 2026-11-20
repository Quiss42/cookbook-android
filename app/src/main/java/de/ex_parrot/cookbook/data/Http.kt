package de.ex_parrot.cookbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** HTTP error with the status code, e.g. 401 for revoked app passwords. */
class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    const val USER_AGENT = "Cookbook (Android)"

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    /** Plain client. Certificate checks are only disabled if explicitly requested. */
    fun client(trustAllCerts: Boolean): OkHttpClient {
        if (!trustAllCerts) return base
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** Client that sends the app password, but only to the account's own server. */
    fun authenticated(account: Account): OkHttpClient =
        client(account.trustAllCerts).newBuilder()
            .addInterceptor(AuthInterceptor(account))
            .build()
}

private class AuthInterceptor(account: Account) : Interceptor {
    private val host = account.server.toHttpUrl().host
    private val credentials = Credentials.basic(account.loginName, account.appPassword)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != host) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", credentials)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build(),
        )
    }
}

/** Executes the request on the IO dispatcher and returns the body, or throws [HttpException]. */
suspend fun OkHttpClient.fetch(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw HttpException(response.code, "HTTP ${response.code}: ${body.take(300)}")
        }
        body
    }
}
