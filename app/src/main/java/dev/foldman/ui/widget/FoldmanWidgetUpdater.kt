package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import dev.foldman.deck.Transport
import dev.foldman.ui.ReelMotion
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the widget shows. Built by the caller from the deck state; the widget
 * stays decoupled from [dev.foldman.deck.DeckUi] so this file never churns
 * with UI refactors.
 */
data class WidgetSnapshot(
    val title: String,
    val artist: String,
    /** Retained for pipeline compat; the mockup cassette is a fixed-chrome graphic. */
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    /** Retained for pipeline compat; this widget design has no shuffle key. */
    val shuffleOn: Boolean,
    val activeSource: WidgetSource,
    /** Track duration in ms; lets the ticker integrate tape progress while playing. */
    val durationMs: Long = 0L,
    /** 0..1 system music volume; drives the VOLUME slider indicator. */
    val volume: Float = 0.5f,
    /** 0-based index of the now-playing track in the queue; drives the counter. */
    val queueIndex: Int = 0,
    /**
     * Stable identity of the now-playing queue entry (BackendState.currentQueueId).
     * The updater resets the reel angles to 0 only when this changes — a
     * pause/resume keeps the frozen angles.
     */
    val trackId: Long = -1,
)

/**
 * Pushes a [WidgetSnapshot] to every installed Foldman widget: renders the
 * vertical cassette (each reel at its own physics-model angle) to a file the
 * widget reads back in [FoldmanWidget.provideGlance], stores everything in
 * Glance state, and redraws.
 *
 * The reels run the SAME pack-radius physics as the deck cassette
 * ([ReelMotion]: ω = v/r per reel, counter-clockwise in play, spin-up /
 * coast-down easing). While a snapshot reports playing, a ticker re-pushes
 * every [TICK_SECONDS] and integrates the model, so the widget wheels turn
 * exactly like the app's. The tick is 120ms: at ~278°/s (empty pack) that's
 * ~33° per tick — smooth enough for the 3-spoke reels without strobing, and
 * ~8 widget updates/sec while playing.
 *
 * Pause stops the ticker and freezes both reel angles; resume spins back up
 * from rest. The angles reset to 0 only when [WidgetSnapshot.trackId]
 * changes (a new song/audio file starts).
 */
object FoldmanWidgetUpdater {

    /** Widget reel ticker interval, in seconds. */
    private const val TICK_SECONDS = 0.12f

    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var lastSnapshot: WidgetSnapshot? = null
    private var lastIsPlaying: Boolean? = null
    /** Physics model owned here; survives pause/resume, fresh on track change. */
    private var reels = ReelMotion()
    /** Tape progress the model is drawn at; integrated by the ticker. */
    private var currentProgress = 0f
    private var lastTrackId: Long? = null

    suspend fun update(context: Context, snapshot: WidgetSnapshot) {
        val appContext = context.applicationContext
        if (snapshot.trackId != lastTrackId) {
            // New song/audio file: fresh model, spinners restart from 0.
            lastTrackId = snapshot.trackId
            reels = ReelMotion()
            currentProgress = 0f
        } else if (snapshot.isPlaying && lastIsPlaying != true) {
            // Resume from pause: spin the reels back up from rest, keeping
            // the frozen angles.
            reels.resetTapeSpeed()
        }
        lastIsPlaying = snapshot.isPlaying
        lastSnapshot = snapshot
        push(appContext, snapshot)
        if (snapshot.isPlaying) startTicker(appContext) else stopTicker()
    }

    /** Stops the reel ticker, e.g. when the app is backgrounded for good. */
    fun stop() {
        stopTicker()
        lastSnapshot = null
        lastIsPlaying = null
    }

