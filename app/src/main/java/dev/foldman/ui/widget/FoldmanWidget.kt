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
import androidx.glance.layout.ContentScale
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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.foldman.MainActivity

/** Glance state keys. The tape + sliders are rendered files; text rides as strings. */
internal object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val TAPE_PATH = stringPreferencesKey("tape_path")
    val REEL_ANGLE_LEFT = floatPreferencesKey("reel_angle_left")
    val REEL_ANGLE_RIGHT = floatPreferencesKey("reel_angle_right")
    val SLIDERS_PATH = stringPreferencesKey("sliders_path")
    val ARTWORK_PATH = stringPreferencesKey("artwork_path")
    val KEY_PATHS = stringPreferencesKey("key_paths")
    /** Up/down (latched) transport-key bitmap paths, written once. */
    val KEY_PATHS_UP = stringPreferencesKey("key_paths_up")
    val KEY_PATHS_DOWN = stringPreferencesKey("key_paths_down")
    /** Rendered brushed-chrome root background (static; written once). */
    val CHROME_PATH = stringPreferencesKey("chrome_path")
    /** Rendered inset title panel (brushed silver + inner shadow; static). */
    val TITLE_PANEL_PATH = stringPreferencesKey("title_panel_path")
    /** Rendered enamel-blue tape panel (static). */
    val BLUE_PANEL_PATH = stringPreferencesKey("blue_panel_path")
    val IS_PLAYING = booleanPreferencesKey("is_playing")
    val SHUFFLE = booleanPreferencesKey("shuffle")
    val HAS_NEXT = booleanPreferencesKey("has_next")
    val HAS_PREV = booleanPreferencesKey("has_prev")
    val SOURCE = stringPreferencesKey("source")
    val QUEUE_INDEX = intPreferencesKey("queue_index")
    val PRESSED_KEY = stringPreferencesKey("pressed_key")
}

/** Fixed palette in the app deck's material language (never album-art derived). */
private object Chrome {
    // Brushed chrome root is a rendered bitmap (see CHROME_PATH); this is the fallback.
    val root = Color(0xFFC3C5C9)
    /** Deck enamel blue — the right tape panel (fallback; the rendered bitmap is richer). */
    val panel = Color(0xFF2A66B8)
    val titleBox = Color(0xFF101216)
    val titleText = Color(0xFFFFFFFF)
    val artistText = Color(0xFF9AA0A8)
    /** Light silver/white inset title panel (mockup): dark text on it. */
    val titlePanel = Color(0xFFE9EBEE)
    val titleTextDark = Color(0xFF141618)
    val artistTextDark = Color(0xFF4A4E54)
    val stereoLabel = Color(0xFF6B7076)
    val brandText = Color(0xFFEDEFF2)
    val brandSub = Color(0xFF8B95A1)
    val keycap = Color(0xFFD8DCE0)
    val keyBorder = Color(0xFF54575C)
    val keyPressed = Color(0xFF3A3F45)
    val glyph = Color(0xFF2B2F34)
    val playGreen = Color(0xFF35A852)
    val pauseOrange = Color(0xFFE8821A)
    /** Engraved small-caps labels, like the deck's silver strip. */
    val label = Color(0xFF3A3C41)
    val counterBg = Color(0xFF0B0C0E)
    val resetBg = Color(0xFF2A2E34)
    val resetText = Color(0xFFC9CDD2)
}

