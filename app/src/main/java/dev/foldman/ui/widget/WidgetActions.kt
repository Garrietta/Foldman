package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.foldman.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * Bind a throwaway [MediaController] to the active session, run [block] on the
 * main thread, and release it again. Building the controller starts a stopped
 * service, so actions work with the app closed; anything that fails returns
 * null and the tap is silently dropped.
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
        controller = withTimeoutOrNull(timeoutMs) { future.await() } ?: return@withContext null
        block(controller)
    } catch (e: Exception) {
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
        val source = parameters.widgetSource() ?: return
        withWidgetController(context, source) { controller ->
            // Decide from live state, not the last render: buffering counts as
            // playing, so a second tap during spin-up stops instead of
            // queueing a redundant play.
            if (controller.playWhenReady || controller.playbackState == Player.STATE_BUFFERING) {
                controller.pause()
            } else {
                controller.play()
            }
            publishLiveState(context, glanceId, controller)
        }
    }
}

class WidgetPrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource() ?: return
        withWidgetController(context, source) { controller ->
            controller.seekToPreviousMediaItem()
            publishLiveState(context, glanceId, controller)
        }
    }
}

class WidgetNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource() ?: return
        withWidgetController(context, source) { controller ->
            controller.seekToNextMediaItem()
            publishLiveState(context, glanceId, controller)
        }
    }
}

/** Toggles shuffle on whatever queue is currently playing. */
class WidgetShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val source = parameters.widgetSource() ?: return
        withWidgetController(context, source) { controller ->
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
            publishLiveState(context, glanceId, controller)
        }
    }
}
