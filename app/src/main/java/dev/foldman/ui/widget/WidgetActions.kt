package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.foldman.MainActivity
import dev.foldman.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/** One-shot read of the session's shuffle mode, for the updater snapshot. */
suspend fun readShuffleMode(context: Context, source: WidgetSource): Boolean =
    withWidgetController(context, source) { it.shuffleModeEnabled } ?: false

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

class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetPlayPauseAction: missing source param; opening app")
            openFoldmanApp(context)
            return
        }
        val reached = withWidgetController(context, source) { controller ->
            if (controller.mediaItemCount == 0) {
                // Nothing loaded: a bare play() would be a silent no-op, so
                // open the app where a tape can be loaded instead.
                Log.i(TAG, "WidgetPlayPauseAction: empty queue on $source; opening app")
                openFoldmanApp(context)
            } else if (controller.playWhenReady || controller.playbackState == Player.STATE_BUFFERING) {
                // Decide from live state, not the last render: buffering counts
                // as playing, so a second tap during spin-up stops instead of
                // queueing a redundant play.
                controller.pause()
            } else {
                controller.play()
            }
            publishLiveState(context, glanceId, controller)
        }
        if (reached == null) {
            Log.w(TAG, "WidgetPlayPauseAction: could not reach $source; opening app")
            openFoldmanApp(context)
        }
    }
}

class WidgetPrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetPrevAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            controller.seekToPreviousMediaItem()
            publishLiveState(context, glanceId, controller)
        }
    }
}

class WidgetNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetNextAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            controller.seekToNextMediaItem()
            publishLiveState(context, glanceId, controller)
        }
    }
}

/** Toggles shuffle on whatever queue is currently playing. */
class WidgetShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource()
        if (source == null) {
            Log.w(TAG, "WidgetShuffleAction: missing source param")
            return
        }
        withWidgetController(context, source) { controller ->
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
            publishLiveState(context, glanceId, controller)
        }
    }
}
