package dev.foldman.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pre-renders the widget's chrome bitmaps with a plain Android [Canvas]
 * (Glance/RemoteViews has no canvas):
 *
 * - [renderCassette]: the horizontal dark cassette window with twin metallic
 *   reels. [angleDeg] rotates the reel spokes; [FoldmanWidgetUpdater]
 *   re-renders on its 750ms ticker while playing, which animates the reels.
 * - [renderSliders]: the VOLUME/TONE indicator cluster. The volume thumb
 *   tracks [volume] (0..1); tone sits at a fixed neutral spot. Visual only —
 *   taps open the app, per the mockup.
 *
 * Fixed brushed-chrome palette (not dynamic, not artwork-derived), matching
 * Garrett's widget mockup. All inputs are sanitized: bad sizes, NaN angles,
 * or out-of-range volumes degrade to a blank-but-valid bitmap, never throw
 * into the updater.
 */
object CassetteRenderer {

    // Fixed chrome palette.
    private val BODY = 0xFF17191D.toInt()
    private val WINDOW = 0xFF22252B.toInt()
    private val REEL_PACK = 0xFF0C0D10.toInt()
    private val METAL = 0xFFC9CDD2.toInt()
    private val METAL_DARK = 0xFF8B9096.toInt()
    private val HUB = 0xFF2A2E34.toInt()
    private val TAPE_LINE = 0xFF3A3F46.toInt()
    private val ACCENT_RED = 0xFFC0392B.toInt()
    private val LABEL_INK = 0xFF3A3F45.toInt()

    fun renderCassette(angleDeg: Float, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val angle = if (angleDeg.isFinite()) angleDeg else 0f

        // Body.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BODY }
        canvas.drawRoundRect(RectF(0f, 0f, wf, hf), hf * 0.12f, hf * 0.12f, body)
        // Inner window.
        val win = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WINDOW }
        val inset = hf * 0.08f
        canvas.drawRoundRect(
            RectF(inset, inset, wf - inset, hf - inset),
            hf * 0.09f, hf * 0.09f, win
        )

        // Twin reels + the tape path between them.
        val reelY = hf * 0.44f
        val reelR = hf * 0.30f
        val leftX = wf * 0.30f
        val rightX = wf * 0.70f
        val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TAPE_LINE
            strokeWidth = hf * 0.022f
        }
        canvas.drawLine(leftX, reelY, rightX, reelY, tapePaint)
        drawReel(canvas, leftX, reelY, reelR, angle)
        drawReel(canvas, rightX, reelY, reelR, angle)

        // Corner screws + a small red accent tab, like the deck hardware.
        val screw = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HUB }
        val sr = hf * 0.035f
        val sx = inset * 1.7f
        val sy = inset * 1.7f
        canvas.drawCircle(sx, sy, sr, screw)
        canvas.drawCircle(wf - sx, sy, sr, screw)
        canvas.drawCircle(sx, hf - sy, sr, screw)
        canvas.drawCircle(wf - sx, hf - sy, sr, screw)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_RED }
        canvas.drawRoundRect(
            RectF(wf * 0.465f, hf * 0.80f, wf * 0.535f, hf * 0.89f),
            hf * 0.02f, hf * 0.02f, accent
        )
        return bmp
    }

    private fun drawReel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        angleDeg: Float,
    ) {
        // Tape pack.
        val pack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REEL_PACK }
        canvas.drawCircle(cx, cy, r, pack)
        // Metallic ring.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = METAL
            style = Paint.Style.STROKE
            strokeWidth = r * 0.14f
        }
        canvas.drawCircle(cx, cy, r * 0.78f, ring)
        val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = METAL_DARK
            style = Paint.Style.STROKE
            strokeWidth = r * 0.06f
        }
        canvas.drawCircle(cx, cy, r * 0.60f, ringInner)
        // Spokes, rotated.
        val spoke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = METAL
            strokeWidth = r * 0.16f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        for (i in 0 until 3) {
            val a = Math.toRadians((i * 120).toDouble())
            canvas.drawLine(
                cx, cy,
                (cx + r * 0.72f * cos(a)).toFloat(),
                (cy + r * 0.72f * sin(a)).toFloat(),
                spoke,
            )
        }
        canvas.restore()
        // Hub.
        val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HUB }
        canvas.drawCircle(cx, cy, r * 0.18f, hub)
    }

    fun renderSliders(volume: Float): Bitmap {
        val w = 360
        val h = 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawSliderRow(canvas, "VOLUME", volume.coerceIn(0f, 1f), top = 6f, w = w.toFloat())
        drawSliderRow(canvas, "TONE", 0.4f, top = 78f, w = w.toFloat())
        return bmp
    }

    private fun drawSliderRow(canvas: Canvas, label: String, level: Float, top: Float, w: Float) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_INK
            textSize = 25f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(label, 8f, top + 24f, labelPaint)
        val trackTop = top + 34f
        val trackH = 26f
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF54595F.toInt() }
        canvas.drawRoundRect(
            RectF(8f, trackTop, w - 8f, trackTop + trackH),
            trackH / 2f, trackH / 2f, trackPaint
        )
        // Center groove.
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E3237.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawLine(
            18f, trackTop + trackH / 2f,
            w - 18f, trackTop + trackH / 2f, groove
        )
        // Thumb.
        val thumbW = 48f
        val thumbH = 42f
        val x = 8f + level.coerceIn(0f, 1f) * (w - 16f - thumbW)
        val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = METAL }
        canvas.drawRoundRect(
            RectF(x, trackTop - 8f, x + thumbW, trackTop - 8f + thumbH),
            9f, 9f, thumb
        )
        val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = METAL_DARK
            strokeWidth = 3f
        }
        for (i in 0 until 3) {
            val rx = x + thumbW * (0.3f + 0.2f * i)
            canvas.drawLine(rx, trackTop - 2f, rx, trackTop - 2f + thumbH - 12f, ridge)
        }
    }
}
