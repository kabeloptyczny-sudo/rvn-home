package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Wspólny język zaznaczenia: skala 1.12 (220 ms, ease-out, bez overshootu), przygaszenie 70% -> 100%,
 * bardzo miękka poświata (ciepła biel, alfa max ok. 25%), jasny odblask górnej krawędzi (alfa ok. 20%),
 * miękki cień pod elementem i nazwa pod spodem (fade 150 ms).
 */
object Sel {
    const val SCALE = 1.12f
    const val DIM = 0.7f
    const val MS = 220L
    const val FADE_MS = 250L
    const val LABEL_MS = 150L
    /** Ikony doku w spoczynku (zaznaczona: 1.0). */
    const val DOCK_DIM = 0.85f
}

/** Krok animacji zaznaczenia (prymitywny float: bez boxingu i alokacji w pętli animacji). */
fun interface SelStep {
    fun step(s: Float)
}

/** Pre-renderowane bitmapy z gradientami (raz na proces). Bez blur API, bez alokacji w onDraw. */
object SelArt {
    private const val N = 128
    val glow: Bitmap = radial(0xEFE8DA, 0x40)       // alfa w środku 0x40 = ok. 25%
    val shadow: Bitmap = radial(0x000000, 0x66)
    val src = Rect(0, 0, N, N)

    private fun radial(rgb: Int, peak: Int): Bitmap {
        val b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        val k = floatArrayOf(1f, 0.82f, 0.5f, 0.22f, 0.06f, 0f)
        val cols = IntArray(k.size) { ((peak * k[it]).toInt() shl 24) or rgb }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(N / 2f, N / 2f, N / 2f, cols, floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawRect(0f, 0f, N.toFloat(), N.toFloat(), p)
        return b
    }
}

/**
 * Animacja zaznaczenia jednego elementu (sel 0..1, 220 ms ease-out) + rysowanie cienia, poświaty i odblasku
 * z gotowych bitmap/shaderów. Jeden animator, brak alokacji w pętli animacji i w draw.
 */
class SelFx(private val host: View, private val u: Float) {
    var sel = 0f
        private set
    var onStep: SelStep? = null
    val z: Float get() = 1f + (Sel.SCALE - 1f) * sel

    private var from = 0f
    private var to = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f)
    private val dst = RectF()
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenR = RectF()
    private var rad = 0f

    init {
        anim.duration = Sel.MS
        anim.interpolator = Motion.EASE
        anim.addUpdateListener {
            sel = from + (to - from) * it.animatedFraction
            onStep?.step(sel)
            val mx = (host.width * 0.8f).toInt()
            val my = (host.height * 0.8f).toInt()
            host.invalidate(-mx, -my, host.width + mx, host.height + my)
        }
    }

    fun set(on: Boolean) {
        anim.cancel()
        from = sel
        to = if (on) 1f else 0f
        anim.start()
    }

    fun cancel() = anim.cancel()

