package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Matowe szkło: rozmyta, lekko odbarwiona tapeta z tyłu + ciemna taflą o niskiej nieprzezroczystości
 * i obwódka 1 px. Zero animowanych efektów; shadery buforowane, w onDraw nie powstają obiekty.
 */
open class FrostPanel(
    context: Context,
    private val radius: Float,
    private val tint: Int = hex(0x4D1B2027L),
    private val edge: Int = hex(0x2EFFFFFFL),
    private val edgePx: Float = 1f
) : FrameLayout(context) {

    private class Sh(val bmp: Bitmap, val sh: BitmapShader)

    private val cache = arrayOfNulls<Sh>(3)
    private var cacheNext = 0
    private var shCur: Sh? = null
    private var shPrev: Sh? = null
    private val rect = RectF()
    private val edgeRect = RectF()
    private val src = RectF()
    private val m = Matrix()
    private val loc = IntArray(2)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        clipChildren = false
        val cm = ColorMatrix()
        cm.setSaturation(0.55f)
        bg.colorFilter = ColorMatrixColorFilter(cm)
        tintP.color = tint
        edgeP.style = Paint.Style.STROKE
        edgeP.color = edge
        edgeP.strokeWidth = edgePx
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun shFor(b: Bitmap?): Sh? {
        if (b == null) return null
        for (e in cache) if (e != null && e.bmp === b) return e
        val e = Sh(b, BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        cache[cacheNext] = e
        cacheNext = (cacheNext + 1) % cache.size
        return e
    }

    /** Wołane przez Backdrop.commit po zmianie tła. */
    fun rebind() {
        shCur = shFor(Backdrop.cur?.blur)
        shPrev = shFor(Backdrop.prev?.blur)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Backdrop.register(this)
        rebind()
    }

    override fun onDetachedFromWindow() {
        Backdrop.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        val e = edgePx / 2f
        edgeRect.set(e, e, w - e, h - e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        getLocationInWindow(loc)
        if (shCur == null && Backdrop.cur != null) rebind()
        val p = shPrev
        if (p != null && Backdrop.prev != null) paintWall(canvas, Backdrop.prev, p, 255)
        val c = shCur
        if (c != null) paintWall(canvas, Backdrop.cur, c, (Backdrop.fade.coerceIn(0f, 1f) * 255).toInt())
        canvas.drawRoundRect(rect, radius, radius, tintP)
    }

    private fun paintWall(canvas: Canvas, wall: Wall?, s: Sh, alpha: Int) {
        if (wall == null) return
        val b = wall.blur
        val k = Math.max(Backdrop.screenW / b.width, Backdrop.screenH / b.height)
        val ox = (Backdrop.screenW - b.width * k) / 2f
        val oy = (Backdrop.screenH - b.height * k) / 2f
        val l = loc[0].toFloat()
        val t = loc[1].toFloat()
        src.set((l - ox) / k, (t - oy) / k, (l + width - ox) / k, (t + height - oy) / k)
        m.setRectToRect(src, rect, Matrix.ScaleToFit.FILL)
        s.sh.setLocalMatrix(m)
        bg.shader = s.sh
        bg.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, bg)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (edgePx > 0f && edge != 0) {
            val r = Math.max(0f, radius - edgePx / 2f)
            canvas.drawRoundRect(edgeRect, r, r, edgeP)
        }
    }
}
