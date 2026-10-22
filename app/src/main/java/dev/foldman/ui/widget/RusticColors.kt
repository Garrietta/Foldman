package dev.foldman.ui.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * The widget's palette, derived from the playing song's album art.
 *
 * "Rustic" here means muted, earthy and warm: the art's dominant hue is
 * desaturated and dragged toward amber/brown, then re-lit per surface. Pure
 * except for the [Palette] pass, so it is unit-testable with a canvas bitmap.
 */
data class RusticColors(
    val background: Color,
    val keycap: Color,
    val symbol: Color,
    val accent: Color,
    val title: Color,
    val subtitle: Color,
) {
    companion object {
        /** Foldman's default deck tones, for when there is no artwork. */
        val Fallback = RusticColors(
            background = Color(0xFF241C15),
            keycap = Color(0xFFC9BFAE),
            symbol = Color(0xFF2A2018),
            accent = Color(0xFFC4501A),
            title = Color(0xFFF2EBDC),
            subtitle = Color(0xFFB9A88F),
        )

        fun from(artwork: Bitmap?): RusticColors {
            if (artwork == null) return Fallback
            return runCatching {
                val swatch = Palette.from(artwork).generate().dominantSwatch
                    ?: return Fallback
                rustic(swatch.rgb)
            }.getOrDefault(Fallback)
        }

        private fun rustic(rgb: Int): RusticColors {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(rgb, hsl)
            // Drag the hue toward warm amber (~32 deg) along the shortest arc,
            // then mute it: rustic, never neon.
            var h = hsl[0]
            val arc = ((32f - h + 540f) % 360f) - 180f
            h = (h + arc * 0.45f + 360f) % 360f
            val s = (hsl[1] * 0.5f).coerceIn(0f, 1f)
            fun at(lightness: Float, saturation: Float = s): Color {
                val c = ColorUtils.HSLToColor(
                    floatArrayOf(h, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
                )
                return Color(c)
            }
            return RusticColors(
                background = at(0.16f, s * 0.8f),
                keycap = at(0.78f, s * 0.45f),
                symbol = at(0.13f, s * 0.6f),
                accent = at(0.52f, s * 1.15f),
                // Warm paper tones stay legible on the darkened background.
                title = Color(0xFFF5EDE0),
                subtitle = Color(0xFFC4B49A),
            )
        }
    }
}
