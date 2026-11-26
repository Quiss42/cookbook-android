package de.ex_parrot.cookbook.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder

/** A folder or image in the user's Nextcloud files. [path] is relative to the user's root, e.g. "/Photos/a.jpg". */
data class CloudEntry(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val contentType: String,
    val fileId: String?,
) {
    val isImage: Boolean get() = contentType.startsWith("image/")
}

/** Minimal WebDAV access to the user's Nextcloud files. */
class NextcloudFiles(private val account: Account, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedUserId: String? = null

    /** The internal user id used in WebDAV paths (may differ from the login name). */
    suspend fun userId(): String {
        cachedUserId?.let { return it }
        val body = client.fetch(
            Request.Builder().url("${account.server}/ocs/v2.php/cloud/user?format=json").get().build(),
        )
        val id = runCatching {
            json.parseToJsonElement(body).jsonObject["ocs"]!!.jsonObject["data"]!!.jsonObject.string("id")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: account.loginName
        cachedUserId = id
        return id
    }

    private suspend fun davUrl(path: String): HttpUrl {
        val builder = account.server.toHttpUrl().newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(userId())
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /** Lists folders and images in [path], folders first. */
    suspend fun list(path: String): List<CloudEntry> {
        val url = davUrl(path)
        val body = """<?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:prop><d:resourcetype/><d:getcontenttype/><oc:fileid/></d:prop>
            </d:propfind>""".trimIndent()
        val response = client.fetch(
            Request.Builder()
                .url(url)
                .header("Depth", "1")
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .build(),
        )
        val prefix = "/remote.php/dav/files/${userId()}"
        return withContext(Dispatchers.Default) { parseMultistatus(response, prefix) }
            .filter { it.path.trimEnd('/') != path.trimEnd('/').ifEmpty { "" } && (it.isFolder || it.isImage) }
            .sortedWith(compareBy<CloudEntry> { !it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    internal fun parseMultistatus(xml: String, prefix: String): List<CloudEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        val entries = mutableListOf<CloudEntry>()
        var href = ""
        var isFolder = false
        var contentType = ""
        var fileId: String? = null
        var text = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = ""; isFolder = false; contentType = ""; fileId = null }
                        "collection" -> isFolder = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontenttype" -> contentType = text.toString().trim()
                    "fileid" -> fileId = text.toString().trim().ifEmpty { null }
                    "response" -> {
                        val decoded = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
                        val index = decoded.indexOf(prefix)
                        if (index >= 0) {
                            val path = decoded.substring(index + prefix.length).trimEnd('/').ifEmpty { "/" }
                            val name = path.substringAfterLast('/')
                            entries += CloudEntry(path, name, isFolder, contentType, fileId)
                        }
                    }
                }
            }
            parser.next()
        }
        return entries
    }

    /** Server-rendered thumbnail of a file. */
    fun previewUrl(fileId: String, size: Int = 256): String =
        account.server.toHttpUrl().newBuilder()
            .addPathSegments("index.php/core/preview")
            .addQueryParameter("fileId", fileId)
            .addQueryParameter("x", size.toString())
            .addQueryParameter("y", size.toString())
            .addQueryParameter("a", "1")
            .build()
            .toString()

    suspend fun upload(path: String, bytes: ByteArray, contentType: String) {
        client.fetch(
            Request.Builder().url(davUrl(path)).put(bytes.toRequestBody(contentType.toMediaType())).build(),
        )
    }

    suspend fun delete(path: String) {
        client.fetch(Request.Builder().url(davUrl(path)).delete().build())
    }
}
