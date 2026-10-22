package dev.foldman.ui.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import java.io.File
import java.io.FileOutputStream

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
)

/**
 * Pushes a [WidgetSnapshot] to every installed Foldman widget: derives the
 * rustic palette from the artwork (downscaled first — Glance state and the
 * Binder transaction punish full-size bitmaps), writes the art to a file the
 * widget reads back in [FoldmanWidget.provideGlance], stores everything in
 * Glance state, and redraws.
 */
object FoldmanWidgetUpdater {

    suspend fun update(context: Context, snapshot: WidgetSnapshot) {
        val appContext = context.applicationContext
        val ids = appWidgetIds(appContext)
        if (ids.isEmpty()) return
        val colors = RusticColors.from(snapshot.artwork)
        val artPath = snapshot.artwork?.let { writeArtwork(appContext, it) }
        val widget = FoldmanWidget()
        val manager = GlanceAppWidgetManager(appContext)
        for (appWidgetId in ids) {
            val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull()
                ?: continue
            updateAppWidgetState(appContext, glanceId) { state ->
                state[WidgetKeys.TITLE] = snapshot.title
                state[WidgetKeys.ARTIST] = snapshot.artist
                if (artPath != null) state[WidgetKeys.ART_PATH] = artPath
                else state.remove(WidgetKeys.ART_PATH)
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
    }

    private fun appWidgetIds(context: Context): IntArray =
        runCatching {
            android.appwidget.AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FoldmanWidgetReceiver::class.java))
        }.getOrNull() ?: IntArray(0)

    private fun writeArtwork(context: Context, art: Bitmap): String? = runCatching {
        val scaled = Bitmap.createScaledBitmap(art, 192, 192, true)
        val file = File(context.filesDir, "foldman_widget_art.png")
        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        file.absolutePath
    }.getOrNull()
}
