package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
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
    /** Reel angle for the cassette; the ticker advances it while playing. */
    val reelAngleDeg: Float = 0f,
    /** 0..1 system music volume; drives the VOLUME slider indicator. */
    val volume: Float = 0.5f,
    /** 0-based index of the now-playing track in the queue; drives the counter. */
    val queueIndex: Int = 0,
)

/**
 * Pushes a [WidgetSnapshot] to every installed Foldman widget: renders the
 * horizontal cassette (reels at [WidgetSnapshot.reelAngleDeg]) and the
 * VOLUME/TONE slider cluster to files the widget reads back in
 * [FoldmanWidget.provideGlance], stores everything in Glance state, and
 * redraws.
 *
 * While a snapshot reports playing, a lightweight ticker re-pushes every
 * ~750ms with the reel angle advanced 10 degrees clockwise (slow — a full
 * revolution takes ~27s), which animates the cassette's reels. The ticker
 * stops on pause (reels freeze at current angle) and whenever a fresh snapshot
 * arrives with [WidgetSnapshot.isPlaying] false.
 */
object FoldmanWidgetUpdater {

    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var lastSnapshot: WidgetSnapshot? = null

    suspend fun update(context: Context, snapshot: WidgetSnapshot) {
        val appContext = context.applicationContext
        val ids = appWidgetIds(appContext)
        lastSnapshot = snapshot
        if (ids.isEmpty()) {
            stopTicker()
            return
        }
        val tapePath = writeTape(appContext, snapshot)
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
            }
            runCatching { widget.update(appContext, glanceId) }
        }
        if (snapshot.isPlaying) startTicker(appContext) else stopTicker()
    }

    /** Stops the reel ticker, e.g. when the app is backgrounded for good. */
    fun stop() {
        stopTicker()
        lastSnapshot = null
    }

    private fun startTicker(context: Context) {
        if (tickerJob?.isActive == true) return
        tickerJob = tickerScope.launch {
            while (isActive) {
                delay(750)
                try {
                    val snap = lastSnapshot ?: break
                    if (!snap.isPlaying) break
                    update(context, snap.copy(reelAngleDeg = (snap.reelAngleDeg + 10f) % 360f))
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
        snapshot: WidgetSnapshot,
    ): String? = runCatching {
        val tape = CassetteRenderer.renderVerticalCassette(snapshot.reelAngleDeg, 200, 640)
        val file = File(context.filesDir, "foldman_widget_tape.png")
        FileOutputStream(file).use { out ->
            tape.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        if (!tape.isRecycled) tape.recycle()
        file.absolutePath
    }.getOrNull()

}
