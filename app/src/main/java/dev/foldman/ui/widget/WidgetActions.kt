package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import dev.foldman.MainActivity
import dev.foldman.deck.LocalTrack
import dev.foldman.playback.LocalLibrary
import dev.foldman.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FoldmanWidget"

/**
 * Which of Foldman's two in-process Media3 sessions a widget action talks to.
 * Decoupled from [dev.foldman.deck.Source] by name on purpose: that enum is
 * being reshaped by another round, while these two session targets are stable.
 */
enum class WidgetSource {
    LOCAL,
    YTMUSIC,
    PODCASTS;

    companion object {
        fun fromDeckName(name: String): WidgetSource = when (name) {
            "LOCAL" -> LOCAL
            "YT_MUSIC" -> YTMUSIC
            else -> PODCASTS
        }
    }
}

/** The action's target service. Koda's MusicService is the playback engine only; no Koda UI is touched. */
internal fun WidgetSource.serviceClass(): Class<*> = when (this) {
    WidgetSource.LOCAL -> PlaybackService::class.java
    WidgetSource.YTMUSIC, WidgetSource.PODCASTS -> com.ivor.ivormusic.service.MusicService::class.java
}

/** Must stay unique per process; mirrors the ids set on the services themselves. */
internal fun WidgetSource.sessionId(): String = when (this) {
    WidgetSource.LOCAL -> "foldman_playback"
    WidgetSource.YTMUSIC, WidgetSource.PODCASTS -> "koda_music"
}

internal val SourceParam = ActionParameters.Key<String>("widget_source")

/**
 * Opens Foldman itself. This is the loud fallback when a widget tap has
 * nothing to play: instead of a silent no-op, Garrett lands in the app where
 * he can load a tape.
 */
internal fun openFoldmanApp(context: Context) {
    val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    runCatching { context.applicationContext.startActivity(intent) }
        .onFailure { Log.e(TAG, "openFoldmanApp: startActivity failed", it) }
}

/**
 * Bind a throwaway [MediaController] to the active session, run [block] on the
 * main thread, and release it again. Building the controller starts a stopped
 * service, so actions work with the app closed. A null return means the
 * session could not be reached at all; failures are logged, never swallowed.
 */
internal suspend fun <T> withWidgetController(
    context: Context,
    source: WidgetSource,
    timeoutMs: Long = 3_000L,
    block: suspend (MediaController) -> T,
): T? = withContext(Dispatchers.Main) {
    val appContext = context.applicationContext
    val token = SessionToken(appContext, ComponentName(appContext, source.serviceClass()))
    val future = MediaController.Builder(appContext, token).buildAsync()
    var controller: MediaController? = null
    try {
        controller = withTimeoutOrNull(timeoutMs) { future.await() }
        if (controller == null) {
            Log.w(TAG, "withWidgetController: connect timeout (${timeoutMs}ms) for $source")
            return@withContext null
        }
        block(controller)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "withWidgetController: action failed for $source", e)
        null
    } finally {
        // Both: release() covers a connected controller, releaseFuture covers
        // one still connecting when the timeout fired.
        runCatching { controller?.release() }
        runCatching { MediaController.releaseFuture(future) }
    }
}

private fun MediaController.isPlayingNow(): Boolean =
    isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)

/**
 * After an action, write what the session actually reports into Glance state
 * and redraw. The app-driven updater refreshes artwork and full state when the
 * app is alive; this keeps the widget truthful when it is not.
 */
internal suspend fun publishLiveState(context: Context, glanceId: GlanceId, controller: MediaController) {
    val md = controller.mediaMetadata
    updateAppWidgetState(context, glanceId) { state ->
        state[WidgetKeys.TITLE] = md.title?.toString().orEmpty()
        state[WidgetKeys.ARTIST] = md.artist?.toString().orEmpty()
        state[WidgetKeys.IS_PLAYING] = controller.isPlayingNow()
        state[WidgetKeys.SHUFFLE] = controller.shuffleModeEnabled
        state[WidgetKeys.HAS_NEXT] = controller.hasNextMediaItem()
        state[WidgetKeys.HAS_PREV] = controller.hasPreviousMediaItem()
    }
    FoldmanWidget().update(context, glanceId)
}

private fun ActionParameters.widgetSource(): WidgetSource? =
    this[SourceParam]?.let { runCatching { WidgetSource.valueOf(it) }.getOrNull() }

/**
 * Headless queue restore for when the app is closed and the service restarted
 * with an empty queue. Returns true when the controller now holds a playable
 * queue. Never opens the app UI.
 *
 * - LOCAL: rebuilds from the persisted folder + last-track resume snapshot
 *   (DeckViewModel persists both; key names must match its KEY_* consts).
 * - YTMUSIC/PODCASTS: asks Koda's MusicService to restore its own persisted
 *   session via its purpose-built RESTORE_PLAYBACK command, with a guard
 *   against resuming the wrong kind of queue.
 */
private const val PREFS_NAME = "foldman"
private const val KEY_FOLDER = "folder"
private const val KEY_LAST_TRACK_URI = "last_track_uri"
private const val KEY_LAST_POSITION_MS = "last_position_ms"
private const val KODA_RESTORE_PLAYBACK = "com.ivor.ivormusic.RESTORE_PLAYBACK"