    /** Kształt odblasku (wołać z onSizeChanged hosta). */
    fun layout(w: Int, h: Int, radius: Float) {
        rad = radius
        val sh = h * 0.26f
        sheenR.set(0f, 0f, w.toFloat(), sh)
        sheenP.shader = LinearGradient(0f, 0f, 0f, sh, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    /** Miękki cień pod prostokątem (l,t,r,b w układzie hosta), z uwzględnieniem skali zaznaczenia. */
    fun drawShadowAt(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (sel <= 0.01f) return
        val k = z
        val w = (r - l) * k
        val h = (b - t) * k
        val cx = (l + r) / 2f
        val bottom = (t + b) / 2f + h / 2f
        val ry = Math.min(h * 0.45f, u * 3.2f)
        dst.set(cx - w * 0.60f, bottom - ry * 0.55f, cx + w * 0.60f, bottom + ry * 1.45f)
        bp.alpha = (sel * 255f).toInt()
        c.drawBitmap(SelArt.shadow, SelArt.src, dst, bp)
    }

    fun drawShadow(c: Canvas, w: Float, h: Float) = drawShadowAt(c, 0f, 0f, w, h)

    /** Szeroka poświata pod elementem (tylko jeśli element nie ma wspólnej, przesuwanej poświaty hosta). */
    fun drawGlow(c: Canvas, w: Float, h: Float) {
        if (sel <= 0.01f) return
        val cy = h * 0.62f
        dst.set(w * 0.5f - w * 0.78f, cy - h * 0.95f, w * 0.5f + w * 0.78f, cy + h * 0.95f)
        bp.alpha = (sel * 255f).toInt()
        c.drawBitmap(SelArt.glow, SelArt.src, dst, bp)
    }

    fun drawSheen(c: Canvas) {
        if (sel <= 0.01f || sheenR.isEmpty) return
        sheenP.alpha = (sel * 255f).toInt()
        c.drawRoundRect(sheenR, rad, rad, sheenP)
    }
}

/**
 * Jedna poświata na kontener (dok, siatka aplikacji, pasek kart). Płynnie przesuwa się do nowego elementu
 * (220 ms), a gdy fokus opuszcza kontener - wygasa 250 ms. Leży za elementami, w treści przewijanej.
 */
class GlowLayer(ctx: Context) : View(ctx) {
    private val cur = RectF()
    private val from = RectF()
    private val to = RectF()
    private val dst = RectF()
    private var a = 0f
    private var aFrom = 0f
    private var aTo = 0f
    private var placed = false
    private var pending: View? = null
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mover = ValueAnimator.ofFloat(0f, 1f)
    private val fader = ValueAnimator.ofFloat(0f, 1f)
    private val hideTask = Runnable { fadeTo(0f, Sel.FADE_MS) }
    var spreadX = 1.05f
    var spreadY = 1.3f
    var biasY = 0.18f

    init {
        mover.duration = Sel.MS
        mover.interpolator = Motion.EASE
        mover.addUpdateListener {
            val f = it.animatedFraction
            cur.left = from.left + (to.left - from.left) * f
            cur.top = from.top + (to.top - from.top) * f
            cur.right = from.right + (to.right - from.right) * f
            cur.bottom = from.bottom + (to.bottom - from.bottom) * f
            invalidate()
        }
        fader.interpolator = Motion.EASE
        fader.addUpdateListener {
            a = aFrom + (aTo - aFrom) * it.animatedFraction
            invalidate()
        }
    }

    private fun fadeTo(target: Float, ms: Long) {
        if (fader.isRunning && aTo == target) return
        if (!fader.isRunning && a == target) return
        fader.cancel()
        aFrom = a
        aTo = target
        fader.duration = ms
        fader.start()
    }

    /** Przesuwa poświatę pod prostokąt (układ tego widoku). */
    fun focusRect(l: Float, t: Float, r: Float, b: Float) {
        removeCallbacks(hideTask)
        to.set(l, t, r, b)
        mover.cancel()
        if (!placed || a <= 0.02f) {
            cur.set(to)
            placed = true
        } else {
            from.set(cur)
            mover.start()
        }
        fadeTo(1f, Sel.MS)
    }

    /** Poświata pod elementem [v] będącym potomkiem [host] (rodzica tej poświaty); czeka na layout, jeśli trzeba. */
    fun focusOn(host: View, v: View, body: View? = null) {
        pending = v
        if (v.width <= 0 || v.isLayoutRequested) {
            v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(x: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    x.removeOnLayoutChangeListener(this)
                    if (pending === x) place(host, x, body)
                }
            })
            return
        }
        place(host, v, body)
    }

    private fun place(host: View, v: View, body: View?) {
        if (v.width <= 0) return
        var x = 0
        var y = 0
        var n: View = v
        while (n !== host) {
            x += n.left
            y += n.top
            n = (n.parent as? View) ?: return
        }
        val h = if (body != null && body.height > 0) body.height else v.height
        focusRect(x.toFloat(), y.toFloat(), (x + v.width).toFloat(), (y + h).toFloat())
    }

    /** Fokus opuścił element: wygaszenie z lekkim opóźnieniem (przeskok między kafelkami nie mruga). */
    fun release() {
        pending = null
        removeCallbacks(hideTask)
        postDelayed(hideTask, 90L)
    }

    fun reset() {
        pending = null
        removeCallbacks(hideTask)
        mover.cancel()
        fader.cancel()
        a = 0f
        placed = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        mover.cancel()
        fader.cancel()
        removeCallbacks(hideTask)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (a <= 0.01f || !placed) return
        val w = cur.width()
        val h = cur.height()
        val cx = cur.centerX()
        val cy = cur.centerY() + h * biasY
        dst.set(cx - w * spreadX, cy - h * spreadY, cx + w * spreadX, cy + h * spreadY)
        p.alpha = (a * 255f).toInt()
        c.drawBitmap(SelArt.glow, SelArt.src, dst, p)
    }
}
