package dev.foldman.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.BitmapImageProvider
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.foldman.MainActivity
import dev.foldman.R

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
 * - Left: the song title and artist pinned to the top, then four icon-only
 *   pill controls (previous, play/pause, next, shuffle).
 * - Right: the cassette window grown to fill the widget's full height — the
 *   visual anchor, its reels turning while a song plays.
 *
 * Theming is Material You: the background and pill containers are read from
 * the system's dynamic color palette (wallpaper-derived, API 31+), with the
 * play/pause pill wearing the contrasting dynamic accent container.
 * Playback glyphs are Material Symbols vectors with one uniform tint, so no
 * emoji-font variance across devices.
 *
 * Everything the updater writes (titles, colors, tape file) is read
 * defensively: the widget must never throw, because Glance shows
 * "Can't show content" for any exception out of provideContent.
 */
class FoldmanWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WalkmanContent()
        }
    }
}

/**
 * The widget's Material You palette, read from the system's dynamic colors
 * (wallpaper-derived on API 31+). Below 31 there is no dynamic palette, so it
 * falls back to the static rustic deck tones.
 */
private data class WidgetPalette(
    val background: ColorProvider,
    val onBackground: ColorProvider,
    val surfaceVariant: ColorProvider,
    val onSurfaceVariant: ColorProvider,
    val primaryContainer: ColorProvider,
    val onSurface: ColorProvider,
)

@Composable
private fun dynamicPalette(): WidgetPalette {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT >= 31) {
            val dark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            fun sys(id: Int): ColorProvider = ColorProvider(Color(context.getColor(id)))
            if (dark) {
                WidgetPalette(
                    background = sys(android.R.color.system_neutral1_900),
                    onBackground = sys(android.R.color.system_neutral1_100),
                    surfaceVariant = sys(android.R.color.system_neutral2_700),
                    onSurfaceVariant = sys(android.R.color.system_neutral2_200),
                    primaryContainer = sys(android.R.color.system_accent1_700),
                    onSurface = sys(android.R.color.system_neutral1_100),
                )
            } else {
                WidgetPalette(
                    background = sys(android.R.color.system_neutral1_50),
                    onBackground = sys(android.R.color.system_neutral1_900),
                    surfaceVariant = sys(android.R.color.system_neutral2_100),
                    onSurfaceVariant = sys(android.R.color.system_neutral2_700),
                    primaryContainer = sys(android.R.color.system_accent1_100),
                    onSurface = sys(android.R.color.system_neutral1_900),
                )
            }
        } else {
            val f = RusticColors.Fallback
            WidgetPalette(
                background = ColorProvider(f.background),
                onBackground = ColorProvider(f.title),
                surfaceVariant = ColorProvider(f.keycap),
                onSurfaceVariant = ColorProvider(f.subtitle),
                primaryContainer = ColorProvider(f.accent),
                onSurface = ColorProvider(f.symbol),
            )
        }
    }
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
    val colors = dynamicPalette()
    // One uniform tint for every playback control icon.
    val iconTint = colors.onSurface

    Row(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(28.dp)
            .background(colors.background)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Left column: text block pinned to the top (aligned with the top of
        // the cassette), icon-only pills along the bottom.
        Column(
            modifier = GlanceModifier.defaultWeight()
                .fillMaxHeight(),
        ) {
            Column(
                modifier = GlanceModifier.clickable(openApp),
            ) {
                Text(
                    title,
                    style = TextStyle(
                        color = colors.onBackground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 2,
                )
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    artist,
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlPill(
                    GlanceModifier.defaultWeight(), R.drawable.ic_skip_previous,
                    colors.surfaceVariant, iconTint,
                    actionRunCallback<WidgetPrevAction>(params),
                )
                Spacer(GlanceModifier.width(6.dp))
                ControlPill(
                    GlanceModifier.defaultWeight(),
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    colors.primaryContainer, iconTint,
                    actionRunCallback<WidgetPlayPauseAction>(params),
                )
                Spacer(GlanceModifier.width(6.dp))
                ControlPill(
                    GlanceModifier.defaultWeight(), R.drawable.ic_skip_next,
                    colors.surfaceVariant, iconTint,
                    actionRunCallback<WidgetNextAction>(params),
                )
                Spacer(GlanceModifier.width(6.dp))
                ControlPill(
                    GlanceModifier.defaultWeight(), R.drawable.ic_shuffle,
                    if (shuffleOn) colors.primaryContainer else colors.surfaceVariant,
                    iconTint,
                    actionRunCallback<WidgetShuffleAction>(params),
                )
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        // Right: the cassette window, grown to fill the widget's full height.
        Box(
            modifier = GlanceModifier.width(92.dp)
                .fillMaxHeight()
                .cornerRadius(22.dp)
                .clickable(openApp),
            contentAlignment = Alignment.Center,
        ) {
            if (tape != null) {
                Image(
                    provider = BitmapImageProvider(tape),
                    contentDescription = "Cassette",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_music_note),
                    contentDescription = "No tape loaded",
                    modifier = GlanceModifier.size(40.dp),
                    colorFilter = ColorFilter.tint(colors.onSurfaceVariant),
                )
            }
        }
    }
}

/**
 * One icon-only pill control: a Material Symbols vector centered vertically
 * and horizontally in a rounded container. The play/pause pill (and a latched
 * shuffle) wear the contrasting dynamic primary-container; the rest sit on
 * surface-variant. Every icon shares one uniform tint.
 */
@Composable
private fun ControlPill(
    modifier: GlanceModifier,
    iconRes: Int,
    container: ColorProvider,
    iconTint: ColorProvider,
    action: Action,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .cornerRadius(24.dp)
            .background(container)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(iconTint),
        )
    }
}
