package dev.foldman.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.LocalSize
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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.foldman.MainActivity

/** Glance state keys. The tape + sliders are rendered files; text rides as strings. */
internal object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val TAPE_PATH = stringPreferencesKey("tape_path")
    val SLIDERS_PATH = stringPreferencesKey("sliders_path")
    val ARTWORK_PATH = stringPreferencesKey("artwork_path")
    val KEY_PATHS = stringPreferencesKey("key_paths")
    val IS_PLAYING = booleanPreferencesKey("is_playing")
    val SHUFFLE = booleanPreferencesKey("shuffle")
    val HAS_NEXT = booleanPreferencesKey("has_next")
    val HAS_PREV = booleanPreferencesKey("has_prev")
    val SOURCE = stringPreferencesKey("source")
    val QUEUE_INDEX = intPreferencesKey("queue_index")
    val PRESSED_KEY = stringPreferencesKey("pressed_key")
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
    val keyPressed = Color(0xFF3A3F45)
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

    /**
     * Exact size mode: the launcher re-requests content whenever the
     * allocated rect changes (including Samsung Good Lock's 110% scaling),
     * and LocalSize.current reports that exact rect. The face below scales
     * proportionally into it, so the transport keys can never be clipped.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WalkmanContent()
        }
    }
}

/**
 * The widget face's canonical design size (dp). The rendered face is this
 * design scaled by min(availW/DESIGN_W, availH/DESIGN_H) — aspect locked —
 * anchored top-left in the allocated rect. Every dimension and font below
 * derives from that single scale, so the layout fits any launcher rect by
 * construction instead of overflowing fixed-dp content.
 */
