package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

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
        for (i in views.indices) (views[i] as? FrostPanel)?.rebind()
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

/** Ikona "Aplikacje": siatka 3x3 zaokrąglonych kwadratów (jednolity piasek). */
class GlyphView(context: Context) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val total = Math.min(width, height) * 0.46f
        val cell = total / 3f
        val gap = cell * 0.22f
        val sq = cell - gap
        val ox = (width - total) / 2f
        val oy = (height - total) / 2f
        p.color = Pal.SAND
        for (r in 0..2) {
            for (c in 0..2) {
                val l = ox + c * cell + gap / 2f
                val t = oy + r * cell + gap / 2f
                canvas.drawRoundRect(l, t, l + sq, t + sq, sq * 0.3f, sq * 0.3f, p)
            }
        }
    }
}
