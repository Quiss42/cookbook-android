package de.ex_parrot.cookbook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.io.InputStream

/** A problem with the content itself (not the connection): retrying will not help. */
open class ContentException(message: String) : IOException(message)

/** Thrown when a web page or file does not contain a usable image. */
class NoImageException : ContentException("No image found")

/** Thrown when a picture on the web is larger than [ImageTools.MAX_DOWNLOAD_BYTES]. */
class ImageTooLargeException : ContentException("Image too large")

/** Prepares images on the phone before they are uploaded to Nextcloud. */
object ImageTools {
    private const val MAX_SIDE = 2048
    /** Downloads from the web are cut off at this size. */
    const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
    private const val JPEG_QUALITY = 85

    /** A browser-like user agent: some sites refuse images to unknown clients. */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** Folder for camera photos and prepared images (shared via FileProvider). */
    fun imageDir(context: Context): File = File(context.cacheDir, "images_upload").apply { mkdirs() }

    /** Deletes prepared pictures and camera photos older than a day (queued photos live elsewhere). */
    fun cleanUp(context: Context, maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        val limit = System.currentTimeMillis() - maxAgeMillis
        File(context.cacheDir, "images_upload").listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    fun newCameraFile(context: Context): File = File(imageDir(context), "camera_${System.currentTimeMillis()}.jpg")

    /** Decodes, rotates and downsizes a picture from the gallery or camera into a JPEG file. */
    suspend fun prepare(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw NoImageException()
            decodeLegacy(bytes)
        }
        writeJpeg(context, bitmap)
    }

    /** Same as [prepare] for downloaded bytes. */
    suspend fun prepare(context: Context, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (w, h) = info.size.width to info.size.height
                    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                }
            }.getOrElse { throw NoImageException() }
        } else {
            decodeLegacy(bytes)
        }
        writeJpeg(context, bitmap)
    }

    private fun decodeLegacy(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw NoImageException()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw NoImageException()
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        }
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }
        return bitmap
    }

    private fun writeJpeg(context: Context, bitmap: Bitmap): File {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val file = File(imageDir(context), "prepared_${System.currentTimeMillis()}.jpg")
        file.writeBytes(out.toByteArray())
        return file
    }

    /**
     * Downloads an image on the phone. If [url] points to a web page (e.g. the
     * recipe page), the page's preview image (og:image) is used instead.
     */
    suspend fun download(client: OkHttpClient, url: String): ByteArray = withContext(Dispatchers.IO) {
        val first = get(client, url)
        if (first.second?.startsWith("text/html") == true) {
            val html = String(first.first, Charsets.UTF_8)
            val imageUrl = findPageImage(html, url) ?: throw NoImageException()
            get(client, imageUrl).first
        } else {
            first.first
        }
    }

    private fun get(client: OkHttpClient, url: String): Pair<ByteArray, String?> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "image/jpeg,image/png,image/*;q=0.8,text/html;q=0.5,*/*;q=0.3")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code}")
            val body = response.body ?: throw NoImageException()
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) throw ImageTooLargeException()
            val bytes = body.byteStream().use { readLimited(it, MAX_DOWNLOAD_BYTES) }
            return bytes to body.contentType()?.let { "${it.type}/${it.subtype}" }
        }
    }
}

/** Reads at most [limit] bytes; throws [ImageTooLargeException] if there is more. */
internal fun readLimited(input: InputStream, limit: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) throw ImageTooLargeException()
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The preview image (og:image, twitter:image) of a web page, as absolute address. */
internal fun findPageImage(html: String, pageUrl: String): String? {
    val patterns = listOf(
        Regex("""<meta[^>]+property=["']og:image(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )
    val found = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return null
    val cleaned = found.replace("&amp;", "&")
    return pageUrl.toHttpUrlOrNull()?.resolve(cleaned)?.toString() ?: cleaned
}
