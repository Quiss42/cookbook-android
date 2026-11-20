package de.ex_parrot.cookbook.data

import kotlinx.serialization.Serializable

/** A logged-in Nextcloud account using an app password. */
@Serializable
data class Account(
    val server: String,
    val loginName: String,
    val appPassword: String,
    /** Accept any TLS certificate for this server (self-signed setups). */
    val trustAllCerts: Boolean = false,
)

/** Normalizes user input like "cloud.example.com/" to "https://cloud.example.com". */
fun normalizeServerUrl(input: String): String {
    var url = input.trim().trimEnd('/')
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "https://$url"
    }
    url = url.removeSuffix("/index.php")
    return url.trimEnd('/')
}
