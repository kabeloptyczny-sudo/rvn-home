package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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

/**
 * Szklany kafelek 16:9 w stylu Apple TV. Niezaznaczony przygaszony, zaznaczony w pełnym kolorze, skala 1.12
 * (220 ms, ease-out), połysk + ramka + smuga światła ([TileGlassView]), miękki cień i nazwa pod spodem.
 * Tło kafelka bez banera to gradient w kolorze ikony. Poświata jest wspólna dla kontenera ([GlowLayer]).
 */
class TileView(
    ctx: Context,
    private val u: Float,
    wU: Float,
    label: String,
    content: View,
    private val showLabel: Boolean = true,
    tint: Int = 0
) : LinearLayout(ctx) {

    val card = FrameLayout(ctx)
    val labelView = TextView(ctx)
    var onFocus: ((TileView) -> Unit)? = null
    var onBlur: ((TileView) -> Unit)? = null
    private val fx = SelFx(this, u)
    private val glass = TileGlassView(ctx, u * 1.45f, Math.max(1f, u * 0.1f))

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
        val r = u * 1.45f

        val bgd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(TileArt.top(tint), TileArt.bottom(tint)))
        bgd.cornerRadius = r
        card.background = bgd
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.alpha = Sel.DIM
        card.addView(content, FrameLayout.LayoutParams(-1, -1))
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))
        addView(card, LayoutParams(w, h))

        labelView.text = label
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.3f)
        labelView.setTextColor(Pal.TEXT)
        labelView.setShadowLayer(u * 0.5f, 0f, u * 0.12f, 0xAA000000.toInt())
        labelView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labelView.maxLines = 1
        labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        labelView.gravity = Gravity.CENTER
        labelView.alpha = 0f
        labelView.letterSpacing = 0.02f
        if (!showLabel) labelView.visibility = View.GONE
        val lp = LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 0.9f).toInt()
        addView(labelView, lp)

        fx.onStep = SelStep { s ->
            val z = 1f + (Sel.SCALE - 1f) * s
            card.scaleX = z
            card.scaleY = z
            card.alpha = Sel.DIM + (1f - Sel.DIM) * s
            glass.sel = s
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fx.set(gainFocus)
        if (gainFocus) glass.sweep() else glass.rest()
        if (showLabel) {
            // nazwa zjeżdża razem z dolną krawędzią powiększonej karty
            val dy = if (gainFocus) card.height * (Sel.SCALE - 1f) / 2f else 0f
            labelView.animate().cancel()
            labelView.animate().alpha(if (gainFocus) 1f else 0f).translationY(dy)
                .setDuration(Sel.LABEL_MS).setInterpolator(Motion.EASE).start()
        }
        if (gainFocus) onFocus?.invoke(this) else onBlur?.invoke(this)
    }

    /** Naciśnięcie: kafelek lekko się "wciska" (70 ms), potem uruchamia akcję i wraca (180 ms). */
    fun press(action: () -> Unit) {
        card.animate().cancel()
        card.animate().scaleX(fx.z * 0.94f).scaleY(fx.z * 0.94f)
            .setDuration(70L).setInterpolator(Motion.EASE)
            .withEndAction {
                action()
                card.animate().scaleX(fx.z).scaleY(fx.z)
                    .setDuration(180L).setInterpolator(Motion.EASE).start()
            }
            .start()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // miękki cień pod kartą (przed dziećmi, więc karta i napis leżą na nim)
        fx.drawShadowAt(canvas, card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        fx.cancel()
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
