package dev.foldman.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlin.math.roundToInt

/** Theme override: follow the system setting, or pin light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The single source of truth for the theme, readable from the Compose UI and
 * the Glance widget alike: the existing "foldman" SharedPreferences (the same
 * store DeckViewModel already uses).
 */
object ThemePrefs {
    private const val PREFS_NAME = "foldman"
    private const val KEY_THEME_MODE = "theme_mode"

    fun read(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, null) ?: "",
        )
    }.getOrDefault(ThemeMode.SYSTEM)

    fun write(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_MODE, mode.name)
        }
    }

    /** Resolves the effective dark flag: the override wins, else the system setting. */
    fun isDark(context: Context): Boolean {
        val appContext = context.applicationContext
        return when (read(appContext)) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM ->
                (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }
}

/** Compose-side resolution of the persisted override against the system setting. */
fun resolveDark(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemDark
}

/** The deck's signature enamel blue, used when no album art is available. */
val FallbackEnamel = Color(0xFF2F6FB5)

/** ARGB-int twin of [FallbackEnamel], for the widget's canvas renderers. */
val FALLBACK_ENAMEL_ARGB: Int = 0xFF2F6FB5.toInt()

/**
 * The DARK primary role: the extracted art color with its value roughly
 * halved, so the enamel deck stays dark in dark mode while keeping its hue.
 */
fun Color.toDarkPrimary(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(), hsv,
    )
    hsv[2] = (hsv[2] * 0.52f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** ARGB-int version of [toDarkPrimary], for the widget's canvas renderers. */
fun darkenArgb(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsv,
    )
    hsv[2] = (hsv[2] * 0.52f).coerceIn(0f, 1f)
    return android.graphics.Color.HSVToColor(0xFF, hsv)
}
