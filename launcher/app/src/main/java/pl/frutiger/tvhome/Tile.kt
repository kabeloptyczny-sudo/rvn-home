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
 * Jednolity, zaokrąglony kafelek 16:9. Bez ramek i teł zaznaczenia: niezaznaczony przygaszony (70%), zaznaczony
 * w pełnym kolorze, skala 1.12 (220 ms, ease-out), jasny odblask górnej krawędzi, miękki cień i nazwa pod spodem.
 * Poświata jest wspólna dla kontenera ([GlowLayer]) i przesuwa się między kafelkami.
 */
class TileView(
    ctx: Context,
    private val u: Float,
    wU: Float,
    label: String,
    content: View,
    private val showLabel: Boolean = true,
    /** Ikona doku: kwadrat (promień 22%), alfa 85% w spoczynku, bez cienia i odblasku; nazwa szersza niż ikona. */
    private val dock: Boolean = false
) : LinearLayout(ctx) {

    val card = FrameLayout(ctx)
    val labelView = TextView(ctx)
    var onFocus: ((TileView) -> Unit)? = null
    var onBlur: ((TileView) -> Unit)? = null
    private val fx = SelFx(this, u)
    private val sheen = View(ctx)
    private val glassBorder = View(ctx)

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
        val h = if (dock) (w * 2 / 3) else (wU * u * 9f / 16f).toInt()
        val r = if (dock) w * 0.20f else u * 1.0f
        val rest = if (dock) Sel.DOCK_DIM else Sel.DIM

        val bgd = GradientDrawable()
        if (dock) {
            bgd.setColor(0x4D1B2027)
            bgd.cornerRadius = r
            glassBorder.background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke((u * 0.3f).toInt(), 0x2EFFFFFF)
                cornerRadius = r
            }
            card.addView(glassBorder, FrameLayout.LayoutParams(-1, -1))
        } else {
            bgd.setColor(Pal.TILE)
            bgd.cornerRadius = r
        }
        card.background = bgd
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.alpha = rest
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        // wąski, jasny odblask na górnej krawędzi (gradient 20% -> 0)
        sheen.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33FFFFFF, 0x00FFFFFF))
        sheen.alpha = 0f
        if (!dock) card.addView(sheen, FrameLayout.LayoutParams(-1, (h * 0.30f).toInt(), Gravity.TOP))
        addView(card, LayoutParams(w, h))

        labelView.text = label
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        labelView.setTextColor(Pal.TEXT)
        labelView.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        labelView.maxLines = 1
        labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        labelView.gravity = Gravity.CENTER
        labelView.alpha = 0f
        labelView.letterSpacing = 0.02f
        if (!showLabel) labelView.visibility = View.GONE
        val lp = LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (dock) {
            // nazwa może być szersza od ikony, ale nie zmienia szerokości kafelka (ujemne marginesy)
            val ex = (w * 0.45f).toInt()
            lp.width = w + 2 * ex
            lp.leftMargin = -ex
            lp.rightMargin = -ex
            labelView.setShadowLayer(u * 0.35f, 0f, u * 0.08f, 0x99000000.toInt())
        }
        lp.topMargin = (u * 0.9f).toInt()
        addView(labelView, lp)

        fx.onStep = SelStep { s ->
            val z = 1f + (Sel.SCALE - 1f) * s
            card.scaleX = z
            card.scaleY = z
            card.alpha = rest + (1f - rest) * s
            sheen.alpha = s
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fx.set(gainFocus)
        if (showLabel) {
            // nazwa zjeżdża razem z dolną krawędzią powiększonej karty
            val dy = if (gainFocus) card.height * (Sel.SCALE - 1f) / 2f else 0f
            labelView.animate().cancel()
            labelView.animate().alpha(if (gainFocus) 1f else 0f).translationY(dy)
                .setDuration(Sel.LABEL_MS).setInterpolator(Motion.EASE).start()
        }
        if (gainFocus) onFocus?.invoke(this) else onBlur?.invoke(this)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // miękki cień pod kartą (przed dziećmi, więc karta i napis leżą na nim)
        if (!dock) fx.drawShadowAt(canvas, card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        fx.cancel()
        super.onDetachedFromWindow()
    }
}

/** Ikona "TV": ramka ekranu + podstawka, jednolita linia w kolorze piasku. */
class TvGlyphView(context: Context, private val k: Float = 1.25f) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        p.style = Paint.Style.STROKE
        p.color = Pal.SAND
    }

    override fun onDraw(canvas: Canvas) {
        val hh = height.toFloat()
        val sw = Math.min(width, height) * k
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
