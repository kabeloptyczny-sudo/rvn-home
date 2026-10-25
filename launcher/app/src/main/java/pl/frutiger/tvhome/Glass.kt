package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/** Skrót do zapisu kolorów ARGB jako liczby szesnastkowe (Long -> Int). */
fun hex(v: Long): Int = v.toInt()

/** Tapeta + jej mocno rozmyta, mała kopia (służy do efektu szkła). */
class Wall(val full: Bitmap, val blur: Bitmap)

/**
 * Wspólny stan tła: aktualna i poprzednia tapeta (płynne przejście) oraz rozmiar ekranu.
 * Szklane elementy rysują fragment rozmytej tapety dokładnie tam, gdzie stoją na ekranie.
 */
object Backdrop {
    var cur: Wall? = null
    var prev: Wall? = null
    var fade = 1f
    var screenW = 1920f
    var screenH = 1080f

    private val views = ArrayList<View>()

    fun register(v: View) {
        if (!views.contains(v)) views.add(v)
    }

    fun unregister(v: View) {
        views.remove(v)
    }

    fun invalidateAll() {
        for (i in views.indices) views[i].invalidate()
    }
}

object Blur {

    /** Rozmywa tablicę pikseli ARGB (box blur, kilka przebiegów ~ rozmycie Gaussa). */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        if (r < 1) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            pass(px, tmp, w, h, r, true)
            pass(tmp, px, w, h, r, false)
        }
    }

    private fun clampIdx(i: Int, len: Int): Int = if (i < 0) 0 else if (i > len - 1) len - 1 else i

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val lines = if (horiz) h else w
        val len = if (horiz) w else h
        val stepIn = if (horiz) 1 else w
        val lineStep = if (horiz) w else 1
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = line * lineStep
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = src[base + clampIdx(i, len) * stepIn]
                sa += c ushr 24
                sr += (c shr 16) and 255
                sg += (c shr 8) and 255
                sb += c and 255
            }
            for (i in 0 until len) {
                dst[base + i * stepIn] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + clampIdx(i + r + 1, len) * stepIn]
                val rem = src[base + clampIdx(i - r, len) * stepIn]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }

    /** Z pełnej tapety robi parę (tapeta, mała mocno rozmyta kopia o tych samych proporcjach). */
    fun makeWall(src: Bitmap): Wall {
        val bw = 128
        val bh = Math.max(2, Math.round(bw.toFloat() * src.height / src.width))
        val small = Bitmap.createScaledBitmap(src, bw, bh, true)
        val px = IntArray(bw * bh)
        small.getPixels(px, 0, bw, 0, 0, bw, bh)
        boxBlur(px, bw, bh, 4, 2)
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, bw, 0, 0, bw, bh)
        return Wall(src, out)
    }
}

/** Pełnoekranowa tapeta z płynnym przejściem (center-crop). */
class WallpaperView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Backdrop.register(this)
    }

    override fun onDetachedFromWindow() {
        Backdrop.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        Backdrop.prev?.let { drawWall(canvas, it.full, 255) }
        Backdrop.cur?.let { drawWall(canvas, it.full, (Backdrop.fade.coerceIn(0f, 1f) * 255).toInt()) }
    }

    private fun drawWall(canvas: Canvas, b: Bitmap, alpha: Int) {
        val sw = width.toFloat()
        val sh = height.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val s = Math.max(sw / b.width, sh / b.height)
        val vw = sw / s
        val vh = sh / s
        val l = (b.width - vw) / 2f
        val t = (b.height - vh) / 2f
        src.set(l.toInt(), t.toInt(), (l + vw).toInt(), (t + vh).toInt())
        dst.set(0, 0, width, height)
        paint.alpha = alpha
        canvas.drawBitmap(b, src, dst, paint)
    }
}

/** Ikona "Aplikacje": siatka 3x3 zaokrąglonych kwadratów. */
class GlyphView(context: Context) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val total = Math.min(width, height) * 0.46f
        val cell = total / 3f
        val gap = cell * 0.22f
        val sq = cell - gap
        val ox = (width - total) / 2f
        val oy = (height - total) / 2f
        p.color = hex(0xEBFFFFFFL)
        for (r in 0..2) {
            for (c in 0..2) {
                val l = ox + c * cell + gap / 2f
                val t = oy + r * cell + gap / 2f
                canvas.drawRoundRect(l, t, l + sq, t + sq, sq * 0.3f, sq * 0.3f, p)
            }
        }
    }
}

/**
 * Szklany panel w stylu iOS 26 "Liquid Glass":
 *  - rozmyta, podbita kolorystycznie tapeta z tyłu (prawdziwe matowe szkło),
 *  - jasna tafla z gradientem i refleksem,
 *  - świecąca krawędź (jaśniejsza w lewym górnym i prawym dolnym rogu),
 *  - po zaznaczeniu: powiększenie, uniesienie (cień), jaśniejsze szkło i delikatna poświata.
 */
