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
 * Jednolity, zaokrąglony kafelek 16:9. Bez ramki: niezaznaczony przygaszony (70%), zaznaczony w pełnym kolorze,
 * skala 1.10 (220 ms, ease-out) i etykieta (fade 150 ms). Miękką pigułkę podświetlenia rysuje [PillHost].
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
        card.alpha = DIM
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
        val z = if (gainFocus) 1.10f else 1f
        card.animate().cancel()
        card.animate().scaleX(z).scaleY(z).alpha(if (gainFocus) 1f else DIM)
            .setDuration(Motion.MS).setInterpolator(Motion.EASE).start()
        if (showLabel) labelView.animate().alpha(if (gainFocus) 1f else 0f)
            .setDuration(Motion.LABEL_MS).setInterpolator(Motion.EASE).start()
        if (gainFocus) onFocus?.invoke(this) else onBlur?.invoke(this)
    }

    companion object {
        const val DIM = 0.7f
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
