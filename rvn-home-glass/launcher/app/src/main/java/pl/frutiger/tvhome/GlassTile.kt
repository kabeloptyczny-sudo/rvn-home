package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Kolory tła kafelka z ikoną (gdy aplikacja nie ma banera): z ikony bierzemy średni kolor i robimy z niego gradient. */
object TileArt {

    /** Średni kolor ikony (0 = brak wyraźnego koloru, wtedy używamy domyślnego grafitu). */
    fun tint(d: Drawable): Int {
        return try {
            val n = 24
            val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            val dd = d.constantState?.newDrawable()?.mutate() ?: d
            dd.setBounds(0, 0, n, n)
            dd.draw(c)
            val px = IntArray(n * n)
            bm.getPixels(px, 0, n, 0, 0, n, n)
            bm.recycle()
            var r = 0L
            var g = 0L
            var b = 0L
            var cnt = 0
            for (p in px) {
                if ((p ushr 24) > 160) {
                    r += (p shr 16) and 255
                    g += (p shr 8) and 255
                    b += p and 255
                    cnt++
                }
            }
            if (cnt < 8) return 0
            val hsv = FloatArray(3)
            Color.RGBToHSV((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt(), hsv)
            if (hsv[1] < 0.14f) 0 else Color.HSVToColor(floatArrayOf(hsv[0], Math.min(1f, hsv[1] * 0.9f + 0.1f), 0.62f))
        } catch (e: Throwable) {
            0
        }
    }

    fun top(tint: Int): Int {
        if (tint == 0) return hex(0xFF343C49L)
        val hsv = FloatArray(3)
        Color.colorToHSV(tint, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.85f, 0.82f))
    }

    fun bottom(tint: Int): Int {
        if (tint == 0) return hex(0xFF1B2028L)
        val hsv = FloatArray(3)
        Color.colorToHSV(tint, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0.34f))
    }
}

/**
 * Warstwa "szkła" na kafelku w stylu Apple TV: połysk na górnej połowie, przyciemnienie dołu,
 * jasna ramka (rozjaśnia się przy zaznaczeniu) i smuga światła, która raz przebiega po kafelku przy zaznaczeniu.
 * Shadery powstają raz (przy zmianie rozmiaru), w onDraw nie ma alokacji.
 */
class TileGlassView(ctx: Context, private val radius: Float, private val rimPx: Float) : View(ctx) {

    private val rect = RectF()
    private val inset = RectF()
    private val shadeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakM = Matrix()
    private var streak: LinearGradient? = null
    private var glossH = 0f
    private var shine = 0f
    private val sweepAnim = ValueAnimator.ofFloat(0f, 1f)

    /** Stopień zaznaczenia 0..1 (ustawia go animacja kafelka). */
    var sel = 0f
        set(v) {
            field = v
            invalidate()
        }

    init {
        rimP.style = Paint.Style.STROKE
        rimP.strokeWidth = rimPx
        sweepAnim.duration = 760L
        sweepAnim.interpolator = DecelerateInterpolator(1.5f)
        sweepAnim.addUpdateListener {
            shine = it.animatedValue as Float
            invalidate()
        }
        isFocusable = false
        isClickable = false
    }

    /** Smuga światła przebiega od lewej do prawej (wołane przy zaznaczeniu). */
    fun sweep() {
        sweepAnim.cancel()
        shine = 0f
        sweepAnim.start()
    }

    fun rest() {
        sweepAnim.cancel()
        shine = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        rect.set(0f, 0f, wf, hf)
        val e = rimPx / 2f
        inset.set(e, e, wf - e, hf - e)
        glossH = hf * 0.50f
        glossP.shader = LinearGradient(0f, 0f, 0f, glossH, 0x40FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
        shadeP.shader = LinearGradient(0f, hf * 0.55f, 0f, hf, 0x00000000, 0x59000000, Shader.TileMode.CLAMP)
        rimP.shader = LinearGradient(0f, 0f, wf, hf, hex(0xCCFFFFFFL), 0x22FFFFFF, Shader.TileMode.CLAMP)
        val sg = LinearGradient(
            0f, 0f, wf * 0.55f, 0f,
            intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        streak = sg
        streakP.shader = sg
    }

    override fun onDetachedFromWindow() {
        sweepAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (rect.isEmpty) return
        c.drawRoundRect(rect, radius, radius, shadeP)
        val n = c.save()
        c.clipRect(0f, 0f, rect.right, glossH)
        c.drawRoundRect(rect, radius, radius, glossP)
        c.restoreToCount(n)

        val s = sel
        if (s > 0.01f) {
            val w = rect.width()
            streakM.setSkew(-0.35f, 0f)
            streakM.postTranslate(-0.9f * w + 2.3f * w * shine, 0f)
            streak?.setLocalMatrix(streakM)
            streakP.alpha = (s * 255f).toInt()
            c.drawRoundRect(rect, radius, radius, streakP)
        }

        rimP.alpha = ((0.62f + 0.38f * s) * 255f).toInt()
        val rr = Math.max(0f, radius - rimPx / 2f)
        c.drawRoundRect(inset, rr, rr, rimP)
    }
}