class GlassView(
    context: Context,
    private val radius: Float,
    private val tintTop: Int,
    private val tintBottom: Int,
    private val rim: Boolean = true
) : FrameLayout(context) {

    var zLift = 0f
    var focusAmt = 0f
        private set

    private var anim: ValueAnimator? = null

    private val rect = RectF()
    private val rimRect = RectF()
    private val softRect = RectF()
    private val haloRect = RectF()
    private val srcRect = RectF()
    private val m = Matrix()
    private val loc = IntArray(2)
    private var rimR = 0f
    private var softR = 0f
    private var rimW = 2f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        val cm = ColorMatrix()
        cm.setSaturation(1.6f)
        bgPaint.colorFilter = ColorMatrixColorFilter(cm)
        focusPaint.color = Color.WHITE
        rimPaint.style = Paint.Style.STROKE
        softPaint.style = Paint.Style.STROKE
        haloPaint.style = Paint.Style.STROKE
        haloPaint.color = Color.WHITE
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Backdrop.register(this)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        Backdrop.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        rect.set(0f, 0f, fw, fh)
        rimW = Math.max(2f, Math.min(fw, fh) * 0.014f)
        rimRect.set(rimW / 2f, rimW / 2f, fw - rimW / 2f, fh - rimW / 2f)
        softRect.set(rimW * 1.5f, rimW * 1.5f, fw - rimW * 1.5f, fh - rimW * 1.5f)
        rimR = Math.max(0f, radius - rimW / 2f)
        softR = Math.max(0f, radius - rimW * 1.5f)

        tintPaint.shader = LinearGradient(0f, 0f, 0f, fh, tintTop, tintBottom, Shader.TileMode.CLAMP)
        shinePaint.shader = LinearGradient(
            0f, 0f, fw * 0.85f, fh,
            hex(0x3DFFFFFFL), hex(0x00FFFFFFL), Shader.TileMode.CLAMP
        )
        val rimShader = LinearGradient(
            0f, 0f, fw, fh,
            intArrayOf(hex(0xD9FFFFFFL), hex(0x26FFFFFFL), hex(0x26FFFFFFL), hex(0x99FFFFFFL)),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.shader = rimShader
        rimPaint.strokeWidth = rimW
        softPaint.shader = rimShader
        softPaint.strokeWidth = rimW * 2.2f
        haloPaint.strokeWidth = rimW * 1.6f
    }

    /** Animacja zaznaczenia: powiększenie, uniesienie i rozjaśnienie szkła. */
    fun setFocusLook(on: Boolean) {
        anim?.cancel()
        val to = if (on) 1f else 0f
        val a = ValueAnimator.ofFloat(focusAmt, to)
        a.duration = 230
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            focusAmt = it.animatedValue as Float
            val s = 1f + 0.10f * focusAmt
            scaleX = s
            scaleY = s
            translationZ = zLift * focusAmt
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        getLocationInWindow(loc)
        val wl = loc[0].toFloat()
        val wt = loc[1].toFloat()
        val ww = width * scaleX
        val wh = height * scaleY

        Backdrop.prev?.let { drawWall(canvas, it, wl, wt, ww, wh, 255) }
        Backdrop.cur?.let {
            drawWall(canvas, it, wl, wt, ww, wh, (Backdrop.fade.coerceIn(0f, 1f) * 255).toInt())
        }

        canvas.drawRoundRect(rect, radius, radius, tintPaint)

        val f = focusAmt.coerceIn(0f, 1f)
        if (f > 0f) {
            focusPaint.alpha = (f * 46f).toInt()
            canvas.drawRoundRect(rect, radius, radius, focusPaint)
        }
    }

    private fun drawWall(
        canvas: Canvas, wall: Wall,
        wl: Float, wt: Float, ww: Float, wh: Float, alpha: Int
    ) {
        val b = wall.blur
        val s = Math.max(Backdrop.screenW / b.width, Backdrop.screenH / b.height)
        val ox = (Backdrop.screenW - b.width * s) / 2f
        val oy = (Backdrop.screenH - b.height * s) / 2f
        srcRect.set((wl - ox) / s, (wt - oy) / s, (wl + ww - ox) / s, (wt + wh - oy) / s)
        val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        m.setRectToRect(srcRect, rect, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(m)
        bgPaint.shader = shader
        bgPaint.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (width <= 0 || height <= 0 || !rim) return
        val f = focusAmt.coerceIn(0f, 1f)

        if (f > 0.01f) {
            // delikatna poświata wokół zaznaczonego elementu
            for (k in 1..3) {
                val grow = rimW * 2.2f * k
                haloRect.set(-grow, -grow, width + grow, height + grow)
                haloPaint.alpha = (34f * f / k).toInt()
                canvas.drawRoundRect(haloRect, radius + grow, radius + grow, haloPaint)
            }
        }

        shinePaint.alpha = (255f * (0.55f + 0.45f * f)).toInt()
        canvas.drawRoundRect(rect, radius, radius, shinePaint)

        softPaint.alpha = (55f + 80f * f).toInt()
        canvas.drawRoundRect(softRect, softR, softR, softPaint)
        rimPaint.alpha = (185f + 70f * f).toInt()
        canvas.drawRoundRect(rimRect, rimR, rimR, rimPaint)
    }
}
