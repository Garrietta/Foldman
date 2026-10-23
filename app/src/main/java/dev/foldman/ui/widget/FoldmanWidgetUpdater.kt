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
 * exactly like the app's. The tick is 50ms: at ~278°/s (empty pack) that's
 * ~14° per tick, ~6.5° at a full pack — under the ~15° choppiness threshold
 * for the 3-spoke reels, so the motion reads smooth. That's ~20 widget
 * updates/sec, but only while playing; pause stops the ticker.
 *
 * Pause stops the ticker and freezes both reel angles; resume spins back up
 * from rest. The angles reset to 0 only when [WidgetSnapshot.trackId]
 * changes (a new song/audio file starts).
 */
object FoldmanWidgetUpdater {

    /** Widget reel ticker interval, in seconds. 50ms keeps the fastest reel under ~14°/tick. */
    private const val TICK_SECONDS = 0.05f

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
        // The tape is the only per-tick render, and it's written atomically
        // (temp file + rename) so the widget never reads a half-written PNG —
        // that's what made it flash while playing. Keys and chrome are static
        // and written once. (The separate artwork file was decoded but never
        // displayed; the cassette renderer takes the artwork bitmap directly.)
        val tapePath = writeTape(appContext, snapshot)
        val (keyPathsUp, keyPathsDown) = writeKeys(appContext)
        val chromePath = writeChrome(appContext)
        val titlePanelPath = writeTitlePanel(appContext)
        val bluePanelPath = writeBluePanel(appContext)
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
                if (keyPathsUp != null) state[WidgetKeys.KEY_PATHS_UP] = keyPathsUp
                else state.remove(WidgetKeys.KEY_PATHS_UP)
                if (keyPathsDown != null) state[WidgetKeys.KEY_PATHS_DOWN] = keyPathsDown
                else state.remove(WidgetKeys.KEY_PATHS_DOWN)
                if (chromePath != null) state[WidgetKeys.CHROME_PATH] = chromePath
                else state.remove(WidgetKeys.CHROME_PATH)
                if (titlePanelPath != null) state[WidgetKeys.TITLE_PANEL_PATH] = titlePanelPath
                else state.remove(WidgetKeys.TITLE_PANEL_PATH)
                if (bluePanelPath != null) state[WidgetKeys.BLUE_PANEL_PATH] = bluePanelPath
                else state.remove(WidgetKeys.BLUE_PANEL_PATH)
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

    /** Atomic PNG write: temp file + rename, so readers never see a torn frame. */
    private fun writePngAtomic(file: File, bmp: Bitmap): Boolean = runCatching {
        val tmp = File(file.parent, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        tmp.renameTo(file)
    }.getOrDefault(false)

    private fun writeKeys(context: Context): Pair<String?, String?> {
        val symbols = listOf("prev", "play", "next", "pause")
        val ups = mutableListOf<String>()
        val downs = mutableListOf<String>()
        for (sym in symbols) {
            for ((state, pressed) in listOf("up" to false, "down" to true)) {
                // Versioned filename: a redesign re-renders once, then it's cached.
                // v5: corrected prev/next glyphs + stronger bevel/borders.
                val file = File(context.filesDir, "foldman_widget_key_v5_${sym}_$state.png")
                if (!file.exists()) {
                    val bmp = CassetteRenderer.renderTransportKey(sym, 144, pressed)
                    writePngAtomic(file, bmp)
                    bmp.recycle()
                }
                if (pressed) downs.add(file.absolutePath) else ups.add(file.absolutePath)
            }
        }
        return ups.joinToString("|") to downs.joinToString("|")
    }

    private fun writeTape(context: Context, snapshot: WidgetSnapshot): String? = runCatching {
        // Aspect matches the blue panel's inner box (112:188 design dp), so
        // ContentScale.Fit fills the panel width and the panel's uniform 8dp
        // padding becomes the thin blue border on all four sides.
        val tape = CassetteRenderer.renderVerticalTapeFace(
            title = snapshot.title,
            artist = snapshot.artist,
            artwork = snapshot.artwork,
            source = snapshot.activeSource,
            leftAngleDeg = reels.leftAngle,
            rightAngleDeg = reels.rightAngle,
            leftRadiusMm = reels.leftRadius,
            rightRadiusMm = reels.rightRadius,
            leftDeltaDeg = reels.leftDelta,
            rightDeltaDeg = reels.rightDelta,
            widthPx = 336,
            heightPx = 564,
        )
        val file = File(context.filesDir, "foldman_widget_tape.png")
        val ok = writePngAtomic(file, tape)
        tape.recycle()
        if (ok) file.absolutePath else null
    }.getOrNull()

    /**
     * Renders the brushed-chrome root background once (versioned filename so
     * design changes are picked up). Static — never re-rendered per tick.
     */
    private fun writeChrome(context: Context): String? = runCatching {
        val file = File(context.filesDir, "foldman_widget_chrome_v1.png")
        if (!file.exists()) {
            // 2x the 440x200 design size; 26dp corner radius -> 52px.
            val chrome = CassetteRenderer.renderBrushedPanel(880, 400, 52f)
            FileOutputStream(file).use { out ->
                chrome.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (!chrome.isRecycled) chrome.recycle()
        }
        file.absolutePath
    }.getOrNull()

    /**
     * Renders the inset title panel (brushed silver + inner shadow) once.
     * Design size: ~294dp x ~94dp at 2x; 16dp corner radius -> 32px.
     */
    private fun writeTitlePanel(context: Context): String? = runCatching {
        val file = File(context.filesDir, "foldman_widget_title_panel_v1.png")
        if (!file.exists()) {
            val panel = CassetteRenderer.renderTitlePanel(588, 188, 32f)
            val ok = writePngAtomic(file, panel)
            if (!panel.isRecycled) panel.recycle()
            if (!ok) return@runCatching null
        }
        file.absolutePath
    }.getOrNull()

    /**
     * Renders the enamel-blue tape panel (richer gradient + edge shadow)
     * once. Design size: 128dp x 188dp at 2x; 14dp corner radius -> 28px.
     */
    private fun writeBluePanel(context: Context): String? = runCatching {
        val file = File(context.filesDir, "foldman_widget_blue_panel_v1.png")
        if (!file.exists()) {
            val panel = CassetteRenderer.renderBluePanel(256, 376, 28f)
            val ok = writePngAtomic(file, panel)
            if (!panel.isRecycled) panel.recycle()
            if (!ok) return@runCatching null
        }
        file.absolutePath
    }.getOrNull()

}
