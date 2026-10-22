package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
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
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val shuffleOn: Boolean,
    val activeSource: WidgetSource,
    /** Reel angle for the cassette window; the ticker advances it while playing. */
    val reelAngleDeg: Float = 0f,
)

/**
 * Pushes a [WidgetSnapshot] to every installed Foldman widget: derives the
 * rustic palette from the artwork, renders the cassette window (label art +
 * reels at [WidgetSnapshot.reelAngleDeg]) to a file the widget reads back in
 * [FoldmanWidget.provideGlance], stores everything in Glance state, and
 * redraws.
 *
 * While a snapshot reports playing, a lightweight ticker re-pushes every
 * ~750ms with the reel angle advanced 40 degrees, which animates the
 * cassette's reels. The ticker stops on pause and whenever a fresh snapshot
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
        val colors = RusticColors.from(snapshot.artwork)
        val tapePath = writeTape(appContext, colors, snapshot)
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
                state[WidgetKeys.IS_PLAYING] = snapshot.isPlaying
                state[WidgetKeys.SHUFFLE] = snapshot.shuffleOn
                state[WidgetKeys.SOURCE] = snapshot.activeSource.name
                state[WidgetKeys.BG] = colors.background.toArgb().toLong()
                state[WidgetKeys.KEYCAP] = colors.keycap.toArgb().toLong()
                state[WidgetKeys.SYMBOL] = colors.symbol.toArgb().toLong()
                state[WidgetKeys.ACCENT] = colors.accent.toArgb().toLong()
                state[WidgetKeys.TITLE_C] = colors.title.toArgb().toLong()
                state[WidgetKeys.SUBTITLE_C] = colors.subtitle.toArgb().toLong()
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
                val snap = lastSnapshot ?: break
                if (!snap.isPlaying) break
                update(context, snap.copy(reelAngleDeg = (snap.reelAngleDeg + 40f) % 360f))
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

    private fun writeTape(
        context: Context,
        colors: RusticColors,
        snapshot: WidgetSnapshot,
    ): String? = runCatching {
        val tape = CassetteRenderer.render(colors, snapshot.artwork, snapshot.reelAngleDeg, 220)
        val file = File(context.filesDir, "foldman_widget_tape.png")
        FileOutputStream(file).use { out ->
            tape.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        if (!tape.isRecycled) tape.recycle()
        file.absolutePath
    }.getOrNull()
}