private const val DESIGN_W = 440f
private const val DESIGN_H = 200f

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
    // Transport key icons (vector-drawn, no emoji): paths joined by "|".
    val keyBitmaps: Map<String, android.graphics.Bitmap?> =
        prefs?.get(WidgetKeys.KEY_PATHS)?.split("|")?.let { paths ->
            mapOf(
                "prev" to paths.getOrNull(0),
                "play" to paths.getOrNull(1),
                "next" to paths.getOrNull(2),
                "pause" to paths.getOrNull(3),
                "stop" to paths.getOrNull(4),
            ).mapValues { (_, p) ->
                p?.let {
                    runCatching {
                        BitmapFactory.decodeFile(it)?.takeIf { b -> b.width > 0 && b.height > 0 }
                    }.getOrNull()
                }
            }
        } ?: emptyMap()
    val pressedKey = prefs?.get(WidgetKeys.PRESSED_KEY)
    // Album artwork for the dimmed backdrop layer.
    val artwork = prefs?.get(WidgetKeys.ARTWORK_PATH)?.let { path ->
        runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }
    val openApp = actionStartActivity<MainActivity>()
    val params = actionParametersOf(SourceParam to sourceName)

    // Aspect-locked fit: scale the whole face into the allocated rect and
    // anchor it top-left. The widget must never throw (Glance shows
    // "Can't show content" for any exception), so a bogus size degrades to
    // the unscaled design rather than crashing.
    val avail = LocalSize.current
    val rawScale = if (avail.width > 0.dp && avail.height > 0.dp) {
        minOf(avail.width.value / DESIGN_W, avail.height.value / DESIGN_H)
    } else {
        1f
    }
    val scale = if (rawScale.isFinite()) rawScale.coerceIn(0.3f, 3f) else 1f
    fun sd(v: Number) = (v.toFloat() * scale).dp
    fun ss(v: Number) = (v.toFloat() * scale).sp

    // The chrome fills the entire allocated rect (no letterbox); the panel
    // below absorbs any extra height via weight, while every element keeps
    // the min-fit scale, so nothing can clip. Grow and shrink both work:
    // the scale follows the rect.
    // Layout: left = title (top) + 4 keys (bottom); right = vertical tape.
    // STOP-EJECT and sliders removed.
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(sd(26))
            .background(ColorProvider(Chrome.root))
            .padding(sd(6)),
    ) {
        // Left: main content.
        Column(modifier = GlanceModifier.defaultWeight()) {
            // Dark top container: 50% of height (cut by 50% per spec).
            // Left half: square track-info (top) + counter/RESET (below).
            // Right half: cassette at 200% scale.
            Column(
                modifier = GlanceModifier.fillMaxWidth()
                    .defaultWeight()
                    .cornerRadius(sd(20))
                    .background(ColorProvider(Chrome.panel))
                    .padding(horizontal = sd(8), vertical = sd(12)),
            ) {
                    // Title box: expanded to fill the width (tape is vertical now).
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        Box(
                            modifier = GlanceModifier.fillMaxWidth()
                                .cornerRadius(sd(14))
                                .background(ColorProvider(Chrome.titleBox))
                                .padding(sd(10))
                                .clickable(openApp),
                        ) {
                            Column {
                                Text(
                                    title,
                                    style = TextStyle(
                                        color = ColorProvider(Chrome.titleText),
                                        fontSize = ss(16),
                                        fontWeight = FontWeight.Medium,
                                        
                                    ),
                                    maxLines = 2,
                                )
                                Spacer(GlanceModifier.height(sd(2)))
                                Text(
                                    artist,
                                    style = TextStyle(
                                        color = ColorProvider(Chrome.artistText),
                                        fontSize = ss(12)
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(GlanceModifier.height(sd(6)))
                    }
                    // Counter + RESET (below title).
                    Spacer(GlanceModifier.height(sd(6)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(sd(10))
                                .background(ColorProvider(Chrome.counterBg))
                                .padding(horizontal = sd(8), vertical = sd(4)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val qIdx = prefs?.get(WidgetKeys.QUEUE_INDEX) ?: 0
                            // 1-based, 4 digits: track 357 -> "0357".
                            val counterText = "%04d".format((qIdx + 1).coerceIn(1, 9999))
                                .let { it[0] + " " + it[1] + " " + it[2] + " " + it[3] }
                            Text(
                                counterText,
                                style = TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = ss(14),
                                ),
                            )
                        }
                        Spacer(GlanceModifier.width(sd(6)))
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(sd(10))
                                .background(ColorProvider(Chrome.resetBg))
                                .padding(horizontal = sd(10), vertical = sd(6))
                                .clickable(openApp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "RESET",
                                style = TextStyle(
                                    color = ColorProvider(Chrome.resetText),
                                    fontSize = ss(10),
                                ),
                            )
                        }
                    }
                }
            }
            // Bottom: transport keys + slider cluster, flush against the dark
            // container (zero gap per spec).
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportKey(
                    GlanceModifier.defaultWeight(), "PREV", keyBitmaps["prev"], scale,
                    actionRunCallback<WidgetPrevAction>(params),
                    isPressed = pressedKey == "prev",
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "PLAY", keyBitmaps["play"], scale,
                    actionRunCallback<WidgetPlayAction>(params),
                    isPressed = pressedKey == "play",
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "NEXT", keyBitmaps["next"], scale,
                    actionRunCallback<WidgetNextAction>(params),
                    isPressed = pressedKey == "next",
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "PAUSE", keyBitmaps["pause"], scale,
                    actionRunCallback<WidgetPauseAction>(params),
                    isPressed = pressedKey == "pause",
                )
            }
        }  // Left column ends.
        Spacer(GlanceModifier.width(sd(6)))
        // Right: vertical cassette spanning the full height.
        Box(
            modifier = GlanceModifier.width(sd(96))
                .fillMaxHeight()
                .cornerRadius(sd(14))
                .background(ColorProvider(Chrome.panel))
                .padding(sd(6))
                .clickable(openApp),
            contentAlignment = Alignment.Center,
        ) {
            if (tape != null) {
                Image(
                    provider = BitmapImageProvider(tape),
                    contentDescription = "Cassette",
                    modifier = GlanceModifier.fillMaxHeight(),
                )
            } else {
                // Empty dark window until the updater renders the tape.
                Box(
                    modifier = GlanceModifier.fillMaxHeight()
                        .width(sd(60))
                        .cornerRadius(sd(10))
                        .background(ColorProvider(Chrome.titleBox)),
                    contentAlignment = Alignment.Center,
                ) {}
            }
        }
    }  // Outer Row ends.
}

/**
 * One square metallic transport key with its small label above: a dark
 * border box holding the silver cap, glyph centered. All sizes derive from
 * the face [scale] so the keys shrink and grow with the widget.
 */
@Composable
private fun TransportKey(
    modifier: GlanceModifier,
    label: String,
    icon: Bitmap?,
    scale: Float,
    action: Action,
    isPressed: Boolean = false,
) {
    fun sd(v: Number) = (v.toFloat() * scale).dp
    fun ss(v: Number) = (v.toFloat() * scale).sp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = TextStyle(
                color = ColorProvider(Chrome.label),
                fontSize = ss(8),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(sd(3)))
        Box(
            modifier = GlanceModifier
                .cornerRadius(sd(12))
                .background(
                    ColorProvider(if (isPressed) Chrome.keyPressed else Chrome.keyBorder)
                )
                .padding(sd(if (isPressed) 4 else 2))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(sd(52))
                    .cornerRadius(sd(10))
                    .background(ColorProvider(Chrome.keycap)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        provider = BitmapImageProvider(icon),
                        contentDescription = label,
                        modifier = GlanceModifier.size(sd(52)),
                    )
                }
            }
        }
    }
}
