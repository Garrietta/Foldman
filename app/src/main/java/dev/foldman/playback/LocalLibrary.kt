package dev.foldman.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dev.foldman.deck.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Walks a folder picked with the system file picker and finds the music (and cover art) in it. */
class LocalLibrary(private val context: Context) {

    /** Every audio file under [tree], album folders in order, tracks in natural order within them. */
    suspend fun scan(tree: Uri, onProgress: (Int) -> Unit = {}): List<LocalTrack> = withContext(Dispatchers.IO) {
        val found = mutableListOf<LocalTrack>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walk(tree, rootId, displayName(tree, rootId) ?: "Music", found, onProgress)
        found.sortedWith(compareBy<LocalTrack, String>(NaturalOrder) { it.folder }.thenBy(NaturalOrder) { it.uri.lastPathSegment ?: "" })
    }

    /** Fills in tag titles and artists, which file names only approximate. Slow, so it runs after the scan. */
    suspend fun readTags(tracks: List<LocalTrack>, limit: Int = 600): List<LocalTrack> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            tracks.mapIndexed { i, track ->
                coroutineContext.ensureActive()
                if (i >= limit) return@mapIndexed track
                runCatching {
                    retriever.setDataSource(context, track.uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    track.copy(
                        title = title?.takeIf { it.isNotBlank() } ?: track.title,
                        artist = artist?.takeIf { it.isNotBlank() } ?: track.artist,
                    )
                }.getOrDefault(track)
            }
        } finally {
            retriever.release()
        }
    }

    private suspend fun walk(tree: Uri, docId: String, folder: String, out: MutableList<LocalTrack>, onProgress: (Int) -> Unit) {
        coroutineContext.ensureActive()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = mutableListOf<Pair<String, String>>()
        val audio = mutableListOf<Pair<String, String>>()
        var cover: Uri? = null
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (!name.startsWith(".")) dirs += id to name
                    mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> audio += id to name
                    mime.startsWith("image/") && name.substringBeforeLast('.').lowercase() in COVER_NAMES ->
                        cover = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        for ((id, name) in audio) {
            out += LocalTrack(
                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                title = titleFromFileName(name),
                artist = null,
                folder = folder,
                coverUri = cover,
            )
        }
        if (audio.isNotEmpty()) onProgress(out.size)
        for ((id, name) in dirs) walk(tree, id, name, out, onProgress)
    }

    private fun displayName(tree: Uri, docId: String): String? = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "aiff", "aif", "mka")
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
        private val TRACK_NUMBER = Regex("""^\s*(\d{1,3}|[A-D]\d{1,2})\s*[-._)\s]\s*""")

        /** "03 - Blue Monday.flac" → "Blue Monday". */
        fun titleFromFileName(name: String): String {
            val stem = name.substringBeforeLast('.')
            return stem.replace(TRACK_NUMBER, "").replace('_', ' ').trim().ifEmpty { stem }
        }
    }
}

/** Orders "2 …" before "10 …", as people number their files. */
private object NaturalOrder : Comparator<String> {
    private val chunks = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val cmp = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (cmp != 0) return cmp
        }
        return x.size.compareTo(y.size)
    }
}