    private suspend fun push(appContext: Context, snapshot: WidgetSnapshot) {
        val ids = appWidgetIds(appContext)
        if (ids.isEmpty()) {
            stopTicker()
            return
        }
        val tapePath = writeTape(appContext, reels.leftAngle, reels.rightAngle)
        val artworkPath = writeArtwork(appContext, snapshot)
        val keyPaths = writeKeys(appContext)
        val widget = FoldmanWidget()
        val manager = GlanceAppWidgetManager(appContext)
        for (appWidgetId in ids) {
            val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull()
                ?: continue
            updateAppWidgetState(appContext, glanceId) { state ->
                state[WidgetKeys.TITLE] = snapshot.title
                state[WidgetKeys.ARTIST] = snapshot.artist
                if (tapePath != null) state[WidgetKeys.TAPE_PATH] = tapePath
                else state.remove(WidgetKeys.TAPE_PATH)
                if (artworkPath != null) state[WidgetKeys.ARTWORK_PATH] = artworkPath
                else state.remove(WidgetKeys.ARTWORK_PATH)
                if (keyPaths != null) state[WidgetKeys.KEY_PATHS] = keyPaths
                else state.remove(WidgetKeys.KEY_PATHS)
                state[WidgetKeys.IS_PLAYING] = snapshot.isPlaying
                state[WidgetKeys.SOURCE] = snapshot.activeSource.name
                state[WidgetKeys.QUEUE_INDEX] = snapshot.queueIndex
                // Recompose triggers; the rotation itself is baked into the tape PNG.
                state[WidgetKeys.REEL_ANGLE_LEFT] = reels.leftAngle
                state[WidgetKeys.REEL_ANGLE_RIGHT] = reels.rightAngle
            }
            runCatching { widget.update(appContext, glanceId) }
        }
    }

    private fun startTicker(context: Context) {
        if (tickerJob?.isActive == true) return
        tickerJob = tickerScope.launch {
            while (isActive) {
                delay((TICK_SECONDS * 1000).toLong())
                try {
                    val snap = lastSnapshot ?: break
                    if (!snap.isPlaying) break
                    val dt = TICK_SECONDS
                    val dur = snap.durationMs
                    if (dur > 0) {
                        currentProgress =
                            (currentProgress + dt * 1000f / dur).coerceIn(0f, 1f)
                    }
                    reels.step(dt, Transport.PLAYING, currentProgress)
                    push(context, snap)
                } catch (e: Exception) {
                    // Keep the ticker alive; a transient failure shouldn't kill rotation.
                    continue
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun appWidgetIds(context: Context): IntArray =
        runCatching {
            android.appwidget.AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FoldmanWidgetReceiver::class.java))
        }.getOrNull() ?: IntArray(0)

    private fun writeArtwork(context: Context, snapshot: WidgetSnapshot): String? =
        runCatching {
            val art = snapshot.artwork ?: return@runCatching null
            val file = File(context.filesDir, "foldman_widget_artwork.png")
            FileOutputStream(file).use { out ->
                art.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()

    private fun writeKeys(context: Context): String? = runCatching {
        val symbols = listOf("prev", "play", "next", "pause", "stop")
        val paths = symbols.map { sym ->
            val bmp = CassetteRenderer.renderTransportKey(sym, 144)
            val file = File(context.filesDir, "foldman_widget_key_$sym.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }
        paths.joinToString("|")
    }.getOrNull()

    private fun writeTape(
        context: Context,
        leftAngleDeg: Float,
        rightAngleDeg: Float,
    ): String? = runCatching {
        // Aspect matches the navy panel's inner box (112:188 design dp), so
        // ContentScale.Fit fills the panel width and the panel's uniform 8dp
        // padding becomes the thin navy border on all four sides.
        val tape = CassetteRenderer.renderVerticalCassette(leftAngleDeg, rightAngleDeg, 336, 564)
        val file = File(context.filesDir, "foldman_widget_tape.png")
        FileOutputStream(file).use { out ->
            tape.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        if (!tape.isRecycled) tape.recycle()
        file.absolutePath
    }.getOrNull()

}
