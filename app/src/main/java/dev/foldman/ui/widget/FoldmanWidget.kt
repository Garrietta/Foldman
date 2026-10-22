package dev.foldman.ui.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.glance.currentState
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.foldman.MainActivity

/** Glance state keys. Colors ride as ARGB longs; the tape is a rendered file. */
internal object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val TAPE_PATH = stringPreferencesKey("tape_path")
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
 * Foldman's deck on the home screen, in the Walkman idiom:
 *
 * - Top row: the song title up top and prominent, artist beneath it, and the
 *   cassette window at the top right — a mini tape whose label shows the
 *   album art and whose reels turn while a song is playing.
 * - Bottom row: four key caps (previous, play/pause, next, shuffle) spanning
 *   the full width, with engraved captions, in the deck-key style.
 *
 * Everything is tinted by the [RusticColors] derived from the album art.
 */
class FoldmanWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WalkmanContent() }
    }
}

private fun Preferences.rustic(): RusticColors {
    fun c(key: Preferences.Key<Long>, fallback: Color) =
        // The longs are stored as color.toArgb().toLong() (sign-extended ARGB).
        // Read them back through the Int constructor: Color(ULong) expects a
        // packed colorspace value in the high bits, and misreading ARGB as
        // packed crashes Glance's background translation with
        // ArrayIndexOutOfBoundsException in Color.getColorSpace (seen in the
        // 2026-10-22 bug report: "Error in Glance App Widget", index=38).
        get(key)?.let { Color(it.toInt()) } ?: fallback
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
    // The widget must never throw: Glance shows "Can't show content" for any
    // exception out of provideContent, so every other risky read below
    // degrades to the idle "No tape loaded" rendering.
    // Glance always provides the state inside provideContent, so this never
    // throws in practice. (try/catch and runCatching are illegal around
    // @Composable invocations.) Empty state still degrades to "No tape loaded".
    val prefs: Preferences? = currentState()
    val colors = remember(prefs) { prefs?.rustic() ?: RusticColors.Fallback }
    val title = prefs?.get(WidgetKeys.TITLE).orEmpty().ifBlank { "Foldman" }
    val artist = prefs?.get(WidgetKeys.ARTIST).orEmpty().ifBlank { "No tape loaded" }
    val isPlaying = prefs?.get(WidgetKeys.IS_PLAYING) == true
    val shuffleOn = prefs?.get(WidgetKeys.SHUFFLE) == true
    val sourceName = prefs?.get(WidgetKeys.SOURCE) ?: WidgetSource.LOCAL.name
    // Deliberately not remembered: the updater re-renders the tape file on
    // every reel tick while playing, so it must be re-decoded each time. A
    // partially-written file (ticker rewrite race) decodes to null or a
    // zero-size bitmap; both fall back to the note glyph, never a crash.
    val tape = prefs?.get(WidgetKeys.TAPE_PATH)?.let { path ->
        runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }
    val openApp = actionStartActivity<MainActivity>()
    val params = actionParametersOf(SourceParam to sourceName)

    Column(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(28.dp)
            .background(ColorProvider(colors.background))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // Top row: title block on the left, cassette window top right.
        Row(
            modifier = GlanceModifier.defaultWeight()
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier.defaultWeight()
                    .clickable(openApp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    artist,
                    style = TextStyle(color = ColorProvider(colors.subtitle), fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier.size(52.dp)
                    .cornerRadius(13.dp)
                    .clickable(openApp),
                contentAlignment = Alignment.Center,
            ) {
                if (tape != null) {
                    Image(
                        provider = BitmapImageProvider(tape),
                        contentDescription = "Cassette",
                        modifier = GlanceModifier.fillMaxSize(),
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
        }
        Spacer(GlanceModifier.height(4.dp))
        // Bottom row: the keys span the full width.
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            DeckWidgetKey(
                GlanceModifier.defaultWeight(), "PREV", "⏮", colors,
                accent = false, latched = false,
                actionRunCallback<WidgetPrevAction>(params),
            )
            DeckWidgetKey(
                GlanceModifier.defaultWeight(),
                if (isPlaying) "PAUSE" else "PLAY",
                if (isPlaying) "⏸" else "▶",
                colors, accent = true, latched = false,
                actionRunCallback<WidgetPlayPauseAction>(params),
            )
            DeckWidgetKey(
                GlanceModifier.defaultWeight(), "NEXT", "⏭", colors,
                accent = false, latched = false,
                actionRunCallback<WidgetNextAction>(params),
            )
            DeckWidgetKey(
                GlanceModifier.defaultWeight(), "SHUF", "⇄", colors,
                accent = false, latched = shuffleOn,
                actionRunCallback<WidgetShuffleAction>(params),
            )
        }
    }
}

/**
 * One deck key: an engraved caption over a rounded metal cap with its symbol.
 * Glance cannot draw the canvas key, so the cap is a rounded box in the
 * rustic keycap tone; the play key and a latched shuffle wear the accent.
 */
@Composable
private fun DeckWidgetKey(
    modifier: GlanceModifier,
    caption: String,
    symbol: String,
    colors: RusticColors,
    accent: Boolean,
    latched: Boolean,
    action: Action,
) {
    val hot = accent || latched
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            caption,
            style = TextStyle(color = ColorProvider(colors.subtitle), fontSize = 7.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Box(
            modifier = GlanceModifier.fillMaxWidth()
                .height(28.dp)
                .cornerRadius(9.dp)
                .background(ColorProvider(if (hot) colors.accent else colors.keycap))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                symbol,
                style = TextStyle(
                    color = ColorProvider(if (hot) colors.title else colors.symbol),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}
