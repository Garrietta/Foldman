package dev.foldman.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min

/**
 * Pre-renders the widget's chrome bitmaps with a plain Android [Canvas]
 * (Glance/RemoteViews has no canvas):
 *
 * - [renderTapeFace]: the app's actual cassette design, ported from
 *   ui/Cassette.kt drawCassette — shell gradient, grip ridges, window with
 *   tape packs (concentric layers + rotating sheen), white hubs with teeth
 *   and splines, label with the real artwork (or paper stripes), script
 *   title, source tag, screws, bottom bridge. Drawn in cassette-millimetre
 *   space (100 x 64) and scaled to the bitmap.
 * - [renderVerticalTapeFace]: the face rotated 90° counter-clockwise for the widget's
 *   vertical tape column (mirrors the in-app cassette's orientation).
 * - [renderTransportKey]: a full deck key (slot + cap + symbol). The
 *   [pressed] variant mimics the in-app DeckKey's pressed state: the cap
 *   sinks into a darker slot, the metal darkens, and latched PLAY gets the
 *   orange underline accent.
 * - [renderSliders]: the VOLUME/TONE indicator cluster (legacy).
 *
 * Fixed brushed-chrome palette (not dynamic), matching Garrett's widget
 * mockup. All inputs are sanitized: bad sizes, NaN angles, or out-of-range
 * volumes degrade to a blank-but-valid bitmap, never throw into the updater.
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
    // App deck metal tones (DeckColors), for the brushed widget chrome.
    private val METAL_HI = 0xFFE4E5E7.toInt()
    private val METAL_MID = 0xFFC3C5C9.toInt()
    private val METAL_MIDLO = 0xFFB6B8BC.toInt()
    private val METAL_LO = 0xFF8F9297.toInt()

    // App cassette constants (ui/Cassette.kt).
    private const val SHELL_W = 100f
    private const val SHELL_H = 64f
    private val WINDOW_RECT = RectF(15f, 21f, 88f, 43f)
    private const val HUB_LX = 28.6f
    private const val HUB_RX = 71.4f
    private const val HUB_Y = 31.7f
    private const val HUB_R = 6.2f
    private const val R_EMPTY = 9.8f
    private val LABEL_RECT = RectF(5.5f, 4f, 94.5f, 54f)

    // DeckColors values used by the app cassette.
    private val DECK_TAPE = 0xFF3D2618.toInt()
    private val DECK_PAPER = 0xFFF2EBDC.toInt()
    private val DECK_INK = 0xFF2A2522.toInt()
    private val DECK_ACCENT = 0xFFF0722B.toInt()
    private val DECK_ACCENT_DEEP = 0xFFC4501A.toInt()
    private val DECK_ENGRAVE = 0xFF3A3C41.toInt()

    /**
     * Brushed-aluminium panel in the app deck's material language
     * (DeckColors.Metal tones + hairline brushing + diagonal sheen), with
     * rounded corners and transparent outside so no Glance clipping is needed.
     * Used as the widget's root chrome background.
     */
    fun renderBrushedPanel(widthPx: Int, heightPx: Int, cornerPx: Float): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val r = cornerPx.coerceIn(0f, minOf(wf, hf) / 2f)
        // Base vertical gradient, like the deck chassis.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(METAL_HI, METAL_MID, METAL_MIDLO, METAL_LO),
                floatArrayOf(0f, 0.18f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, wf, hf), r, r, body)
        // Hairline brushing: deterministic (fixed seed) so it never shimmers between redraws.
        val rnd = java.util.Random(7)
        val light = Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = 18
            strokeWidth = 1f
        }
        val dark = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 14
            strokeWidth = 1f
        }
        var y = 0f
        var i = 0
        while (y < hf) {
            val x0 = rnd.nextFloat() * wf * 0.3f
            val x1 = wf - rnd.nextFloat() * wf * 0.3f
            val p = if (i % 2 == 0) light else dark
            canvas.drawLine(x0, y, x1, y, p)
            y += 1f + rnd.nextFloat() * 2f
            i++
        }
        // Diagonal sheen.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, wf, hf,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    0x29FFFFFF,
                    android.graphics.Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, wf, hf), r, r, sheen)
        // Punch the rounded corners transparent.
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(0f, 0f, wf, hf), Path.Direction.CW)
            addRoundRect(RectF(0f, 0f, wf, hf), r, r, Path.Direction.CW)
        }
        canvas.drawPath(outside, clear)
        return bmp
    }

    /**
     * The light inset title panel: brushed silver with the subtle inner
     * shadow that sells the "inset" look. Deterministic brushing (fixed
     * seed) so it never shimmers between redraws. Rendered once and cached
     * by the updater; the widget draws its text on top.
     */
    fun renderTitlePanel(widthPx: Int, heightPx: Int, cornerPx: Float): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val r = cornerPx.coerceIn(0f, minOf(wf, hf) / 2f)
        val panel = RectF(0f, 0f, wf, hf)
        // Bright brushed-silver base, lighter at the top.
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(
                    0xFFF7F8FA.toInt(),
                    0xFFE8EAEE.toInt(),
                    0xFFD5D8DE.toInt(),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(panel, r, r, base)
        // Hairline brushing, lighter than the chrome root.
        val rnd = java.util.Random(21)
        val light = Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = 22
            strokeWidth = 1f
        }
        val dark = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 10
            strokeWidth = 1f
        }
        var y = 0f
        var i = 0
        while (y < hf) {
            val x0 = rnd.nextFloat() * wf * 0.3f
            val x1 = wf - rnd.nextFloat() * wf * 0.3f
            canvas.drawLine(x0, y, x1, y, if (i % 2 == 0) light else dark)
            y += 1f + rnd.nextFloat() * 2f
            i++
        }
        // Inner shadow: dark along the top and left edges (the inset read),
        // faint light lift along the bottom edge.
        canvas.save()
        val clip = Path().apply {
            addRoundRect(panel, r, r, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        val topShadow = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, hf * 0.30f,
                intArrayOf(0x4D000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, 0f, wf, hf * 0.30f), topShadow)
        val leftShadow = Paint().apply {
            shader = LinearGradient(
                0f, 0f, wf * 0.16f, 0f,
                intArrayOf(0x33000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, 0f, wf * 0.16f, hf), leftShadow)
        val bottomLift = Paint().apply {
            shader = LinearGradient(
                0f, hf * 0.80f, 0f, hf,
                intArrayOf(0x00000000, 0x59FFFFFF.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, hf * 0.80f, wf, hf), bottomLift)
        canvas.restore()
        // Crisp 1px dark outline for a clean edge.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0xFF8F949B.toInt()
        }
        canvas.drawRoundRect(RectF(1f, 1f, wf - 1f, hf - 1f), r, r, edge)
        // Punch the rounded corners transparent.
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(0f, 0f, wf, hf), Path.Direction.CW)
            addRoundRect(RectF(0f, 0f, wf, hf), r, r, Path.Direction.CW)
        }
        canvas.drawPath(outside, clear)
        return bmp
    }

    /**
     * The enamel-blue tape panel: richer cobalt gradient with a subtle edge
     * shadow on all four sides for depth, plus a faint top highlight lip.
     * Rendered once and cached by the updater; the widget draws the tape
     * and wordmark on top.
     */
    fun renderBluePanel(widthPx: Int, heightPx: Int, cornerPx: Float): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val r = cornerPx.coerceIn(0f, minOf(wf, hf) / 2f)
        val panel = RectF(0f, 0f, wf, hf)
        // Richer enamel blue: brighter crown, deep foot.
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(
                    0xFF3E82CC.toInt(),
                    0xFF2F6CB8.toInt(),
                    0xFF23549E.toInt(),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(panel, r, r, base)
        // Edge shadow on all four sides for depth, clipped to the panel.
        canvas.save()
        val clip = Path().apply {
            addRoundRect(panel, r, r, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        val edgeLen = minOf(wf, hf) * 0.14f
        val top = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, edgeLen,
                intArrayOf(0x66000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, 0f, wf, edgeLen), top)
        val bottom = Paint().apply {
            shader = LinearGradient(
                0f, hf - edgeLen, 0f, hf,
                intArrayOf(0x00000000, 0x66000000.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, hf - edgeLen, wf, hf), bottom)
        val left = Paint().apply {
            shader = LinearGradient(
                0f, 0f, edgeLen, 0f,
                intArrayOf(0x4D000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(0f, 0f, edgeLen, hf), left)
        val right = Paint().apply {
            shader = LinearGradient(
                wf - edgeLen, 0f, wf, 0f,
                intArrayOf(0x00000000, 0x4D000000.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(wf - edgeLen, 0f, wf, hf), right)
        // Faint top highlight lip.
        val lip = Paint().apply {
            color = 0x66FFFFFF.toInt()
            strokeWidth = maxOf(1f, hf * 0.004f)
        }
        canvas.drawLine(r * 0.6f, 2f, wf - r * 0.6f, 2f, lip)
        canvas.restore()
        // Punch the rounded corners transparent.
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(0f, 0f, wf, hf), Path.Direction.CW)
            addRoundRect(RectF(0f, 0f, wf, hf), r, r, Path.Direction.CW)
        }
        canvas.drawPath(outside, clear)
        return bmp
    }

    // ------------------------------------------------------------------
    // App-faithful cassette face.
    // ------------------------------------------------------------------

    /**
     * Renders the app's actual cassette face (ui/Cassette.kt drawCassette) in
     * cassette-millimetre space, scaled to fit [widthPx] x [heightPx].
     */
    fun renderTapeFace(
        title: String,
        artist: String,
        artwork: Bitmap?,
        source: WidgetSource,
        leftAngleDeg: Float,
        rightAngleDeg: Float,
        leftRadiusMm: Float,
        rightRadiusMm: Float,
        leftDeltaDeg: Float,
        rightDeltaDeg: Float,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            val u = min(w / SHELL_W, h / SHELL_H)
            canvas.translate((w - SHELL_W * u) / 2f, (h - SHELL_H * u) / 2f)
            canvas.scale(u, u)
            drawTapeFaceMm(
                canvas,
                title, artist, artwork, shellFor(source), sourceTagFor(source),
                leftAngleDeg, rightAngleDeg,
                leftRadiusMm.coerceIn(R_EMPTY, 21f), rightRadiusMm.coerceIn(R_EMPTY, 21f),
                leftDeltaDeg, rightDeltaDeg,
            )
            bmp
        } catch (_: Exception) {
            // Never hand the updater a half-drawn bitmap.
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            bmp
        }
    }

    /**
     * The face rotated 90° counter-clockwise for the widget's vertical tape
     * column — the same rigid rotation of the in-app cassette, so the tape
     * mirrors the app's orientation and artwork layout exactly.
     */
    fun renderVerticalTapeFace(
        title: String,
        artist: String,
        artwork: Bitmap?,
        source: WidgetSource,
        leftAngleDeg: Float,
        rightAngleDeg: Float,
        leftRadiusMm: Float,
        rightRadiusMm: Float,
        leftDeltaDeg: Float,
        rightDeltaDeg: Float,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val horizontal = renderTapeFace(
            title, artist, artwork, source,
            leftAngleDeg, rightAngleDeg, leftRadiusMm, rightRadiusMm,
            leftDeltaDeg, rightDeltaDeg,
            heightPx, widthPx,
        )
        val matrix = Matrix().apply { postRotate(270f) }
        val rotated = Bitmap.createBitmap(
            horizontal, 0, 0, horizontal.width, horizontal.height, matrix, true,
        )
        horizontal.recycle()
        return rotated
    }

    private fun drawTapeFaceMm(
        canvas: Canvas,
        title: String,
        artist: String,
        artwork: Bitmap?,
        shell: Int,
        sourceTag: String,
        leftAngle: Float,
        rightAngle: Float,
        leftRadius: Float,
        rightRadius: Float,
        leftDelta: Float,
        rightDelta: Float,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Drop shadow.
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0x73000000
        canvas.save()
        canvas.translate(0.8f, 1.6f)
        canvas.drawRoundRect(RectF(0f, 0f, SHELL_W, SHELL_H), 3.4f, 3.4f, p)
        canvas.restore()

        // Shell.
        p.shader = LinearGradient(
            0f, 0f, 0f, SHELL_H,
            intArrayOf(
                lerpColor(shell, 0xFFFFFFFF.toInt(), 0.18f),
                shell,
                lerpColor(shell, 0xFF000000.toInt(), 0.22f),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(0f, 0f, SHELL_W, SHELL_H), 3.4f, 3.4f, p)
        p.shader = null

        // Grip ridges.
        p.color = 0x59000000
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.7f
        for (i in 0 until 7) {
            val y = 22f + i * 3f
            canvas.drawLine(1.4f, y, 3.2f, y, p)
            canvas.drawLine(96.8f, y, 98.6f, y, p)
        }
        p.style = Paint.Style.FILL

        // Window: clip, then packs + tape lines + smoked tint.
        canvas.save()
        val winPath = Path().apply {
            addRoundRect(WINDOW_RECT, 3.2f, 3.2f, Path.Direction.CW)
        }
        canvas.clipPath(winPath)
        p.color = 0xFF1A1714.toInt()
        canvas.drawRect(WINDOW_RECT, p)
        drawPackMm(canvas, HUB_LX, HUB_Y, leftRadius, leftAngle, p)
        drawPackMm(canvas, HUB_RX, HUB_Y, rightRadius, rightAngle, p)
        p.color = DECK_TAPE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.28f
        for (i in -3..3) {
            canvas.drawLine(HUB_LX, HUB_Y + i * 0.55f, HUB_RX, HUB_Y + i * 0.55f, p)
        }
        p.style = Paint.Style.FILL
        p.color = 0x2E6F7680
        canvas.drawRect(WINDOW_RECT, p)
        canvas.restore()

        // Hubs (drawn over the tint, like the app).
        drawHubMm(canvas, HUB_LX, HUB_Y, leftAngle, leftDelta, p)
        drawHubMm(canvas, HUB_RX, HUB_Y, rightAngle, rightDelta, p)

        // Window glare + frame.
        val glare = Path().apply {
            moveTo(15f, 43f)
            lineTo(34f, 21f)
            lineTo(48f, 21f)
            lineTo(29f, 43f)
            close()
        }
        p.color = (0x12 shl 24) or 0x00FFFFFF // white at 7% alpha
        canvas.save()
        canvas.clipPath(winPath)
        canvas.drawPath(glare, p)
        canvas.restore()
        p.color = lerpColor(shell, 0xFF000000.toInt(), 0.35f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.9f
        canvas.drawRoundRect(WINDOW_RECT, 3.2f, 3.2f, p)
        p.style = Paint.Style.FILL

        // Label: clip to label minus window.
        val labelClip = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRoundRect(LABEL_RECT, 2.2f, 2.2f, Path.Direction.CW)
            addRoundRect(WINDOW_RECT, 3.2f, 3.2f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(labelClip)
        if (artwork != null && !artwork.isRecycled) {
            // Center-crop the artwork into the label rect.
            val aw = artwork.width.toFloat()
            val ah = artwork.height.toFloat()
            val lw = LABEL_RECT.width()
            val lh = LABEL_RECT.height()
            val scale = maxOf(lw / aw, lh / ah)
            val sw = lw / scale
            val sh = lh / scale
            // drawBitmap takes an Int Rect for the source.
            val src = android.graphics.Rect(
                ((aw - sw) / 2f).toInt(), ((ah - sh) / 2f).toInt(),
                ((aw + sw) / 2f).toInt(), ((ah + sh) / 2f).toInt(),
            )
            canvas.drawBitmap(artwork, src, LABEL_RECT, p)
        } else {
            // Paper label with stripes, like the app's LabelPalette fallback.
            p.color = DECK_PAPER
            canvas.drawRect(LABEL_RECT, p)
            p.color = 0xFF2F6FB5.toInt()
            canvas.drawRect(RectF(LABEL_RECT.left, 30f, LABEL_RECT.right, 33.5f), p)
            p.color = 0xFFB8322A.toInt()
            canvas.drawRect(RectF(LABEL_RECT.left, 34.5f, LABEL_RECT.right, 36f), p)
        }
        canvas.restore()

        // Script title.
        val scriptTypeface = try {
            Typeface.create("cursive", Typeface.BOLD)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
        val scriptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = scriptTypeface
            color = DECK_INK
            textSize = 4.6f
            textAlign = Paint.Align.CENTER
        }
        val fullTitle = if (artist.isNotBlank()) "$title — $artist" else title
        val fitted = ellipsizeTo(scriptPaint, fullTitle, 74f)
        // Slight upward arc like the app's transform.
        canvas.save()
        canvas.translate(48f, 13.5f)
        canvas.rotate(-4f)
        canvas.drawText(fitted, 0f, 0f, scriptPaint)
        canvas.restore()

        // Circled "A" marker.
        p.color = DECK_INK
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.5f
        canvas.drawCircle(86f, 11.5f, 2.6f, p)
        p.style = Paint.Style.FILL
        val aPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = DECK_INK
            textSize = 3.4f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("A", 86f, 12.8f, aPaint)

        // Bottom labels.
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = DECK_INK
            textSize = 3f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(sourceTag, 9f, 50f, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("TYPE I NORMAL", 91f, 50f, labelPaint)

        // Bottom bridge.
        p.color = lerpColor(shell, 0xFF000000.toInt(), 0.28f)
        val bridge = Path().apply {
            moveTo(35f, 52f)
            lineTo(65f, 52f)
            lineTo(61f, 60f)
            lineTo(39f, 60f)
            close()
        }
        canvas.drawPath(bridge, p)
        p.color = 0xFF0E0D0C.toInt()
        canvas.drawRect(RectF(45f, 54f, 48f, 58f), p)
        canvas.drawRect(RectF(52f, 54f, 55f, 58f), p)

        // Screws.
        drawScrewMm(canvas, 6f, 6f, 20f, p)
        drawScrewMm(canvas, 94f, 6f, 57f, p)
        drawScrewMm(canvas, 6f, 58f, 94f, p)
        drawScrewMm(canvas, 94f, 58f, 131f, p)
        drawScrewMm(canvas, 50f, 32f, 168f, p)
    }

    /** Concentric tape layers plus rotating sheen sectors, in mm space. */
    private fun drawPackMm(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        packRadius: Float,
        angleDeg: Float,
        paint: Paint,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f
        paint.color = 0xFF2E2019.toInt()
        var r = R_EMPTY
        while (r < packRadius) {
            canvas.drawCircle(cx, cy, r, paint)
            r += 2.2f
        }
        paint.style = Paint.Style.FILL
        // Rotating sheen sectors (3 arcs).
        paint.color = 0x40D8CFC2
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        val oval = RectF(cx - packRadius, cy - packRadius, cx + packRadius, cy + packRadius)
        for (k in 0 until 3) {
            canvas.drawArc(oval, k * 120f - 25f, 50f, true, paint)
        }
        canvas.restore()
    }

    /** White hub with teeth, spindle, and motion-blur copies when spinning fast. */
    private fun drawHubMm(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        deltaDeg: Float,
        paint: Paint,
    ) {
        // Motion-blur copies like the app (kept below the wagon-wheel limit).
        val sweep = minOf(7, (kotlin.math.abs(deltaDeg) / 3.5f).toInt() + 1).coerceAtLeast(1)
        for (copy in 0 until sweep) {
            val a = angleDeg - copy * (deltaDeg / sweep)
            val alpha = if (sweep == 1) 1f else 1f - copy * 0.75f / sweep
            drawHubOnceMm(canvas, cx, cy, a, alpha, paint)
        }
    }

    private fun drawHubOnceMm(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        alpha: Float,
        paint: Paint,
    ) {
        val white = 0xFFF4F1EA.toInt()
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(white, alpha)
        canvas.drawCircle(cx, cy, HUB_R, paint)
        // Dark ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        paint.color = withAlpha(0xFF1A1A1C.toInt(), alpha)
        canvas.drawCircle(cx, cy, HUB_R, paint)
        // Teeth.
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        for (k in 0 until 6) {
            val a = Math.toRadians((k * 60).toDouble())
            val x1 = cx + 2.6f * kotlin.math.cos(a).toFloat()
            val y1 = cy + 2.6f * kotlin.math.sin(a).toFloat()
            val x2 = cx + HUB_R * kotlin.math.cos(a).toFloat()
            val y2 = cy + HUB_R * kotlin.math.sin(a).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        // Spindle with 3 splines.
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(0xFF4A4D52.toInt(), alpha)
        canvas.drawCircle(cx, cy, 1.9f, paint)
        paint.color = withAlpha(0xFFB9BDC2.toInt(), alpha)
        for (k in 0 until 3) {
            val a = Math.toRadians((k * 120).toDouble())
            val x1 = cx + 0.5f * kotlin.math.cos(a).toFloat()
            val y1 = cy + 0.5f * kotlin.math.sin(a).toFloat()
            val x2 = cx + 1.9f * kotlin.math.cos(a).toFloat()
            val y2 = cy + 1.9f * kotlin.math.sin(a).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.7f
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawScrewMm(canvas: Canvas, cx: Float, cy: Float, slotAngleDeg: Float, paint: Paint) {
        val r = 1.5f
        paint.style = Paint.Style.FILL
        paint.color = 0x59000000
        canvas.drawCircle(cx, cy + 0.2f, r * 1.15f, paint)
        paint.shader = null
        paint.color = 0xFF9A9DA2.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = 0xFF3A3C41.toInt()
        canvas.save()
        canvas.rotate(slotAngleDeg, cx, cy)
        canvas.drawRect(RectF(cx - r * 0.8f, cy - r * 0.14f, cx + r * 0.8f, cy + r * 0.14f), paint)
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // Transport keys.
    // ------------------------------------------------------------------

    /**
     * A full deck key: the dark slot it sits in, the silver cap, grip
     * ribbing, and the engraved transport symbol. [pressed] mimics the
     * in-app DeckKey pressed state — the cap sinks into a darker slot, the
     * metal darkens toward black, and latched PLAY gets the orange underline
     * accent (the app's deck accent mark).
     */
    fun renderTransportKey(symbol: String, sizePx: Int, pressed: Boolean): Bitmap {
        val s = sizePx.coerceAtLeast(1).toFloat()
        val bmp = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // Slot: the recess the key sits in. Darker when latched.
            // Deep, near-black slot with a crisp outer border for the
            // mockup's heavier key outlines.
            val slotR = s * 0.18f
            p.style = Paint.Style.FILL
            p.color = if (pressed) 0xFF0B0C0F.toInt() else 0xFF1B1E23.toInt()
            canvas.drawRoundRect(RectF(0f, 0f, s, s), slotR, slotR, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(1.5f, s * 0.014f)
            p.color = 0xFF0A0B0D.toInt()
            canvas.drawRoundRect(RectF(1f, 1f, s - 1f, s - 1f), slotR, slotR, p)
            p.style = Paint.Style.FILL
            // Slot top inner shadow for depth.
            p.shader = LinearGradient(
                0f, 0f, 0f, s * 0.35f,
                intArrayOf(0x99000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(RectF(0f, 0f, s, s), slotR, slotR, p)
            p.shader = null

            // Cap: sinks and darkens when pressed (app DeckKey depth).
            // Stronger bevel: brighter crown, darker foot, dark outline.
            val darken = if (pressed) 0.12f else 0f
            val inset = s * (if (pressed) 0.15f else 0.11f)
            val sink = if (pressed) s * 0.025f else 0f
            val cap = RectF(inset, inset + sink, s - inset, s - inset + sink)
            val capR = s * 0.13f
            val hi = lerpColor(0xFFFAFBFC.toInt(), 0xFF000000.toInt(), darken)
            val mid = lerpColor(0xFFC9CCD1.toInt(), 0xFF000000.toInt(), darken)
            val lo = lerpColor(0xFF9BA0A7.toInt(), 0xFF000000.toInt(), darken)
            p.shader = LinearGradient(
                0f, cap.top, 0f, cap.bottom,
                intArrayOf(hi, mid, lo),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(cap, capR, capR, p)
            p.shader = null
            // Dark outline around the cap for a crisp edge.
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(1f, s * 0.010f)
            p.color = lerpColor(0xFF4A4E55.toInt(), 0xFF000000.toInt(), darken)
            canvas.drawRoundRect(cap, capR, capR, p)
            p.style = Paint.Style.FILL

            // Grip ribbing.
            p.color = lerpColor(0xFF9AA0A6.toInt(), 0xFF000000.toInt(), darken * 0.5f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(1f, s * 0.008f)
            val ribTop = cap.top + cap.height() * 0.16f
            val ribBottom = cap.bottom - cap.height() * 0.16f
            val ribCount = 3
            for (i in 0 until ribCount) {
                val y = ribTop + (ribBottom - ribTop) * i / (ribCount - 1).coerceAtLeast(1)
                canvas.drawLine(cap.left + cap.width() * 0.18f, y, cap.right - cap.width() * 0.18f, y, p)
            }
            p.style = Paint.Style.FILL

            // Top highlight lip on the cap (fainter when pressed).
            p.color = if (pressed) 0x40FFFFFF.toInt() else 0xB3FFFFFF.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(1f, s * 0.012f)
            canvas.drawRoundRect(
                RectF(cap.left + 1f, cap.top + 1f, cap.right - 1f, cap.top + cap.height() * 0.5f),
                capR, capR, p,
            )
            p.style = Paint.Style.FILL

            // Symbol: engraved dark, or deep orange for latched PLAY.
            val cx = s / 2f
            val cy = s / 2f + sink * 0.5f
            val symSize = s * 0.30f
            val symColor = if (pressed && symbol == "play") DECK_ACCENT_DEEP else DECK_ENGRAVE
            drawKeySymbol(canvas, symbol, cx, cy, symSize, symColor)

            // Latched PLAY: orange underline accent, like the app's deck mark.
            if (pressed && symbol == "play") {
                p.color = DECK_ACCENT
                val uw = symSize * 0.62f
                val uh = maxOf(2f, s * 0.028f)
                val uy = cy + symSize * 0.62f
                canvas.drawRoundRect(RectF(cx - uw / 2f, uy, cx + uw / 2f, uy + uh), uh / 2f, uh / 2f, p)
            }
            bmp
        } catch (_: Exception) {
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            bmp
        }
    }

    private fun drawKeySymbol(
        canvas: Canvas,
        symbol: String,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val h = size / 2f
        when (symbol) {
            "prev" -> {
                // Bar + left-pointing triangle: the previous-track glyph.
                val barW = size * 0.16f
                canvas.drawRect(RectF(cx - h, cy - h, cx - h + barW, cy + h), p)
                val tri = Path().apply {
                    moveTo(cx + h - barW * 1.4f, cy - h)
                    lineTo(cx + h - barW * 1.4f, cy + h)
                    lineTo(cx - h, cy)
                    close()
                }
                canvas.drawPath(tri, p)
            }
            "next" -> {
                // Right-pointing triangle + bar: the next-track glyph.
                val barW = size * 0.16f
                canvas.drawRect(RectF(cx + h - barW, cy - h, cx + h, cy + h), p)
                val tri = Path().apply {
                    moveTo(cx - h + barW * 1.4f, cy - h)
                    lineTo(cx - h + barW * 1.4f, cy + h)
                    lineTo(cx + h, cy)
                    close()
                }
                canvas.drawPath(tri, p)
            }
            "play" -> {
                val tri = Path().apply {
                    moveTo(cx - h * 0.85f, cy - h)
                    lineTo(cx - h * 0.85f, cy + h)
                    lineTo(cx + h, cy)
                    close()
                }
                canvas.drawPath(tri, p)
            }
            "pause" -> {
                val barW = size * 0.30f
                val gap = size * 0.16f
                canvas.drawRect(RectF(cx - gap / 2f - barW, cy - h, cx - gap / 2f, cy + h), p)
                canvas.drawRect(RectF(cx + gap / 2f, cy - h, cx + gap / 2f + barW, cy + h), p)
            }
        }
    }

    /**
     * Renders the VOLUME/TONE indicator cluster. The volume thumb tracks
     * [volume] (0..1); tone sits at a fixed neutral spot. Visual only —
     * taps open the app, per the mockup.
     */
    fun renderSliders(volume: Float, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val v = volume.coerceIn(0f, 1f)

        fun drawSlider(top: Float, label: String, thumbT: Float) {
            val cy = top
            val x0 = w * 0.06f
            val x1 = w * 0.94f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Slot.
            paint.color = 0xFF0E0F12.toInt()
            val slotH = h * 0.10f
            canvas.drawRoundRect(RectF(x0, cy - slotH / 2f, x1, cy + slotH / 2f), slotH / 2f, slotH / 2f, paint)
            // Rail highlight.
            paint.color = 0xFF3A3F46.toInt()
            paint.strokeWidth = 2f
            canvas.drawLine(x0 + 8f, cy, x1 - 8f, cy, paint)
            // Thumb.
            val tx = x0 + 8f + (x1 - x0 - 16f) * thumbT
            val tw = w * 0.16f
            val th = h * 0.34f
            val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, cy - th / 2f, 0f, cy + th / 2f,
                    intArrayOf(0xFFF4F5F6.toInt(), 0xFFC6C9CE.toInt(), 0xFF9BA0A6.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(RectF(tx - tw / 2f, cy - th / 2f, tx + tw / 2f, cy + th / 2f), 6f, 6f, thumb)
            // Thumb center line.
            paint.shader = null
            paint.color = ACCENT_RED
            paint.strokeWidth = 3f
            canvas.drawLine(tx, cy - th / 2f + 4f, tx, cy + th / 2f - 4f, paint)
            // Label.
            paint.color = LABEL_INK
            paint.textSize = h * 0.16f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText(label, w / 2f, top + h * 0.30f, paint)
        }

        drawSlider(h * 0.26f, "VOLUME", v)
        drawSlider(h * 0.68f, "TONE", 0.5f)
        return bmp
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private fun shellFor(source: WidgetSource): Int = when (source) {
        WidgetSource.LOCAL -> 0xFF3A3D44.toInt()
        WidgetSource.YTMUSIC -> 0xFFB8322A.toInt()
        WidgetSource.PODCASTS -> 0xFF7A5C2E.toInt()
    }

    private fun sourceTagFor(source: WidgetSource): String = when (source) {
        WidgetSource.LOCAL -> "FM 60"
        WidgetSource.YTMUSIC -> "YT ∞"
        WidgetSource.PODCASTS -> "RSS ∞"
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * tt).toInt()
        val g = (ag + (bg - ag) * tt).toInt()
        val bl = (ab + (bb - ab) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (255f * alpha.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun ellipsizeTo(paint: Paint, text: String, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxWidth) {
            t = t.dropLast(1)
        }
        return "$t…"
    }
}
