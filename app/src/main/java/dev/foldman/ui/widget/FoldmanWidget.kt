package dev.foldman.ui.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.currentState
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.foldman.MainActivity

/** Glance state keys. Colors ride as ARGB longs. */
internal object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val ART_PATH = stringPreferencesKey("art_path")
    val IS_PLAYING = booleanPreferencesKey("is_playing")
    val SHUFFLE = booleanPreferencesKey("shuffle")
    val HAS_NEXT = booleanPreferencesKey("has_next")
    val HAS_PREV = booleanPreferencesKey("has_prev")
    val SOURCE = stringPreferencesKey("source")
    val BG = longPreferencesKey("c_bg")
    val KEYCAP = longPreferencesKey("c_keycap")
    val SYMBOL = longPreferencesKey("c_symbol")
    val ACCENT = longPreferencesKey("c_accent")
    val TITLE_C = longPreferencesKey("c_title")
    val SUBTITLE_C = longPreferencesKey("c_subtitle")
}

/**
 * Foldman's deck on the home screen: one compact horizontal row in the
 * Walkman idiom — artwork tile, title/artist, and four key caps (previous,
 * play/pause, next, shuffle) with engraved captions, all tinted by the
 * [RusticColors] derived from the album art.
 */
class FoldmanWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WalkmanContent() }
    }
}

private fun Preferences.rustic(): RusticColors {
    fun c(key: androidx.datastore.preferences.core.Preferences.Key<Long>, fallback: Color) =
        get(key)?.let { Color(it.toULong()) } ?: fallback
    val f = RusticColors.Fallback
    return RusticColors(
        background = c(WidgetKeys.BG, f.background),
        keycap = c(WidgetKeys.KEYCAP, f.keycap),
        symbol = c(WidgetKeys.SYMBOL, f.symbol),
        accent = c(WidgetKeys.ACCENT, f.accent),
        title = c(WidgetKeys.TITLE_C, f.title),
        subtitle = c(WidgetKeys.SUBTITLE_C, f.subtitle),
    )
}

@Composable
private fun WalkmanContent() {
    val prefs = currentState<Preferences>()
    val colors = remember(prefs) { prefs.rustic() }
    val title = prefs[WidgetKeys.TITLE].orEmpty().ifBlank { "Foldman" }
    val artist = prefs[WidgetKeys.ARTIST].orEmpty().ifBlank { "No tape loaded" }
    val isPlaying = prefs[WidgetKeys.IS_PLAYING] == true
    val shuffleOn = prefs[WidgetKeys.SHUFFLE] == true
    val sourceName = prefs[WidgetKeys.SOURCE] ?: WidgetSource.LOCAL.name
    val artPath = prefs[WidgetKeys.ART_PATH]
    val artwork = remember(artPath) {
        artPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    val openApp = actionStartActivity<MainActivity>()
    val params = actionParametersOf(SourceParam to sourceName)

    Row(
        modifier = GlanceModifier.fillMaxSize()
            .padding(6.dp)
            .cornerRadius(28.dp)
            .background(ColorProvider(colors.background))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork tile; tap opens the app.
        Box(
            modifier = GlanceModifier.size(62.dp)
                .cornerRadius(18.dp)
                .background(ColorProvider(colors.keycap.copy(alpha = 0.28f)))
                .clickable(openApp),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    provider = BitmapImageProvider(artwork),
                    contentDescription = "Album art",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "♪",
                    style = TextStyle(
                        color = ColorProvider(colors.subtitle),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        // Title / artist; tap opens the app.
        Column(
            modifier = GlanceModifier.defaultWeight()
                .padding(horizontal = 10.dp)
                .clickable(openApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = TextStyle(
                    color = ColorProvider(colors.title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                artist,
                style = TextStyle(color = ColorProvider(colors.subtitle), fontSize = 11.sp),
                maxLines = 1,
            )
        }
        // The keys: prev, play/pause (accent, like the deck), next, shuffle.
        DeckWidgetKey("PREV", "⏮", colors, false, false, actionRunCallback<WidgetPrevAction>(params))
        DeckWidgetKey(
            if (isPlaying) "PAUSE" else "PLAY",
            if (isPlaying) "⏸" else "▶",
            colors, accent = true, latched = false,
            actionRunCallback<WidgetPlayPauseAction>(params),
        )
        DeckWidgetKey("NEXT", "⏭", colors, false, false, actionRunCallback<WidgetNextAction>(params))
        DeckWidgetKey("SHUF", "⇄", colors, false, shuffleOn, actionRunCallback<WidgetShuffleAction>(params))
    }
}

/**
 * One deck key: an engraved caption over a rounded metal cap with its symbol.
 * Glance cannot draw the canvas key, so the cap is a rounded box in the
 * rustic keycap tone; the play key and a latched shuffle wear the accent.
 */
@Composable
private fun DeckWidgetKey(
    caption: String,
    symbol: String,
    colors: RusticColors,
    accent: Boolean,
    latched: Boolean,
    action: Action,
) {
    val hot = accent || latched
    Column(
        modifier = GlanceModifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            caption,
            style = TextStyle(color = ColorProvider(colors.subtitle), fontSize = 7.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(3.dp))
        Box(
            modifier = GlanceModifier.size(44.dp, 40.dp)
                .cornerRadius(13.dp)
                .background(ColorProvider(if (hot) colors.accent else colors.keycap))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                symbol,
                style = TextStyle(
                    color = ColorProvider(if (hot) colors.title else colors.symbol),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}
