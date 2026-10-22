package dev.foldman.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the mini cassette window for the home-screen widget onto a bitmap.
 *
 * Glance (RemoteViews) has no canvas, so the tape — body, label window with
 * the album art, twin reels with spokes, tape path — is pre-rendered here in
 * the app process with a plain Android [Canvas]. The widget shows the bitmap;
 * [FoldmanWidgetUpdater] re-renders it with a new [angleDeg] on every reel
 * tick while playing, which is what animates the reels.
 */
object CassetteRenderer {

    fun render(colors: RusticColors, artwork: Bitmap?, angleDeg: Float, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = sizePx.toFloat()

        // Body.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.keycap.toArgb() }
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * 0.16f, s * 0.16f, body)

        // Label window: the album art when there is any, else a ♪ glyph.
        val label = RectF(s * 0.09f, s * 0.08f, s * 0.91f, s * 0.50f)
        if (artwork != null) {
            canvas.save()
            val clip = Path().apply {
                addRoundRect(label, s * 0.05f, s * 0.05f, Path.Direction.CW)
            }
            canvas.clipPath(clip)
            // Center-crop the art into the label.
            val aw = artwork.width.toFloat()
            val ah = artwork.height.toFloat()
            val scale = maxOf(label.width() / aw, label.height() / ah)
            val dw = aw * scale
            val dh = ah * scale
            val dst = RectF(
                label.centerX() - dw / 2f, label.centerY() - dh / 2f,
                label.centerX() + dw / 2f, label.centerY() + dh / 2f,
            )
            canvas.drawBitmap(artwork, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.symbol.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = s * 0.012f
            }
            canvas.drawRoundRect(label, s * 0.05f, s * 0.05f, frame)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent.toArgb() }
            canvas.drawRoundRect(label, s * 0.05f, s * 0.05f, fill)
            val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.title.toArgb()
                textSize = s * 0.24f
                textAlign = Paint.Align.CENTER
            }
            val fm = glyph.fontMetrics
            canvas.drawText("♪", label.centerX(), label.centerY() - (fm.ascent + fm.descent) / 2f, glyph)
        }

        // Tape path between the reels.
        val leftX = s * 0.30f
        val rightX = s * 0.70f
        val reelY = s * 0.70f
        val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.symbol.toArgb()
            strokeWidth = s * 0.02f
        }
        canvas.drawLine(leftX, reelY, rightX, reelY, tape)

        // Twin reels; the spokes rotate with angleDeg.
        drawReel(canvas, leftX, reelY, s * 0.115f, angleDeg, colors)
        drawReel(canvas, rightX, reelY, s * 0.115f, angleDeg, colors)

        return bmp
    }

    private fun drawReel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        angleDeg: Float,
        colors: RusticColors,
    ) {
        // Tape pack.
        val pack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.background.toArgb() }
        canvas.drawCircle(cx, cy, r, pack)
        // Hub ring.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.symbol.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = r * 0.16f
        }
        canvas.drawCircle(cx, cy, r * 0.62f, ring)
        // Spokes, rotated.
        val spoke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.title.toArgb()
            strokeWidth = r * 0.18f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        for (i in 0 until 3) {
            val a = Math.toRadians((i * 120).toDouble())
            canvas.drawLine(
                cx, cy,
                (cx + r * 0.88f * cos(a)).toFloat(),
                (cy + r * 0.88f * sin(a)).toFloat(),
                spoke,
            )
        }
        canvas.restore()
        // Hub.
        val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent.toArgb() }
        canvas.drawCircle(cx, cy, r * 0.16f, hub)
    }
}
