package dev.foldman.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Decodes cover art small enough for a cassette label, from bytes, content URIs or the web. */
object Artwork {
    private const val MAX_EDGE = 640
    private val thumbs = android.util.LruCache<Uri, Bitmap>(96)

    /** Like [load], but remembers recent results for list thumbnails. */
    suspend fun thumbnail(context: Context, uri: Uri): Bitmap? =
        thumbs.get(uri) ?: load(context, uri)?.also { thumbs.put(uri, it) }

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) })
    }.getOrNull()

    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when (uri.scheme) {
                "http", "https" -> (URL(uri.toString()).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000
                    readTimeout = 5000
                    try { inputStream.use { it.readBytes() } } finally { disconnect() }
                }
                else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            bytes?.let(::decode)
        }.getOrNull()
    }

    private fun sampleSize(bounds: BitmapFactory.Options): Int {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}
