package dev.foldman.deck

import android.content.Context
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Serializable snapshot of a YouTube Music playlist's identity (not its tracks).
 * [PlaylistDisplayItem] derives its id back out of [url], so the url round-trips it.
 */
@Serializable
data class CachedPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val uploaderName: String,
    val itemCount: Int = -1,
    val thumbnailUrl: String? = null,
) {
    fun toDisplayItem() = PlaylistDisplayItem(
        name = name,
        url = url,
        uploaderName = uploaderName,
        itemCount = itemCount,
        thumbnailUrl = thumbnailUrl,
    )
}

private fun PlaylistDisplayItem.toCached() = CachedPlaylist(
    id = id,
    name = name,
    url = url,
    uploaderName = uploaderName,
    itemCount = itemCount,
    thumbnailUrl = thumbnailUrl,
)

/** The whole YouTube Music library list: playlist identities plus when they were fetched. */
@Serializable
data class CachedLibrary(
    val fetchedAt: Long = 0L,
    val accountName: String? = null,
    val playlists: List<CachedPlaylist> = emptyList(),
)

/** One playlist's track list. Tracks are Koda's [Song] (already @Serializable): id, title, artist, album, duration, artwork URL — metadata only, never audio. */
@Serializable
data class CachedPlaylistTracks(
    val playlistId: String,
    val fetchedAt: Long = 0L,
    val tracks: List<Song> = emptyList(),
)

/**
 * Disk cache for YouTube Music library metadata (playlist list + per-playlist tracks).
 *
 * Stale-while-revalidate: the UI renders instantly from disk on open, then a
 * background network refresh rewrites the files. Timestamps are stored for
 * diagnostics; any cached copy is fine to serve instantly — the refresh always
 * follows. All methods do blocking IO and must run off the main thread
 * (callers use Dispatchers.IO). Every read/write is failure-proof: a corrupt
 * or missing file just reads as absent, never throws.
 */
class MetadataCache(app: Context) {
    private val dir = File(app.filesDir, "metadata_cache").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun loadLibrary(): CachedLibrary? = read("yt_library.json")

    fun saveLibrary(accountName: String?, playlists: List<PlaylistDisplayItem>) {
        write(
            "yt_library.json",
            CachedLibrary(
                fetchedAt = System.currentTimeMillis(),
                accountName = accountName,
                playlists = playlists.map { it.toCached() },
            ),
        )
    }

    fun loadPlaylistTracks(playlistId: String): CachedPlaylistTracks? = read(trackFileName(playlistId))

    fun savePlaylistTracks(playlistId: String, tracks: List<Song>) {
        write(
            trackFileName(playlistId),
            CachedPlaylistTracks(
                playlistId = playlistId,
                fetchedAt = System.currentTimeMillis(),
                tracks = tracks,
            ),
        )
    }

    private fun trackFileName(playlistId: String): String =
        "yt_playlist_" + playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json"

    private inline fun <reified T> read(name: String): T? = runCatching {
        val file = File(dir, name)
        if (!file.exists()) return null
        json.decodeFromString<T>(file.readText())
    }.getOrNull()

    private inline fun <reified T> write(name: String, value: T) {
        runCatching {
            // Atomic: write temp, then rename over the old file.
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(value))
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
            }
        }
    }
}
