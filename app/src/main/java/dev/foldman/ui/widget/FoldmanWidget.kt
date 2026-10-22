package dev.foldman.ui.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.foldman.MainActivity

/** Glance state keys. The tape + sliders are rendered files; text rides as strings. */
internal object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val TAPE_PATH = stringPreferencesKey("tape_path")
    val SLIDERS_PATH = stringPreferencesKey("sliders_path")
    val IS_PLAYING = booleanPreferencesKey("is_playing")
    val SHUFFLE = booleanPreferencesKey("shuffle")
    val HAS_NEXT = booleanPreferencesKey("has_next")
    val HAS_PREV = booleanPreferencesKey("has_prev")
    val SOURCE = stringPreferencesKey("source")
}

/** Fixed brushed-chrome palette, matching Garrett's widget mockup. */
private object Chrome {
    val root = Color(0xFFB7BCC2)
    val panel = Color(0xFF3D4A5E)
    val titleBox = Color(0xFF101216)
    val titleText = Color(0xFFFFFFFF)
    val artistText = Color(0xFF9AA0A8)
    val brandText = Color(0xFFEDEFF2)
    val brandSub = Color(0xFF8B95A1)
    val keycap = Color(0xFFD8DCE0)
    val keyBorder = Color(0xFF5F666D)
    val glyph = Color(0xFF2B2F34)
    val playGreen = Color(0xFF35A852)
    val pauseOrange = Color(0xFFE8821A)
    val label = Color(0xFF3A3F45)
    val counterBg = Color(0xFF0B0C0E)
    val resetBg = Color(0xFF2A2E34)
    val resetText = Color(0xFFC9CDD2)
}

