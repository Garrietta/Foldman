package dev.foldman.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ivor.ivormusic.service.MusicService
import dev.foldman.deck.BackendState
import dev.foldman.deck.DeckBackend
import dev.foldman.deck.NowPlaying
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foldman's transport and queue view over its bundled YouTube Music playback service. */
@OptIn(UnstableApi::class)
class YouTubeMusicBackend(private val context: Context) : DeckBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(BackendState(status = SourceStatus.AppNotRunning))
    override val state: kotlinx.coroutines.flow.StateFlow<BackendState> = _state

    private var future: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var artworkKey: Any? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    override fun refresh() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val pending = MediaBrowser.Builder(context, token).buildAsync()
        future = pending
        pending.addListener({
            val connected = runCatching { pending.get() }.getOrNull()
            if (connected == null) {
                if (future === pending) future = null
                _state.value = BackendState(status = SourceStatus.AppNotRunning)
                return@addListener
            }
            browser = connected
            connected.addListener(listener)
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val player = browser
        if (player == null) {
            _state.value = BackendState(status = SourceStatus.AppNotRunning)
            return
        }

        val metadata = player.mediaMetadata
        updateArtwork(metadata)
        val currentIndex = player.currentMediaItemIndex
        val queue = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            QueueEntry(
                id = index.toLong(),
                title = item.mediaMetadata.title?.toString() ?: item.mediaId,
                subtitle = item.mediaMetadata.artist?.toString() ?: item.mediaMetadata.albumTitle?.toString(),
                artworkUri = item.mediaMetadata.artworkUri,
            )
        }
        val duration = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        _state.value = BackendState(
            status = SourceStatus.Ready,
            nowPlaying = NowPlaying(
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString() ?: metadata.albumArtist?.toString(),
                album = metadata.albumTitle?.toString(),
                artwork = artwork,
                artworkKey = artworkKey.takeIf { artwork != null },
                artworkLoading = artwork == null && artworkKey != null,
                durationMs = duration,
            ),
            isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
            ended = player.playbackState == Player.STATE_ENDED,
            trackIndex = currentIndex,
            trackCount = player.mediaItemCount,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queue = queue,
            currentQueueId = currentIndex.takeIf { it >= 0 }?.toLong() ?: -1L,
        )
    }

    private fun updateArtwork(metadata: MediaMetadata) {
        // Keyed by the artwork URI (falling back to the image bytes' hash), not the song:
        // right after a track change the controller can still report the previous song's
        // metadata for a moment, and keying by song would keep that stale cover for the new
        // one. URI-first also means the prefetch pass warms exactly the key asked for here.
        val data = metadata.artworkData
        val uri = metadata.artworkUri
        val key: Any? = uri ?: data?.contentHashCode()
        if (key == artworkKey && (artwork != null || artworkJob?.isActive == true)) return
        artworkJob?.cancel()
        artworkKey = key
        // Serve from the art cache synchronously: on a hit there is no null window, so the
        // cassette label never flashes the default background on track change.
        val cacheKey = ArtworkCache.stableKey(key)
        artwork = cacheKey?.let { ArtworkCache.get(context, it) }
        if (artwork != null) return
        if (data == null && uri == null) return
        artworkJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { data?.let(Artwork::decode) }
                ?: uri?.let { Artwork.load(context, it) }
            if (artworkKey == key && loaded != null) {
                cacheKey?.let { ArtworkCache.put(context, it, loaded) }
                artwork = loaded
                publish()
            }
        }
    }

    override fun positionMs(): Long = browser?.currentPosition ?: 0L

    override fun currentMediaId(): String? = browser?.currentMediaItem?.mediaId

    override fun play() {
        val player = browser ?: return refresh()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() { browser?.pause() }
    override fun seekTo(positionMs: Long) { browser?.seekTo(positionMs) }
    override fun playQueueItem(id: Long) {
        browser?.takeIf { id in 0 until it.mediaItemCount.toLong() }?.seekTo(id.toInt(), 0L)
    }
    override fun skipToNext() { browser?.takeIf(Player::hasNextMediaItem)?.seekToNextMediaItem() }
    override fun skipToPrevious() { browser?.takeIf(Player::hasPreviousMediaItem)?.seekToPreviousMediaItem() }

    override fun release() {
        browser?.removeListener(listener)
        browser = null
        future?.let(MediaController::releaseFuture)
        future = null
        artworkJob?.cancel()
        scope.cancel()
    }
}