/**
 * Foldman's deck on the home screen, in the Walkman idiom (Garrett's mockup):
 *
 * - Left: light silver/white inset title panel with dark text (two lines)
 *   and a small "— STEREO —" caps label at the top-right; below it, four
 *   silver transport keys (PREV / PLAY / NEXT / PAUSE) with the in-app
 *   DeckKey pressed language (PLAY latched shows the orange underline,
 *   PAUSE latched sits recessed); mechanical counter + RESET under the title.
 * - Right: enamel-blue panel with the vertical cassette (the app's actual
 *   tape artwork) and a white FOLDMAN wordmark at the bottom.
 *
 * Everything the updater writes (titles, tape/key files) is read
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
    // Reel angles: referenced so Glance recomposes on every ticker tick.
    // The tape bitmap file is re-decoded above with the new rotations.
    @Suppress("UNUSED_VARIABLE")
    val reelAngleLeft = prefs?.get(WidgetKeys.REEL_ANGLE_LEFT) ?: 0f
    @Suppress("UNUSED_VARIABLE")
    val reelAngleRight = prefs?.get(WidgetKeys.REEL_ANGLE_RIGHT) ?: 0f
    // Transport key bitmaps: up (idle) and down (latched/pressed) variants,
    // rendered with the in-app DeckKey pressed language. Paths joined by "|".
    // Decoded fresh each composition — the updater writes them once, and a
    // torn read decodes to null and falls back gracefully, never a crash.
    fun decodeKeyMap(joined: String?): Map<String, android.graphics.Bitmap?> =
        joined?.split("|")?.let { paths ->
            mapOf(
                "prev" to paths.getOrNull(0),
                "play" to paths.getOrNull(1),
                "next" to paths.getOrNull(2),
                "pause" to paths.getOrNull(3),
            ).mapValues { (_, p) ->
                p?.let {
                    runCatching {
                        BitmapFactory.decodeFile(it)?.takeIf { b -> b.width > 0 && b.height > 0 }
                    }.getOrNull()
                }
            }
        } ?: emptyMap()
    val keysUp = decodeKeyMap(prefs?.get(WidgetKeys.KEY_PATHS_UP))
    val keysDown = decodeKeyMap(prefs?.get(WidgetKeys.KEY_PATHS_DOWN))
    // Back-compat: fall back to the legacy single-set paths if the updater
    // hasn't written the up/down variants yet.
    val keysLegacy = decodeKeyMap(prefs?.get(WidgetKeys.KEY_PATHS))
    fun keyUp(sym: String) = keysUp[sym] ?: keysLegacy[sym]
    fun keyDown(sym: String) = keysDown[sym] ?: keysLegacy[sym]
    val pressedKey = prefs?.get(WidgetKeys.PRESSED_KEY)
    // Latched transport state: PLAY stays recessed while playing, PAUSE stays
    // recessed while paused — driven by the actual playback state in Glance
    // state, not just the momentary tap. Null (never reported) latches neither.
    val isPlaying = prefs?.get(WidgetKeys.IS_PLAYING)
    // Brushed-chrome root background, rendered once by the updater in the
    // deck's material language. Falls back to the flat metal tone.
    val chromeBg = prefs?.get(WidgetKeys.CHROME_PATH)?.let { path ->
        runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }
    // Rendered inset title panel (brushed silver + inner shadow) and
    // enamel-blue tape panel (edge shadow). Static, written once.
    fun decodePanel(key: androidx.datastore.preferences.core.Preferences.Key<String>): Bitmap? =
        prefs?.get(key)?.let { path ->
            runCatching {
                BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
            }.getOrNull()
        }
    val titlePanelBg = decodePanel(WidgetKeys.TITLE_PANEL_PATH)
    val bluePanelBg = decodePanel(WidgetKeys.BLUE_PANEL_PATH)
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
    // Root chrome: brushed aluminium rendered in the deck's material
    // language (rounded corners baked into the bitmap); flat metal fallback.
    // Layout: left = title (top) + 4 keys (bottom); right = vertical tape.
    // STOP-EJECT and sliders removed.
    Box(
        modifier = GlanceModifier.fillMaxSize(),
    ) {
        if (chromeBg != null) {
            Image(
                provider = BitmapImageProvider(chromeBg),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .cornerRadius(sd(26))
                    .background(ColorProvider(Chrome.root)),
            ) {}
        }
        Row(
            modifier = GlanceModifier.fillMaxSize()
                .padding(sd(6)),
        ) {
        // Left: main content.
        Column(modifier = GlanceModifier.defaultWeight()) {
            // Light inset title panel (mockup): brushed silver with inner
            // shadow (rendered bitmap), dark all-caps text, STEREO label
            // top-right. Falls back to the flat silver tone.
            Box(
                modifier = GlanceModifier.fillMaxWidth()
                    .defaultWeight(),
            ) {
                if (titlePanelBg != null) {
                    Image(
                        provider = BitmapImageProvider(titlePanelBg),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Box(
                        modifier = GlanceModifier.fillMaxSize()
                            .cornerRadius(sd(16))
                            .background(ColorProvider(Chrome.titlePanel)),
                    ) {}
                }
                Column(
                    modifier = GlanceModifier.fillMaxSize()
                        .padding(sd(10)),
                ) {
                // Small "— STEREO —" caps label at the top-right of the panel.
                Box(
                    modifier = GlanceModifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        "— STEREO —",
                        style = TextStyle(
                            color = ColorProvider(Chrome.stereoLabel),
                            fontSize = ss(8),
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(sd(2)))
                Text(
                    title.uppercase(),
                    style = TextStyle(
                        color = ColorProvider(Chrome.titleTextDark),
                        fontSize = ss(19),
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                )
                Spacer(GlanceModifier.height(sd(2)))
                Text(
                    artist.uppercase(),
                    style = TextStyle(
                        color = ColorProvider(Chrome.artistTextDark),
                        fontSize = ss(12),
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                // Counter + RESET (below title).
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
                }  // Title content column ends.
            }  // Title panel box ends.
            // Transport keys: full deck keys (slot + cap + symbol) rendered with
            // the in-app DeckKey pressed language. PLAY latches recessed with
            // the orange underline while playing; PAUSE latches while paused.
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportKey(
                    GlanceModifier.defaultWeight(), "PREV",
                    keyUp("prev"), keyDown("prev"), scale,
                    actionRunCallback<WidgetPrevAction>(params),
                    isPressed = pressedKey == "prev",
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "PLAY",
                    keyUp("play"), keyDown("play"), scale,
                    actionRunCallback<WidgetPlayAction>(params),
                    isPressed = pressedKey == "play" || isPlaying == true,
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "NEXT",
                    keyUp("next"), keyDown("next"), scale,
                    actionRunCallback<WidgetNextAction>(params),
                    isPressed = pressedKey == "next",
                )
                TransportKey(
                    GlanceModifier.defaultWeight(), "PAUSE",
                    keyUp("pause"), keyDown("pause"), scale,
                    actionRunCallback<WidgetPauseAction>(params),
                    isPressed = pressedKey == "pause" || isPlaying == false,
                )
            }
        }  // Left column ends.
        Spacer(GlanceModifier.width(sd(6)))
        // Right: enamel-blue panel (rendered bitmap: richer cobalt gradient
        // with edge shadow) with the vertical cassette (the app's actual tape
        // artwork) and the white FOLDMAN wordmark at the bottom. Falls back
        // to the flat blue tone.
        // (Glance Box in a Row stretches vertically to fill the Row's height.)
        // The tape bitmap is rendered at the inner box's aspect (112:188), so
        // Fit fills the width and the 6dp padding is a uniform blue border.
        Box(
            modifier = GlanceModifier.width(sd(128))
                .clickable(openApp),
            contentAlignment = Alignment.Center,
        ) {
            if (bluePanelBg != null) {
                Image(
                    provider = BitmapImageProvider(bluePanelBg),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Box(
                    modifier = GlanceModifier.fillMaxSize()
                        .cornerRadius(sd(14))
                        .background(ColorProvider(Chrome.panel)),
                ) {}
            }
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .padding(sd(6)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = GlanceModifier.defaultWeight()
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tape != null) {
                        Image(
                            provider = BitmapImageProvider(tape),
                            contentDescription = "Cassette",
                            modifier = GlanceModifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                Spacer(GlanceModifier.height(sd(6)))
                Text(
                    "FOLDMAN",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = ss(16),
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }  // Outer Row ends.
    }  // Chrome Box ends.
}

/**
 * One deck transport key with its small caps label above. The bitmap carries
 * the full key — slot, silver cap, grip ribbing, engraved symbol — rendered
 * with the in-app DeckKey pressed language; Glance just picks the up or
 * down (latched) variant. All sizes derive from the face [scale] so the keys
 * shrink and grow with the widget.
 */
@Composable
private fun TransportKey(
    modifier: GlanceModifier,
    label: String,
    iconUp: Bitmap?,
    iconDown: Bitmap?,
    scale: Float,
    action: Action,
    isPressed: Boolean = false,
) {
    fun sd(v: Number) = (v.toFloat() * scale).dp
    fun ss(v: Number) = (v.toFloat() * scale).sp
    val icon = if (isPressed) iconDown ?: iconUp else iconUp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = TextStyle(
                color = ColorProvider(Chrome.label),
                fontSize = ss(9),
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(sd(3)))
        Box(
            modifier = GlanceModifier
                .size(sd(60))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    provider = BitmapImageProvider(icon),
                    contentDescription = label,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
