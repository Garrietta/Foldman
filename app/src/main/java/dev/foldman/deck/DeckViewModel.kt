package dev.foldman.deck

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.PodcastEpisode
import com.ivor.ivormusic.data.PodcastFeed
import com.ivor.ivormusic.data.PodcastRepository
import com.ivor.ivormusic.data.SessionManager
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.YouTubeRepository
import com.ivor.ivormusic.service.MusicService
import com.ivor.ivormusic.ui.player.PlayerViewModel
import dev.foldman.playback.Artwork
import dev.foldman.playback.ArtworkCache
import dev.foldman.playback.LocalBackend
import dev.foldman.playback.LocalLibrary
import dev.foldman.playback.YouTubeMusicBackend
import dev.foldman.ui.widget.FoldmanWidgetUpdater
import dev.foldman.ui.widget.WidgetSnapshot
import dev.foldman.ui.widget.WidgetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Cassette label colours, taken from the album art when there is some. */
data class LabelPalette(val paper: Color, val stripe: Color, val stripe2: Color, val ink: Color) {
    companion object {
        val Default = LabelPalette(Color(0xFFF2EBDC), Color(0xFFE8642C), Color(0xFFF2B33D), Color(0xFF2A2522))
    }
}

data class DeckUi(
    val source: Source = Source.LOCAL,
    val transport: Transport = Transport.STOPPED,
    val status: SourceStatus = SourceStatus.NeedsFolder,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: ImageBitmap? = null,
    val label: LabelPalette = LabelPalette.Default,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val trackIndex: Int = -1,
    val trackCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val tracks: List<LocalTrack> = emptyList(),
    val folderName: String? = null,
    val volume: Float = 0.5f,
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueId: Long = -1,
    val keySounds: Boolean = true,
    val haptics: Boolean = true,
    val shuffleOn: Boolean = false,
    val podcasts: PodcastsUi = PodcastsUi(),
    val youtubeMusic: YouTubeMusicUi = YouTubeMusicUi(),
) {
    /** How far through the tape we are: the left reel empties into the right one as this grows. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

sealed interface DeckEvent {
    /** The tape ran out while winding: the mechanism thunks back to stop. */
    data object TapeEnd : DeckEvent
    /** STOP pressed while already stopped: the cassette door pops. */
    data object Eject : DeckEvent
}

class DeckViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("foldman", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val library = LocalLibrary(app)
    private val local = LocalBackend(app).also { it.connect() }
    private val youtubeBackend = YouTubeMusicBackend(app)
    private val youtubeSession = SessionManager(app)
    private val youtubeRepository = YouTubeRepository(app)
    private val _youtubeMusic = MutableStateFlow(YouTubeMusicUi())
    val youtubeMusic: StateFlow<YouTubeMusicUi> = _youtubeMusic.asStateFlow()
    private var youtubeJob: Job? = null
    /** Disk cache for YouTube Music library metadata: stale-while-revalidate. */
    private val metadataCache = MetadataCache(app)
    private val podcastRepository = PodcastRepository(app)
    private val _podcasts = MutableStateFlow(PodcastsUi())
    val podcasts: StateFlow<PodcastsUi> = _podcasts.asStateFlow()
    private var podcastJob: Job? = null
    private val _shuffleOn = MutableStateFlow(false)

    /** Toggles shuffle on the queue that's in the deck. */
    @androidx.media3.common.util.UnstableApi
    fun toggleShuffle() = setShuffleEnabled(!_shuffleOn.value)

    private val _ui = MutableStateFlow(DeckUi())
    val ui: StateFlow<DeckUi> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<DeckEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DeckEvent> = _events

    private var source = runCatching { Source.valueOf(prefs.getString(KEY_SOURCE, null) ?: "") }.getOrDefault(Source.LOCAL)
    private val backend: DeckBackend
        get() = when (source) {
            Source.LOCAL -> local
            Source.YT_MUSIC, Source.PODCASTS -> youtubeBackend
        }

    private var tracks: List<LocalTrack> = emptyList()
    private var folderName: String? = null
    private var scanJob: Job? = null

    /** STOP was pressed (or the tape ended): show STOPPED rather than PAUSED while not playing. */
    private var stopped = true
    /** Play was just pressed; keep the play key down while the player catches up. */
    private var playRequestedAt = 0L
    /** -1 rewinding, +1 fast-forwarding, 0 neither. */
    private var winding = 0
    private var windPosition = 0L
    private var windLastTick = 0L
    private var windLastSeek = 0L
    private var windWaitingForTrack = -1

    private var paletteKey: Any? = null
    private var label = LabelPalette.Default
    private var artBitmap: ImageBitmap? = null
    private var artSource: Any? = null
    private var lastWidgetKey: Any? = null
    private var lastPrefetchKey: Any? = null

    init {
        prefs.getString(KEY_FOLDER, null)?.let { loadFolder(Uri.parse(it), firstLoad = true) }
        backend.refresh()
        refreshYouTubeMusic()
        viewModelScope.launch {
            podcastRepository.ensureLoaded()
            launch { podcastRepository.feeds.collect { feeds -> _podcasts.value = _podcasts.value.copy(feeds = feeds) } }
            launch { podcastRepository.episodesByFeed.collect { map -> _podcasts.value = _podcasts.value.copy(episodesByFeed = map) } }
            // Quiet background refresh of subscribed feeds. The disk cache (Koda's
            // PodcastRepository) already rendered instantly above; this just tops up
            // episode lists without touching the UI. Failures stay silent.
            launch(Dispatchers.IO) {
                val feeds = withTimeoutOrNull(30_000) {
                    podcastRepository.feeds.first { it.isNotEmpty() }
                }.orEmpty()
                feeds.forEach { feed -> runCatching { podcastRepository.refreshFeed(feed.id) } }
            }
        }
        viewModelScope.launch {
            while (true) {
                tick()
                delay(if (winding != 0) 33 else 100)
            }
        }
    }

    // region Keys

    fun play() {
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        backend.play()
        tick()
    }

    fun pauseKey() {
        when (_ui.value.transport) {
            Transport.PLAYING -> {
                stopped = false
                playRequestedAt = 0
                backend.pause()
            }
            Transport.PAUSED -> play()
            else -> return
        }
        tick()
    }

    fun stop() {
        when {
            winding != 0 -> finishWinding()
            _ui.value.transport == Transport.STOPPED -> _events.tryEmit(DeckEvent.Eject)
            else -> backend.pause()
        }
        stopped = true
        playRequestedAt = 0
        tick()
    }

    fun rewind() = startWinding(-1)
    fun fastForward() = startWinding(+1)

    /** AMS back: to the top of this song, or the previous one when already near its start. */
    fun previous() {
        if (winding != 0) finishWinding()
        if (backend.positionMs() > 3000 || !_ui.value.hasPrevious) backend.seekTo(0) else backend.skipToPrevious()
        tick()
    }

    fun next() {
        if (winding != 0) finishWinding()
        backend.skipToNext()
        tick()
    }

    private fun startWinding(direction: Int) {
        if (winding == direction) return
        if (_ui.value.status != SourceStatus.Ready) return
        if (winding == 0) {
            backend.pause()
            windPosition = backend.positionMs()
        }
        winding = direction
        windLastTick = SystemClock.elapsedRealtime()
        windWaitingForTrack = -1
        playRequestedAt = 0
        tick()
    }

    private fun finishWinding() {
        winding = 0
        windWaitingForTrack = -1
        backend.seekTo(windPosition)
    }

    private fun tapeEnd() {
        finishWinding()
        stopped = true
        _events.tryEmit(DeckEvent.TapeEnd)
    }

    /** Moves the tape at [WIND_SPEED]× while REW/FF is latched, crossing into neighbouring songs like a real tape. */
    private fun windStep(state: BackendState) {
        val now = SystemClock.elapsedRealtime()
        val dt = now - windLastTick
        windLastTick = now
        val duration = state.nowPlaying.durationMs
        if (windWaitingForTrack >= 0) {
            // Just rewound into the previous song: wait until we know how long it is, then start from its end.
            if (state.trackIndex != windWaitingForTrack || duration <= 0) return
            windWaitingForTrack = -1
            windPosition = duration
        }
        windPosition += winding * dt * WIND_SPEED
        val isLocal = source == Source.LOCAL
        when {
            windPosition <= 0 -> {
                windPosition = 0
                if (isLocal && state.hasPrevious) {
                    windWaitingForTrack = state.trackIndex - 1
                    backend.skipToPrevious()
                } else tapeEnd()
            }
            duration > 0 && windPosition >= duration -> {
                if (state.hasNext) {
                    windPosition = 0
                    backend.skipToNext()
                    if (isLocal) backend.pause() else tapeEnd()
                } else {
                    windPosition = duration
                    tapeEnd()
                }
            }
            // Other apps' sessions get fewer seeks; ours can take one per frame.
            isLocal || now - windLastSeek > 250 -> {
                backend.seekTo(windPosition)
                windLastSeek = now
            }
        }
    }

    // endregion

    // region Sources

    fun selectSource(newSource: Source) {
        if (newSource == source) return
        if (winding != 0) finishWinding()
        // One tape in the deck at a time: ejecting stops the old one.
        if (backend.state.value.isPlaying) backend.pause()
        source = newSource
        prefs.edit { putString(KEY_SOURCE, newSource.name) }
        stopped = !backend.state.value.isPlaying
        backend.refresh()
        tick()
    }

    fun setFolder(uri: Uri) {
        prefs.edit { putString(KEY_FOLDER, uri.toString()) }
        selectSource(Source.LOCAL)
        loadFolder(uri, firstLoad = false)
    }

    fun playTrack(index: Int) {
        selectSource(Source.LOCAL)
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        local.playTrack(index)
    }

    private fun loadFolder(uri: Uri, firstLoad: Boolean) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            local.setLibraryStatus(SourceStatus.Scanning(0))
            val found = runCatching {
                library.scan(uri) { n -> viewModelScope.launch { local.setLibraryStatus(SourceStatus.Scanning(n)) } }
            }.getOrElse {
                local.setLibraryStatus(SourceStatus.NeedsFolder)
                return@launch
            }
            folderName = found.firstOrNull()?.folder ?: uri.lastPathSegment?.substringAfterLast(':')
            tracks = found
            local.setTracks(found, startIndex = if (firstLoad) null else 0)
            if (!firstLoad) stopped = true
            tick()
            val tagged = library.readTags(found)
            tracks = tagged
            local.setTracks(tagged)
            tick()
        }
    }

    fun refresh() {
        backend.refresh()
        tick()
    }

    /**
     * Stale-while-revalidate: the library renders instantly from the disk cache,
     * then a background network refresh rewrites the cache. Manual refreshes
     * always hit the network.
     */
    fun refreshYouTubeMusic() {
        val signedIn = youtubeSession.isLoggedIn()
        youtubeJob?.cancel()
        youtubeJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { metadataCache.loadLibrary() }
            _youtubeMusic.value = _youtubeMusic.value.copy(
                signedIn = signedIn,
                accountName = youtubeSession.getUserName(),
                playlists = if (signedIn) {
                    cached?.playlists?.map { it.toDisplayItem() } ?: _youtubeMusic.value.playlists
                } else {
                    emptyList()
                },
                message = null,
            )
            if (signedIn) launch { fetchPlaylistsFromNetwork() }
        }
    }

    fun setYouTubeMusicQuery(query: String) {
        _youtubeMusic.value = _youtubeMusic.value.copy(query = query)
    }

    fun searchYouTubeMusic() {
        val query = _youtubeMusic.value.query.trim()
        if (query.isEmpty()) return
        youtubeJob?.cancel()
        youtubeJob = viewModelScope.launch {
            _youtubeMusic.value = _youtubeMusic.value.copy(
                loading = true,
                searched = true,
                searchResults = emptyList(),
                openPlaylist = null,
                playlistTracks = emptyList(),
                message = null,
            )
            try {
                val songs = youtubeRepository.search(query)
                _youtubeMusic.value = _youtubeMusic.value.copy(
                    searchResults = songs,
                    message = if (songs.isEmpty()) "No songs found." else null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _youtubeMusic.value = _youtubeMusic.value.copy(
                    message = e.message ?: "Couldn't search YouTube Music.",
                )
            } finally {
                _youtubeMusic.value = _youtubeMusic.value.copy(loading = false)
            }
        }
    }

    /**
     * Background network revalidation of the playlist list. Runs as a child of
     * [youtubeJob] so searches and playlist opens cancel it. The spinner only
     * shows when there is nothing cached to display.
     */
    private suspend fun fetchPlaylistsFromNetwork() {
        _youtubeMusic.value = _youtubeMusic.value.copy(
            loading = _youtubeMusic.value.playlists.isEmpty(),
            message = null,
        )
        try {
            val playlists = withContext(Dispatchers.IO) { youtubeRepository.getUserPlaylists() }
            withContext(Dispatchers.IO) {
                metadataCache.saveLibrary(_youtubeMusic.value.accountName, playlists)
            }
            _youtubeMusic.value = _youtubeMusic.value.copy(
                playlists = playlists,
                message = if (playlists.isEmpty()) "No playlists were found in this account." else null,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _youtubeMusic.value = _youtubeMusic.value.copy(
                message = e.message ?: "Couldn't load your YouTube Music library.",
            )
        } finally {
            _youtubeMusic.value = _youtubeMusic.value.copy(loading = false)
        }
    }

    /**
     * Stale-while-revalidate: the playlist's tracks render instantly from the
     * disk cache when present, then a background network refresh rewrites the
     * cache. A pending shuffle-play is fulfilled from the cache immediately
     * when possible instead of waiting on the network.
     */
    fun openYouTubePlaylist(playlist: PlaylistDisplayItem) {
        youtubeJob?.cancel()
        if (pendingShufflePlaylist?.first?.id != playlist.id) pendingShufflePlaylist = null
        youtubeJob = viewModelScope.launch {
            val cachedTracks = withContext(Dispatchers.IO) {
                metadataCache.loadPlaylistTracks(playlist.id)?.tracks.orEmpty()
            }
            _youtubeMusic.value = _youtubeMusic.value.copy(
                openPlaylist = playlist,
                playlistTracks = cachedTracks,
                loading = cachedTracks.isEmpty(),
                message = null,
            )
            val pending = pendingShufflePlaylist?.takeIf { it.first.id == playlist.id }
            if (pending != null && cachedTracks.isNotEmpty()) {
                pendingShufflePlaylist = null
                selectSource(Source.YT_MUSIC)
                setShuffleEnabled(true)
                playKodaSongs(shufflePlayOrder(cachedTracks), 0, pending.second, Source.YT_MUSIC)
                return@launch
            }
            launch { fetchPlaylistTracksFromNetwork(playlist) }
        }
    }

    /**
     * Background network revalidation of one playlist's tracks. Runs as a child
     * of [youtubeJob] so it is cancelled by searches and other playlist opens.
     */
    private suspend fun fetchPlaylistTracksFromNetwork(playlist: PlaylistDisplayItem) {
        _youtubeMusic.value = _youtubeMusic.value.copy(
            loading = _youtubeMusic.value.playlistTracks.isEmpty(),
            message = null,
        )
        try {
            val songs = withContext(Dispatchers.IO) { youtubeRepository.getPlaylist(playlist.id) }
            withContext(Dispatchers.IO) { metadataCache.savePlaylistTracks(playlist.id, songs) }
            _youtubeMusic.value = _youtubeMusic.value.copy(
                playlistTracks = songs,
                message = if (songs.isEmpty()) "This playlist has no playable songs." else null,
            )
            pendingShufflePlaylist?.takeIf { it.first.id == playlist.id }?.let { (_, player) ->
                pendingShufflePlaylist = null
                if (songs.isNotEmpty()) {
                    selectSource(Source.YT_MUSIC)
                    setShuffleEnabled(true)
                    playKodaSongs(shufflePlayOrder(songs), 0, player, Source.YT_MUSIC)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _youtubeMusic.value = _youtubeMusic.value.copy(
                message = e.message ?: "Couldn't open this playlist.",
            )
        } finally {
            _youtubeMusic.value = _youtubeMusic.value.copy(loading = false)
        }
    }

    fun closeYouTubePlaylist() {
        _youtubeMusic.value = _youtubeMusic.value.copy(openPlaylist = null, playlistTracks = emptyList(), message = null)
    }

    @androidx.media3.common.util.UnstableApi
    fun playYouTubeMusic(songs: List<Song>, startIndex: Int, player: PlayerViewModel) {
        playKodaSongs(songs, startIndex, player, Source.YT_MUSIC)
    }

    /**
     * Plays Koda [Song]s (YouTube Music tracks or podcast episodes) through Koda's
     * PlayerViewModel, which drives Koda's MusicService. The deck's transport then
     * follows that service via [youtubeBackend].
     */
    @androidx.media3.common.util.UnstableApi
    fun playKodaSongs(songs: List<Song>, startIndex: Int, player: PlayerViewModel, forSource: Source) {
        val startSong = songs.getOrNull(startIndex) ?: return
        selectSource(forSource)
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        player.playQueue(songs, startSong)
        youtubeBackend.refresh()
        tick()
    }

    /**
     * Shuffle-play order: the first song stays on top, the rest shuffle.
     * Shuffling the list itself (not just the session flag) makes the queue
     * order deterministic regardless of when the async session flag lands.
     */
    private fun shufflePlayOrder(songs: List<Song>): List<Song> =
        if (songs.size > 1) listOf(songs[0]) + songs.drop(1).shuffled() else songs

    /** Plays a podcast feed's episodes from the top, shuffled (first episode plays, the rest shuffle). */
    @androidx.media3.common.util.UnstableApi
    fun playPodcastFeedShuffled(feed: PodcastFeed, player: PlayerViewModel) {
        val songs = podcastSongs(feed.id)
        if (songs.isEmpty()) return
        selectSource(Source.PODCASTS)
        setShuffleEnabled(true)
        playKodaSongs(shufflePlayOrder(songs), 0, player, Source.PODCASTS)
    }

    /** Plays a YouTube Music playlist from the top, shuffled. */
    @androidx.media3.common.util.UnstableApi
    fun playYouTubePlaylistShuffled(playlist: PlaylistDisplayItem, player: PlayerViewModel) {
        val songs = _youtubeMusic.value.playlistTracks.takeIf { _youtubeMusic.value.openPlaylist?.id == playlist.id }
        if (songs.isNullOrEmpty()) {
            // Tracks aren't loaded yet: open the playlist first, then shuffle-play once they arrive.
            openYouTubePlaylist(playlist)
            pendingShufflePlaylist = playlist to player
            return
        }
        selectSource(Source.YT_MUSIC)
        setShuffleEnabled(true)
        playKodaSongs(shufflePlayOrder(songs), 0, player, Source.YT_MUSIC)
    }

    private var pendingShufflePlaylist: Pair<PlaylistDisplayItem, PlayerViewModel>? = null

    /** Plays one episode inside its feed's episode list. */
    @androidx.media3.common.util.UnstableApi
    fun playPodcastEpisode(episode: PodcastEpisode, player: PlayerViewModel) {
        val songs = podcastSongs(episode.feedId)
        val index = songs.indexOfFirst { it.id == "podcast:${episode.id}" }.coerceAtLeast(0)
        playKodaSongs(songs, index, player, Source.PODCASTS)
    }

    private fun podcastSongs(feedId: String): List<Song> {
        val ui = _podcasts.value
        val feed = ui.feeds.firstOrNull { it.id == feedId }
        return (ui.episodesByFeed[feedId] ?: emptyList()).map { podcastRepository.episodeToSong(it, feed) }
    }

    /**
     * Shuffles the active source's queue. Local goes straight to Foldman's player;
     * YouTube Music and Podcasts go through Koda's MusicService via a throwaway
     * MediaController (released right after the flag lands).
     */
    @androidx.media3.common.util.UnstableApi
    fun setShuffleEnabled(enabled: Boolean) {
        _shuffleOn.value = enabled
        when (source) {
            Source.LOCAL -> local.setShuffleEnabled(enabled)
            Source.YT_MUSIC, Source.PODCASTS -> {
                val app = getApplication<Application>()
                val token = SessionToken(app, ComponentName(app, MusicService::class.java))
                val future = MediaController.Builder(app, token).buildAsync()
                future.addListener({
                    val controller = runCatching { future.get() }.getOrNull()
                    if (controller == null) {
                        MediaController.releaseFuture(future)
                        return@addListener
                    }
                    // The session can already have shuffle on: Koda persists
                    // it across restarts, and the widget flips it without
                    // telling the deck. Re-setting an already-set flag is a
                    // silent no-op (no shuffle-mode-changed callback, no
                    // reshuffle), so cycle it to guarantee a fresh shuffle.
                    if (enabled) controller.shuffleModeEnabled = false
                    controller.shuffleModeEnabled = enabled
                    controller.release()
                }, ContextCompat.getMainExecutor(app))
            }
        }
    }

    private var queueFor: List<LocalTrack>? = null
    private var queueCache: List<QueueEntry> = emptyList()

    private fun localQueue(): List<QueueEntry> {
        if (queueFor !== tracks) {
            queueFor = tracks
            queueCache = tracks.mapIndexed { i, t ->
                QueueEntry(i.toLong(), t.title, t.artist ?: t.folder, artworkUri = t.coverUri)
            }
        }
        return queueCache
    }

    fun playQueueItem(id: Long) {
        if (source == Source.LOCAL) return playTrack(id.toInt())
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        backend.playQueueItem(id)
    }

    fun setKeySounds(on: Boolean) {
        prefs.edit { putBoolean(KEY_SOUNDS, on) }
        tick()
    }

    fun setHaptics(on: Boolean) {
        prefs.edit { putBoolean(KEY_HAPTICS, on) }
        tick()
    }

    // region Podcasts (RSS)

    fun setPodcastAddUrl(url: String) {
        _podcasts.value = _podcasts.value.copy(addUrl = url, addError = null)
    }

    fun showAddPodcastDialog(show: Boolean) {
        _podcasts.value = _podcasts.value.copy(showAddDialog = show, addUrl = "", addError = null, adding = false)
    }

    fun addPodcastFeed() {
        val url = _podcasts.value.addUrl.trim()
        if (url.isEmpty() || _podcasts.value.adding) return
        podcastJob?.cancel()
        podcastJob = viewModelScope.launch {
            _podcasts.value = _podcasts.value.copy(adding = true, addError = null)
            podcastRepository.addFeed(url)
                .onSuccess { feed ->
                    _podcasts.value = _podcasts.value.copy(
                        adding = false, showAddDialog = false, addUrl = "", addError = null, message = null,
                        openFeed = feed,
                    )
                }
                .onFailure { e ->
                    _podcasts.value = _podcasts.value.copy(
                        adding = false, addError = e.message ?: "Couldn't add that feed. Check the URL.",
                    )
                }
        }
    }

    fun openPodcastFeed(feed: PodcastFeed?) {
        _podcasts.value = _podcasts.value.copy(openFeed = feed, message = null)
        if (feed != null) refreshPodcastFeed(feed)
    }

    fun refreshPodcastFeed(feed: PodcastFeed) {
        podcastJob?.cancel()
        podcastJob = viewModelScope.launch {
            _podcasts.value = _podcasts.value.copy(loading = true, message = null)
            try {
                val count = podcastRepository.refreshFeed(feed.id).getOrThrow()
                _podcasts.value = _podcasts.value.copy(message = if (count == 0) "Feed is up to date." else null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _podcasts.value = _podcasts.value.copy(message = e.message ?: "Couldn't refresh this feed.")
            } finally {
                _podcasts.value = _podcasts.value.copy(loading = false)
            }
        }
    }

    fun removePodcastFeed(feed: PodcastFeed) {
        podcastJob?.cancel()
        podcastJob = viewModelScope.launch {
            podcastRepository.removeFeed(feed.id)
            if (_podcasts.value.openFeed?.id == feed.id) _podcasts.value = _podcasts.value.copy(openFeed = null)
        }
    }

    // endregion


    fun setVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        _ui.value = _ui.value.copy(volume = fraction.coerceIn(0f, 1f))
    }

    // endregion

    private fun tick() {
        val state = backend.state.value
        val now = SystemClock.elapsedRealtime()
        if (winding != 0) windStep(state)
        if (state.ended) stopped = true
        if (state.isPlaying && winding == 0) {
            stopped = false
            playRequestedAt = 0
        }
        val playPending = now - playRequestedAt < 1500
        val transport = when {
            winding < 0 -> Transport.REWINDING
            winding > 0 -> Transport.FAST_FORWARDING
            state.isPlaying || playPending -> Transport.PLAYING
            stopped -> Transport.STOPPED
            else -> Transport.PAUSED
        }
        val np = state.nowPlaying
        updateLabel(np)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        val prev = _ui.value
        _ui.value = DeckUi(
            source = source,
            transport = transport,
            status = state.status,
            title = np.title.orEmpty(),
            artist = np.artist.orEmpty(),
            album = np.album.orEmpty(),
            artwork = artBitmap,
            label = label,
            positionMs = if (winding != 0) windPosition else backend.positionMs(),
            durationMs = np.durationMs,
            trackIndex = state.trackIndex,
            trackCount = state.trackCount,
            hasNext = state.hasNext,
            hasPrevious = state.hasPrevious,
            tracks = tracks,
            folderName = folderName,
            // While the wheel is being turned our own value leads; otherwise follow the hardware keys.
            volume = if (kotlin.math.abs(prev.volume - volume) * max < 1f) prev.volume else volume,
            queue = if (source == Source.LOCAL) localQueue() else state.queue,
            currentQueueId = if (source == Source.LOCAL) state.trackIndex.toLong() else state.currentQueueId,
            keySounds = prefs.getBoolean(KEY_SOUNDS, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            shuffleOn = _shuffleOn.value,
            podcasts = _podcasts.value,
            youtubeMusic = _youtubeMusic.value,
        )
        // Warm the art cache for what's coming next, so hitting NEXT finds the cover
        // already cached instead of flashing the default background while it loads.
        val trackKey = listOf(source, np.title, np.artist)
        if (trackKey != lastPrefetchKey) {
            lastPrefetchKey = trackKey
            val upcomingUris = if (source == Source.LOCAL) {
                tracks.drop(state.trackIndex + 1).take(3).mapNotNull { it.coverUri }
            } else {
                val queue = state.queue
                val pos = queue.indexOfFirst { it.id == state.currentQueueId }.takeIf { it >= 0 } ?: -1
                queue.drop(pos + 1).take(3).mapNotNull { it.artworkUri }
            }
            if (upcomingUris.isNotEmpty()) {
                val app = getApplication<Application>()
                viewModelScope.launch(Dispatchers.IO) {
                    for (uri in upcomingUris) {
                        val key = ArtworkCache.stableKey(uri) ?: continue
                        if (ArtworkCache.get(app, key) == null) {
                            Artwork.load(app, uri)?.let { ArtworkCache.put(app, key, it) }
                        }
                    }
                }
            }
        }
        // Push now-playing state to the home-screen widget. Gated on track /
        // transport / artwork / shuffle changes — never on position ticks.
        val widgetKey = listOf(np.title, np.artist, transport, source, _shuffleOn.value, np.artwork)
        if (widgetKey != lastWidgetKey) {
            lastWidgetKey = widgetKey
            val app = getApplication<Application>()
            viewModelScope.launch {
                FoldmanWidgetUpdater.update(app, WidgetSnapshot(
                    title = np.title.orEmpty().ifBlank { "Foldman" },
                    artist = np.artist.orEmpty(),
                    artwork = np.artwork,
                    isPlaying = transport == Transport.PLAYING,
                    shuffleOn = _shuffleOn.value,
                    activeSource = WidgetSource.fromDeckName(source.name),
                ))
            }
        }
    }

    private fun updateLabel(np: NowPlaying) {
        val bitmap = np.artwork
        if (bitmap == null) {
            // A new track's cover is still loading: hold the previous art/label instead of
            // flashing the default background. Only fall back when a track genuinely has no art.
            if (np.artworkLoading) return
            artBitmap = null
            artSource = null
            paletteKey = null
            label = LabelPalette.Default
            return
        }
        if (artSource !== bitmap) {
            artSource = bitmap
            artBitmap = bitmap.asImageBitmap()
        }
        val key = np.artworkKey ?: bitmap
        if (key == paletteKey) return
        paletteKey = key
        viewModelScope.launch {
            val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).maximumColorCount(16).generate() }
            if (paletteKey != key) return@launch
            val d = LabelPalette.Default
            label = LabelPalette(
                paper = palette.lightMutedSwatch?.rgb?.let { blend(Color(it), d.paper, 0.75f) } ?: d.paper,
                stripe = (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color) ?: d.stripe,
                stripe2 = (palette.lightVibrantSwatch ?: palette.mutedSwatch)?.rgb?.let(::Color) ?: d.stripe2,
                ink = palette.darkMutedSwatch?.rgb?.let { blend(Color(it), d.ink, 0.5f) } ?: d.ink,
            )
            tick()
        }
    }

    override fun onCleared() {
        local.release()
        youtubeBackend.release()
    }

    companion object {
        private const val KEY_SOURCE = "source"
        private const val KEY_FOLDER = "folder"
        private const val KEY_SOUNDS = "key_sounds"
        private const val KEY_HAPTICS = "haptics"
        /** Winding speed relative to play: a four-minute song winds through in about 20 s. */
        const val WIND_SPEED = 12L

        private fun blend(a: Color, b: Color, t: Float) = Color(
            red = a.red * (1 - t) + b.red * t,
            green = a.green * (1 - t) + b.green * t,
            blue = a.blue * (1 - t) + b.blue * t,
        )
    }
}