private suspend fun ensureQueueRestored(
    context: Context,
    source: WidgetSource,
    controller: MediaController,
): Boolean {
    if (controller.mediaItemCount > 0) return true
    val restored = when (source) {
        WidgetSource.LOCAL -> restoreLocalQueue(context, controller)
        WidgetSource.YTMUSIC, WidgetSource.PODCASTS -> restoreKodaQueue(controller, source)
    }
    if (restored) Log.i(TAG, "ensureQueueRestored: headless restore succeeded for $source")
    else Log.i(TAG, "ensureQueueRestored: headless restore failed for $source")
    return restored
}

/** Rebuilds the local queue headlessly from the persisted folder + resume snapshot. */
private suspend fun restoreLocalQueue(context: Context, controller: MediaController): Boolean {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val folder = prefs.getString(KEY_FOLDER, null)?.let { Uri.parse(it) } ?: return false
    val tracks = runCatching { LocalLibrary(appContext).scan(folder) }.getOrNull()
    if (tracks.isNullOrEmpty()) return false
    val lastUri = prefs.getString(KEY_LAST_TRACK_URI, null)
    val startIndex = tracks.indexOfFirst { it.uri.toString() == lastUri }.takeIf { it >= 0 } ?: 0
    val startPositionMs = prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
    withContext(Dispatchers.Main) {
        controller.setMediaItems(tracks.map { it.toWidgetMediaItem() }, startIndex, startPositionMs)
        controller.prepare()
    }
    return true
}

/** Asks Koda's MusicService to restore its persisted session, headlessly. */
private suspend fun restoreKodaQueue(controller: MediaController, source: WidgetSource): Boolean {
    val result = runCatching {
        controller.sendCustomCommand(
            SessionCommand(KODA_RESTORE_PLAYBACK, Bundle.EMPTY),
            Bundle.EMPTY,
        ).await()
    }.getOrNull() ?: return false
    if (result.resultCode != SessionResult.RESULT_SUCCESS) return false
    if (controller.mediaItemCount == 0) return false
    // Guard against resuming the wrong kind of queue (e.g. a podcast queue
    // when the widget was on YouTube Music). Podcast song ids are
    // "podcast:"-prefixed; YouTube items are bare video ids.
    val currentId = runCatching {
        controller.getMediaItemAt(controller.currentMediaItemIndex).mediaId
    }.getOrNull().orEmpty()
    val isPodcast = currentId.startsWith("podcast:")
    if (source == WidgetSource.PODCASTS && !isPodcast) return false
    if (source == WidgetSource.YTMUSIC && isPodcast) return false
    return true
}

private fun LocalTrack.toWidgetMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(folder)
            .setArtworkUri(coverUri)
            .build(),
    )
    .build()

/**
 * PLAY key: starts playback. With the app closed the service restarts empty;
 * the queue is restored headlessly first, and the app only opens as a last
 * resort when there is genuinely nothing to play.
 */
class WidgetPlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        animateKeyPress(context, glanceId, "play")
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetPlayAction: missing source param; opening app")
            openFoldmanApp(context)
            return
        }
        val reached = withWidgetController(context, source) { controller ->
            if (!ensureQueueRestored(context, source, controller)) {
                Log.i(TAG, "WidgetPlayAction: empty queue on $source and restore failed; opening app")
                openFoldmanApp(context)
            } else {
                controller.play()
            }
            publishLiveState(context, glanceId, controller)
        }
        if (reached == null) {
            Log.w(TAG, "WidgetPlayAction: could not reach $source; opening app")
            openFoldmanApp(context)
        }
    }
}

/** PAUSE key: pauses whatever is playing. */
class WidgetPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        animateKeyPress(context, glanceId, "pause")
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetPauseAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            controller.pause()
            publishLiveState(context, glanceId, controller)
        }
    }
}

/** STOP key: stops playback. */
class WidgetStopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        animateKeyPress(context, glanceId, "stop")
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetStopAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            controller.stop()
            publishLiveState(context, glanceId, controller)
        }
    }
}

/**
 * Shows a press-in animation on a widget transport key: sets the pressed state,
 * redraws, waits 150ms, clears. The key renders darker/inset while pressed.
 */
suspend fun animateKeyPress(context: Context, glanceId: GlanceId, key: String) {
    val widget = FoldmanWidget()
    updateAppWidgetState(context, glanceId) { state ->
        state[WidgetKeys.PRESSED_KEY] = key
    }
    runCatching { widget.update(context, glanceId) }
    delay(150)
    updateAppWidgetState(context, glanceId) { state ->
        state.remove(WidgetKeys.PRESSED_KEY)
    }
    runCatching { widget.update(context, glanceId) }
}

class WidgetPrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        animateKeyPress(context, glanceId, "prev")
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetPrevAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            if (ensureQueueRestored(context, source, controller)) {
                controller.seekToPreviousMediaItem()
            }
            publishLiveState(context, glanceId, controller)
        }
    }
}

class WidgetNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        animateKeyPress(context, glanceId, "next")
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetNextAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            if (ensureQueueRestored(context, source, controller)) {
                controller.seekToNextMediaItem()
            }
            publishLiveState(context, glanceId, controller)
        }
    }
}


