package dev.foldman.apple

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ApplePlaylist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    /** Shareable catalog id (pl.u-…), when the playlist has one; used to open it in the Apple Music app. */
    val globalId: String?,
)

data class AppleTrack(
    val id: String,
    /** Catalog song id. Uploaded songs have none and can't be streamed through MusicKit. */
    val catalogId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String?,
)

class AppleAuthExpired : Exception("Apple Music sign-in expired")

/**
 * The bits of the Apple Music API the tape shelf needs: the user's library playlists and their
 * songs. Every call carries the developer token and the user's Music User Token.
 */
class AppleMusicApi(private val developerToken: String, private val userToken: String) {

    suspend fun playlists(): List<ApplePlaylist> = paged("/v1/me/library/playlists?limit=100") { o ->
        val a = o.optJSONObject("attributes") ?: return@paged null
        ApplePlaylist(
            id = o.getString("id"),
            name = a.optString("name", "Playlist"),
            artworkUrl = a.optJSONObject("artwork")?.optString("url")?.takeIf { it.isNotBlank() },
            globalId = a.optJSONObject("playParams")?.optString("globalId")?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun tracks(playlistId: String): List<AppleTrack> =
        paged("/v1/me/library/playlists/${URLEncoder.encode(playlistId, "UTF-8")}/tracks?limit=100") { o ->
            val a = o.optJSONObject("attributes") ?: return@paged null
            AppleTrack(
                id = o.getString("id"),
                catalogId = a.optJSONObject("playParams")?.optString("catalogId")?.takeIf { it.isNotBlank() },
                title = a.optString("name"),
                artist = a.optString("artistName"),
                album = a.optString("albumName"),
                durationMs = a.optLong("durationInMillis"),
                artworkUrl = a.optJSONObject("artwork")?.optString("url")?.takeIf { it.isNotBlank() },
            )
        }

    private suspend fun <T> paged(first: String, parse: (JSONObject) -> T?): List<T> = withContext(Dispatchers.IO) {
        val out = mutableListOf<T>()
        var next: String? = first
        while (next != null && out.size < MAX_ITEMS) {
            val page = get(next)
            val data = page.optJSONArray("data")
            if (data != null) for (i in 0 until data.length()) data.optJSONObject(i)?.let(parse)?.let(out::add)
            next = page.optString("next").takeIf { it.isNotBlank() }
        }
        out
    }

    private fun get(path: String): JSONObject {
        val conn = URL("https://api.music.apple.com$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $developerToken")
            conn.setRequestProperty("Music-User-Token", userToken)
            return when (val code = conn.responseCode) {
                in 200..299 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                401, 403 -> throw AppleAuthExpired()
                // An empty playlist has no tracks resource at all.
                404 -> JSONObject()
                else -> error("Apple Music API returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_ITEMS = 1000

        /** Artwork URLs are templates: "…/{w}x{h}bb.jpg". */
        fun artwork(template: String?, size: Int): String? =
            template?.replace("{w}", size.toString())?.replace("{h}", size.toString())
    }
}
