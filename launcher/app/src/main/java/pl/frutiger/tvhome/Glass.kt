package pl.frutiger.tvhome

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
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
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

    /** Wołać po zmianie cur/prev: szkło przepina zbuforowane shadery (bez alokacji w onDraw). */
    fun commit() {
        for (i in views.indices) (views[i] as? GlassView)?.rebind()
        invalidateAll()
    }
}

object Blur {

    /** Rozmywa tablicę pikseli ARGB (box blur, kilka przebiegów ~ rozmycie Gaussa). */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, passes: Int, tmp: IntArray = IntArray(px.size)) {
        if (r < 1) return
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
 *  - rozmyta, podbita kolorystycznie tapeta z tyłu (matowe szkło),
 *  - refrakcja przy krawędziach (dwa pierścienie z powiększonym fragmentem tła + jaśniejszy kolor),
 *  - jasna tafla z gradientem, świecąca krawędź, miękka poświata,
 *  - po zaznaczeniu: sprężyste powiększenie z overshootem, "oddychanie", refleks wjeżdżający od strony fokusu.
 * Shadery są zbuforowane (2-3 bitmapy tła), w onDraw nie powstają żadne obiekty.
 */
open class GlassView(
    context: Context,
    private val radius: Float,
    private val tintTop: Int,
    private val tintBottom: Int,
    private val rim: Boolean = true
) : FrameLayout(context) {

    var zLift = 0f
    var lens = true
    var onFocusProgress: ((Float) -> Unit)? = null
    var focusAmt = 0f
        private set

    private class ShSet(val bmp: Bitmap, val main: BitmapShader, val ra: BitmapShader, val rb: BitmapShader)

    private val cache = arrayOfNulls<ShSet>(3)
    private var cacheNext = 0
    private var setCur: ShSet? = null
    private var setPrev: ShSet? = null

    private val spring = Spring(380f, 0.52f)
    private val spec = Spring(170f, 0.60f, 0.3f)
    private var focused = false
    private var tSec = 0f

    private val tick = object : Tick {
        override fun onTick(dt: Float): Boolean {
            tSec += dt
            val a = spring.step(dt)
            val b = spec.step(dt)
            focusAmt = spring.x
            val f = focusAmt
            val fc = if (f < 0f) 0f else if (f > 1f) 1f else f
            val s = 1f + 0.10f * f + 0.007f * fc * Math.sin(tSec * 2.2).toFloat()
            scaleX = s
            scaleY = s
            translationZ = zLift * (if (f < 0f) 0f else f)
            onFocusProgress?.invoke(f)
            invalidate()
            return a || b || focused
        }
    }

    private val rect = RectF()
    private val rimRect = RectF()
    private val softRect = RectF()
    private val haloRect = RectF()
    private val srcRect = RectF()
    private val srcRect2 = RectF()
    private val ringRectA = RectF()
    private val ringRectB = RectF()
    private val m = Matrix()
    private val m2 = Matrix()
    private val loc = IntArray(2)
    private val clipP = Path()
    private var rimR = 0f
    private var softR = 0f
    private var rimW = 2f
    private var ringRA = 0f
    private var ringRB = 0f
    private var specSh: RadialGradient? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaintA = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaintB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        val cm = ColorMatrix()
        cm.setSaturation(1.6f)
        bgPaint.colorFilter = ColorMatrixColorFilter(cm)
        val ca = ColorMatrix()
        ca.setSaturation(2.0f)
        ca.postConcat(ColorMatrix(floatArrayOf(1.16f, 0f, 0f, 0f, 4f, 0f, 1.16f, 0f, 0f, 4f, 0f, 0f, 1.16f, 0f, 4f, 0f, 0f, 0f, 1f, 0f)))
        ringPaintA.colorFilter = ColorMatrixColorFilter(ca)
        val cb = ColorMatrix()
        cb.setSaturation(2.2f)
        cb.postConcat(ColorMatrix(floatArrayOf(1.30f, 0f, 0f, 0f, 10f, 0f, 1.30f, 0f, 0f, 10f, 0f, 0f, 1.30f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
        ringPaintB.colorFilter = ColorMatrixColorFilter(cb)
        ringPaintA.style = Paint.Style.STROKE
        ringPaintB.style = Paint.Style.STROKE
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

    // ------------------------------------------------------------ shadery tła (cache)

    private fun setFor(b: Bitmap?): ShSet? {
        if (b == null) return null
        for (e in cache) if (e != null && e.bmp === b) return e
        val e = ShSet(
            b,
            BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        )
        cache[cacheNext] = e
        cacheNext = (cacheNext + 1) % cache.size
        return e
    }

    /** Przepina bieżący/poprzedni shader po zmianie tła (Backdrop.commit). */
    fun rebind() {
        setCur = setFor(Backdrop.cur?.blur)
        setPrev = setFor(Backdrop.prev?.blur)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Backdrop.register(this)
        rebind()
        if (focused) Ticker.add(tick)
    }

    override fun onDetachedFromWindow() {
        Ticker.remove(tick)
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
        clipP.rewind()
        clipP.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val wa = Math.min(Math.min(fw, fh) * 0.16f, radius * 0.9f)
        val wb = wa * 0.42f
        ringPaintA.strokeWidth = wa
        ringPaintB.strokeWidth = wb
        ringRectA.set(wa / 2f, wa / 2f, fw - wa / 2f, fh - wa / 2f)
        ringRectB.set(wb / 2f, wb / 2f, fw - wb / 2f, fh - wb / 2f)
        ringRA = Math.max(0f, radius - wa / 2f)
        ringRB = Math.max(0f, radius - wb / 2f)

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

        val sr = Math.max(fw, fh) * 0.75f
        specSh = RadialGradient(0f, 0f, sr, hex(0x80FFFFFFL), hex(0x00FFFFFFL), Shader.TileMode.CLAMP)
        specPaint.shader = specSh
    }

    /** Zaznaczenie: sprężyste powiększenie (overshoot), oddychanie, refleks wjeżdża od strony, z której przyszedł fokus (dir -1..1). */
    fun setFocusLook(on: Boolean, dir: Float = 0f) {
        focused = on
        spring.target = if (on) 1f else 0f
        if (on) {
            spec.x = 0.3f + dir * 0.95f
            spec.v = 0f
            spec.target = 0.3f
        }
        Ticker.add(tick)
    }

    // ------------------------------------------------------------ rysowanie

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        getLocationInWindow(loc)
        val wl = loc[0].toFloat()
        val wt = loc[1].toFloat()
        val ww = width * scaleX
        val wh = height * scaleY

        if (setCur == null && Backdrop.cur != null) rebind()
        setPrev?.let { if (Backdrop.prev != null) drawWall(canvas, Backdrop.prev, it, wl, wt, ww, wh, 255, false) }
        setCur?.let {
            drawWall(canvas, Backdrop.cur, it, wl, wt, ww, wh, (Backdrop.fade.coerceIn(0f, 1f) * 255).toInt(), lens)
        }

        canvas.drawRoundRect(rect, radius, radius, tintPaint)

        val f = focusAmt.coerceIn(0f, 1f)
        if (f > 0f) {
            focusPaint.alpha = (f * 46f).toInt()
            canvas.drawRoundRect(rect, radius, radius, focusPaint)
        }
    }

    private fun drawWall(
        canvas: Canvas, wall: Wall?, set: ShSet,
        wl: Float, wt: Float, ww: Float, wh: Float, alpha: Int, ring: Boolean
    ) {
        if (wall == null) return
        val b = wall.blur
        val s = Math.max(Backdrop.screenW / b.width, Backdrop.screenH / b.height)
        val ox = (Backdrop.screenW - b.width * s) / 2f
        val oy = (Backdrop.screenH - b.height * s) / 2f
        srcRect.set((wl - ox) / s, (wt - oy) / s, (wl + ww - ox) / s, (wt + wh - oy) / s)
        m.setRectToRect(srcRect, rect, Matrix.ScaleToFit.FILL)
        set.main.setLocalMatrix(m)
        bgPaint.shader = set.main
        bgPaint.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        if (!ring) return

        // refrakcja: przy krawędziach tło jest powiększone (efekt soczewki) i jaśniejsze
        val cx = srcRect.centerX()
        val cy = srcRect.centerY()
        val hw = srcRect.width() / 2f
        val hh = srcRect.height() / 2f
        srcRect2.set(cx - hw * 0.90f, cy - hh * 0.90f, cx + hw * 0.90f, cy + hh * 0.90f)
        m2.setRectToRect(srcRect2, rect, Matrix.ScaleToFit.FILL)
        set.ra.setLocalMatrix(m2)
        ringPaintA.shader = set.ra
        ringPaintA.alpha = alpha
        canvas.drawRoundRect(ringRectA, ringRA, ringRA, ringPaintA)
        srcRect2.set(cx - hw * 0.80f, cy - hh * 0.80f, cx + hw * 0.80f, cy + hh * 0.80f)
        m2.setRectToRect(srcRect2, rect, Matrix.ScaleToFit.FILL)
        set.rb.setLocalMatrix(m2)
        ringPaintB.shader = set.rb
        ringPaintB.alpha = alpha
        canvas.drawRoundRect(ringRectB, ringRB, ringRB, ringPaintB)
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

        if (f > 0.02f && lens) {
            // jaśniejszy refleks podążający za zaznaczeniem
            canvas.save()
            canvas.clipPath(clipP)
            val sx = width * (spec.x + 0.035f * Math.sin(tSec * 1.1).toFloat())
            m2.setTranslate(sx, height * 0.18f)
            specSh?.setLocalMatrix(m2)
            specPaint.shader = specSh
            specPaint.alpha = (f * 255f).toInt()
            canvas.drawRect(rect, specPaint)
            canvas.restore()
        }

        shinePaint.alpha = (255f * (0.55f + 0.45f * f)).toInt()
        canvas.drawRoundRect(rect, radius, radius, shinePaint)

        softPaint.alpha = (55f + 80f * f).toInt()
        canvas.drawRoundRect(softRect, softR, softR, softPaint)
        rimPaint.alpha = (185f + 70f * f).toInt()
        canvas.drawRoundRect(rimRect, rimR, rimR, rimPaint)
    }
}
