package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/** A newer release found on GitHub. */
data class UpdateInfo(val version: String, val downloadUrl: String)

/** Looks up the latest GitHub release of this app. */
object UpdateChecker {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Quiss42/cookbook-android/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the newer release, or null if [currentVersion] is up to date. Throws on network errors. */
    suspend fun check(currentVersion: String): UpdateInfo? {
        val body = Http.client(false).fetch(
            Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build(),
        )
        val release = json.parseToJsonElement(body).jsonObject
        val version = release.string("tag_name").removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val apk = (release["assets"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.string("name").endsWith(".apk") }
            ?.string("browser_download_url")
        return UpdateInfo(version, apk ?: release.string("html_url"))
    }

    /** Compares versions like "0.4.0" and "0.3.10" number by number. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