/**
 * Foldman's deck on the home screen, in the Walkman idiom (Garrett's mockup):
 *
 * - Top: dark slate panel — left has the dark title box (track + artist) over
 *   the chrome FOLDMAN wordmark; right has the horizontal cassette window
 *   with twin reels, over the mechanical counter + RESET key.
 * - Bottom: five square metallic transport keys (PREV / PLAY / NEXT / PAUSE /
 *   STOP-EJECT) with small labels above each, plus the VOLUME/TONE slider
 *   cluster rendered as a visual indicator (taps open the app).
 *
 * Everything the updater writes (titles, tape/slider files) is read
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

@Composable
private fun WalkmanContent() {
    // The widget must never throw: Glance shows "Can't show content" for any
    // exception out of provideContent, so every risky read below degrades to
    // the idle rendering. (try/catch and runCatching are illegal around
    // @Composable invocations.) Glance always provides the state inside
    // provideContent, so the nullable read never throws in practice.
    val prefs: Preferences? = currentState()
    val title = prefs?.get(WidgetKeys.TITLE).orEmpty().ifBlank { "Foldman" }
    val artist = prefs?.get(WidgetKeys.ARTIST).orEmpty().ifBlank { "No tape loaded" }
    val sourceName = prefs?.get(WidgetKeys.SOURCE) ?: WidgetSource.LOCAL.name
    // Deliberately not remembered: the updater re-renders these files while
    // playing (reel ticks) and on volume changes, so they must be re-decoded
    // each time. A partially-written file decodes to null or zero-size; both
    // fall back gracefully, never a crash.
    val tape = prefs?.get(WidgetKeys.TAPE_PATH)?.let { path ->
        runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }
    val sliders = prefs?.get(WidgetKeys.SLIDERS_PATH)?.let { path ->
        runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }
    val openApp = actionStartActivity<MainActivity>()
    val params = actionParametersOf(SourceParam to sourceName)

    Column(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(26.dp)
            .background(ColorProvider(Chrome.root))
            .padding(10.dp),
    ) {
        // Top: dark slate panel.
        Column(
            modifier = GlanceModifier.fillMaxWidth()
                .cornerRadius(18.dp)
                .background(ColorProvider(Chrome.panel))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // Left: title box over the chrome wordmark.
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Box(
                        modifier = GlanceModifier.fillMaxWidth()
                            .cornerRadius(12.dp)
                            .background(ColorProvider(Chrome.titleBox))
                            .padding(10.dp)
                            .clickable(openApp),
                    ) {
                        Column {
                            Text(
                                title,
                                style = TextStyle(
                                    color = ColorProvider(Chrome.titleText),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 2,
                            )
                            Spacer(GlanceModifier.height(2.dp))
                            Text(
                                artist,
                                style = TextStyle(
                                    color = ColorProvider(Chrome.artistText),
                                    fontSize = 12.sp
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(GlanceModifier.height(10.dp))
                    Text(
                        "FOLDMAN",
                        style = TextStyle(
                            color = ColorProvider(Chrome.brandText),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        "F-8 STEREO CASSETTE PLAYER",
                        style = TextStyle(
                            color = ColorProvider(Chrome.brandSub),
                            fontSize = 9.sp,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.width(10.dp))
                // Right: cassette window over counter + RESET.
                Column {
                    Box(
                        modifier = GlanceModifier.width(168.dp)
                            .clickable(openApp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tape != null) {
                            Image(
                                provider = BitmapImageProvider(tape),
                                contentDescription = "Cassette",
                                modifier = GlanceModifier.fillMaxWidth(),
                            )
                        } else {
                            // Empty dark window until the updater renders the tape.
                            Box(
                                modifier = GlanceModifier.fillMaxWidth()
                                    .height(84.dp)
                                    .cornerRadius(10.dp)
                                    .background(ColorProvider(Chrome.titleBox)),
                                contentAlignment = Alignment.Center,
                            ) {}
                        }
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(6.dp)
                                .background(ColorProvider(Chrome.counterBg))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "0 0 0",
                                style = TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = 14.sp,
                                ),
                            )
                        }
                        Spacer(GlanceModifier.width(6.dp))
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(6.dp)
                                .background(ColorProvider(Chrome.resetBg))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clickable(openApp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "RESET",
                                style = TextStyle(
                                    color = ColorProvider(Chrome.resetText),
                                    fontSize = 10.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        // Bottom: transport keys + slider cluster.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportKey(
                GlanceModifier.defaultWeight(), "PREV", "◀◀", Chrome.glyph,
                actionRunCallback<WidgetPrevAction>(params),
            )
            TransportKey(
                GlanceModifier.defaultWeight(), "PLAY", "▶", Chrome.playGreen,
                actionRunCallback<WidgetPlayAction>(params),
            )
            TransportKey(
                GlanceModifier.defaultWeight(), "NEXT", "▶▶", Chrome.glyph,
                actionRunCallback<WidgetNextAction>(params),
            )
            TransportKey(
                GlanceModifier.defaultWeight(), "PAUSE", "⏸", Chrome.pauseOrange,
                actionRunCallback<WidgetPauseAction>(params),
            )
            TransportKey(
                GlanceModifier.defaultWeight(), "STOP-EJECT", "■", Chrome.glyph,
                actionRunCallback<WidgetStopAction>(params),
            )
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier.defaultWeight()
                    .clickable(openApp),
                contentAlignment = Alignment.Center,
            ) {
                if (sliders != null) {
                    Image(
                        provider = BitmapImageProvider(sliders),
                        contentDescription = "Volume and tone",
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * One square metallic transport key with its small label above: a dark
 * border box holding the silver cap, glyph centered.
 */
@Composable
private fun TransportKey(
    modifier: GlanceModifier,
    label: String,
    glyph: String,
    glyphColor: Color,
    action: Action,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = TextStyle(
                color = ColorProvider(Chrome.label),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(3.dp))
        Box(
            modifier = GlanceModifier
                .cornerRadius(12.dp)
                .background(ColorProvider(Chrome.keyBorder))
                .padding(2.dp)
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(52.dp)
                    .cornerRadius(10.dp)
                    .background(ColorProvider(Chrome.keycap)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph,
                    style = TextStyle(
                        color = ColorProvider(glyphColor),
                        fontSize = 22.sp,
                    ),
                )
            }
        }
    }
}
