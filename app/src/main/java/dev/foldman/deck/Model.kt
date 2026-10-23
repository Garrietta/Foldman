package dev.foldman.deck

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Where the tape in the deck comes from. */
enum class Source(val label: String, val packageName: String?) {
    LOCAL("Local folder", null),
    YT_MUSIC("YouTube Music", null),
    PODCASTS("Podcasts", null),
}

/**
 * What the mechanism is doing. PAUSED keeps the play key latched with the pinch roller lifted;
 * STOPPED releases every key. The reels animate from this.
 */
enum class Transport { STOPPED, PLAYING, PAUSED, REWINDING, FAST_FORWARDING }

sealed interface SourceStatus {
    data object Ready : SourceStatus
    data object NeedsFolder : SourceStatus
    data class Scanning(val found: Int) : SourceStatus
    data object EmptyFolder : SourceStatus
    data object AppNotRunning : SourceStatus
}

/** What the tape shelf knows about the user's podcast subscriptions (RSS feeds via Koda's PodcastRepository). */
data class PodcastsUi(
    val feeds: List<com.ivor.ivormusic.data.PodcastFeed> = emptyList(),
    val episodesByFeed: Map<String, List<com.ivor.ivormusic.data.PodcastEpisode>> = emptyMap(),
    val openFeed: com.ivor.ivormusic.data.PodcastFeed? = null,
    val loading: Boolean = false,
    val message: String? = null,
    /** Add-feed dialog state. */
    val showAddDialog: Boolean = false,
    val addUrl: String = "",
    val adding: Boolean = false,
    val addError: String? = null,
)

data class YouTubeMusicUi(
    val signedIn: Boolean = false,
    val accountName: String? = null,
    val query: String = "",
    val searchResults: List<com.ivor.ivormusic.data.Song> = emptyList(),
    val searchAlbums: List<com.ivor.ivormusic.data.PlaylistDisplayItem> = emptyList(),
    val searchArtists: List<com.ivor.ivormusic.data.ArtistItem> = emptyList(),
    val searched: Boolean = false,
    val playlists: List<com.ivor.ivormusic.data.PlaylistDisplayItem> = emptyList(),
    val openPlaylist: com.ivor.ivormusic.data.PlaylistDisplayItem? = null,
    val playlistTracks: List<com.ivor.ivormusic.data.Song> = emptyList(),
    /** Artist page opened from a search result: top songs plus albums. */
    val openArtist: com.ivor.ivormusic.data.ArtistItem? = null,
    val artistSongs: List<com.ivor.ivormusic.data.Song> = emptyList(),
    val artistAlbums: List<com.ivor.ivormusic.data.PlaylistDisplayItem> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

data class LocalTrack(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val folder: String,
    val coverUri: Uri?,
)

/** A song in the play queue: a local file (id = index) or another app's queue item. */
data class QueueEntry(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val artwork: Bitmap? = null,
    val artworkUri: Uri? = null,
)

data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    /** Changes whenever the artwork does, so the label palette is only worked out once per cover. */
    val artworkKey: Any? = null,
    /** True while a new track's cover is still loading — the UI should hold the old art, not blank. */
    val artworkLoading: Boolean = false,
    val durationMs: Long = 0,
)

data class BackendState(
    val status: SourceStatus = SourceStatus.Ready,
    val nowPlaying: NowPlaying = NowPlaying(),
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    val trackIndex: Int = -1,
    val trackCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueId: Long = -1,
)

/** One tape source: the local Media3 player, or another app's media session. */
interface DeckBackend {
    val state: StateFlow<BackendState>
    fun positionMs(): Long
    /** Media id of the currently loaded queue item, when the backend exposes one. */
    fun currentMediaId(): String? = null
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun playQueueItem(id: Long)
    /** Re-checks permissions and sessions, e.g. when the app comes back to the foreground. */
    fun refresh() {}
    fun release()
}
