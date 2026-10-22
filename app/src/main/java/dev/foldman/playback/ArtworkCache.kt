package dev.foldman.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Two-level cache for now-playing artwork, keyed by a stable string derived from the
 * artwork URI (falling back to the image bytes' hash when there is no URI).
 *
 * The memory LRU serves revisits instantly; the disk store under filesDir survives
 * restarts, so a track's cover is available the moment it starts playing — the
 * cassette label never flashes the default background while art loads.
 *
 * Keys are URI-first on purpose: the prefetch pass only ever sees queue-entry URIs,
 * so the key it warms is exactly the key the backends' updateArtwork asks for.
 */
object ArtworkCache {
    private const val MEM_ENTRIES = 16
    private const val MAX_DISK_BYTES = 48L * 1024 * 1024
    private const val DIR_NAME = "artwork_cache"

    private val mem = LruCache<String, Bitmap>(MEM_ENTRIES)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable cache key for the raw artwork keys the backends use (Uri or Int hash). */
    fun stableKey(raw: Any?): String? = when (raw) {
        null -> null
        is Uri -> "u:$raw"
        is Int -> "h:$raw"
        else -> null
    }

    /** Memory first, then disk (promoted back into memory on a hit). */
    fun get(context: Context, key: String): Bitmap? {
        mem.get(key)?.let { return it }
        val file = fileFor(context, key)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.also {
                mem.put(key, it)
                file.setLastModified(System.currentTimeMillis())
            }
    }

    /** Stores in memory immediately; the disk write happens in the background. */
    fun put(context: Context, key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        mem.put(key, bitmap)
        ioScope.launch {
            runCatching {
                val dir = dirFor(context).apply { mkdirs() }
                val dst = File(dir, fileName(key))
                val tmp = File(dir, dst.name + ".tmp")
                tmp.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                tmp.renameTo(dst)
                evictIfNeeded(dir)
            }
        }
    }

    private fun dirFor(context: Context) = File(context.filesDir, DIR_NAME)

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".png"
    }

    private fun fileFor(context: Context, key: String) = File(dirFor(context), fileName(key))

    private fun evictIfNeeded(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_DISK_BYTES) return
            total -= f.length()
            f.delete()
        }
    }
}
