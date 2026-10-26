package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Wspólne zaznaczenie: skala 1.12 w 200 ms (ease-out), przygaszenie 70% -> 100%, nazwa (fade 150 ms). */
object Sel {
    const val SCALE = 1.12f
    const val DIM = 0.7f
    const val MS = 200L
    const val LABEL_MS = 150L
}

/**
 * Bardzo delikatne, miękkie światło pod zaznaczonym elementem (radialny gradient, bez ramek i prostokątów).
 * Jeden animator i jeden shader na cały czas życia; w draw() brak alokacji.
 */
class SoftGlow(private val host: View) {
    private var a = 0f
    private var from = 0f
    private var to = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim = ValueAnimator.ofFloat(0f, 1f)

    init {
        p.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0x30EFE8DA, 0x14EFE8DA, 0x00EFE8DA), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        anim.duration = Sel.MS
        anim.interpolator = Motion.EASE
        anim.addUpdateListener {
            a = from + (to - from) * it.animatedFraction
            val m = Math.max(host.width, host.height) / 2
            host.invalidate(-m, -m, host.width + m, host.height + m)
        }
    }

    fun set(on: Boolean) {
        anim.cancel()
        from = a
        to = if (on) 1f else 0f
        anim.start()
    }

    fun cancel() = anim.cancel()

    fun draw(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        if (a <= 0.01f) return
        p.alpha = (a * 255f).toInt()
        c.save()
        c.translate(cx, cy)
        c.scale(rx, ry)
        c.drawCircle(0f, 0f, 1f, p)
        c.restore()
    }
}

/**
 * Jednolity, zaokrąglony kafelek 16:9. Bez ramek i teł zaznaczenia: niezaznaczony przygaszony (70%), zaznaczony
 * w pełnym kolorze, skala 1.12 (200 ms, ease-out), pod nim nazwa (fade 150 ms) i bardzo delikatne miękkie światło.
 */
class TileView(
    ctx: Context,
    private val u: Float,
    wU: Float,
    label: String,
    content: View,
    private val showLabel: Boolean = true
) : LinearLayout(ctx) {

    val card = FrameLayout(ctx)
    val labelView = TextView(ctx)
    var onFocus: ((TileView) -> Unit)? = null
    var onBlur: ((TileView) -> Unit)? = null
    private val glow = SoftGlow(this)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        id = View.generateViewId()

        val w = (wU * u).toInt()
        val h = (wU * u * 9f / 16f).toInt()
        val r = u * 1.0f

        val bgd = GradientDrawable()
        bgd.setColor(Pal.TILE)
        bgd.cornerRadius = r
        card.background = bgd
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.alpha = Sel.DIM
        card.addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(card, LayoutParams(w, h))

        labelView.text = label
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        labelView.setTextColor(Pal.TEXT)
        labelView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        labelView.maxLines = 1
        labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        labelView.gravity = Gravity.CENTER
        labelView.alpha = 0f
        labelView.letterSpacing = 0.02f
        if (!showLabel) labelView.visibility = View.GONE
        val lp = LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 0.9f).toInt()
        addView(labelView, lp)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val z = if (gainFocus) Sel.SCALE else 1f
        card.animate().cancel()
        card.animate().scaleX(z).scaleY(z).alpha(if (gainFocus) 1f else Sel.DIM)
            .setDuration(Sel.MS).setInterpolator(Motion.EASE).start()
        if (showLabel) {
            // nazwa zjeżdża razem z dolną krawędzią powiększonej karty
            val dy = if (gainFocus) card.height * (Sel.SCALE - 1f) / 2f else 0f
            labelView.animate().cancel()
            labelView.animate().alpha(if (gainFocus) 1f else 0f).translationY(dy)
                .setDuration(Sel.LABEL_MS).setInterpolator(Motion.EASE).start()
        }
        glow.set(gainFocus)
        if (gainFocus) onFocus?.invoke(this) else onBlur?.invoke(this)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // światło pod kartą (rysowane przed dziećmi, więc kafelek i napis leżą na nim)
        glow.draw(canvas, width / 2f, card.height * (0.5f + Sel.SCALE / 2f), card.width * 0.62f, card.height * 0.95f)
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        glow.cancel()
        super.onDetachedFromWindow()
    }
}

/** Ikona "TV": ramka ekranu + podstawka, jednolita linia w kolorze piasku. */
class TvGlyphView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        p.style = Paint.Style.STROKE
        p.color = Pal.SAND
    }

    override fun onDraw(canvas: Canvas) {
        val hh = height.toFloat()
        val sw = Math.min(width, height) * 1.25f
        val sh = sw * 0.58f
        p.strokeWidth = Math.max(2f, hh * 0.035f)
        val l = (width - sw) / 2f
        val t = (hh - sh) / 2f - hh * 0.04f
        r.set(l, t, l + sw, t + sh)
        canvas.drawRoundRect(r, sh * 0.12f, sh * 0.12f, p)
        val cx = width / 2f
        canvas.drawLine(cx - sw * 0.14f, t + sh + hh * 0.07f, cx + sw * 0.14f, t + sh + hh * 0.07f, p)
    }
}
