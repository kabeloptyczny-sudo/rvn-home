package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Wiersz listy w języku zaznaczenia kafelków: bez tła i ramki, przygaszony 70% -> 100%, skala, miękka poświata pod spodem. */
class ListRow(ctx: Context, private val u: Float, text: String) : FrameLayout(ctx) {
    private val fx = SelFx(this, u)
    private val label = TextView(ctx)

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        clipChildren = false
        label.text = text
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.7f)
        label.setTextColor(Pal.TEXT)
        label.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        label.gravity = Gravity.CENTER_VERTICAL
        label.alpha = Sel.DIM
        label.setPadding((u * 2.2f).toInt(), (u * 1.3f).toInt(), (u * 2.2f).toInt(), (u * 1.3f).toInt())
        addView(label, LayoutParams(-1, -2))
        fx.onStep = SelStep { s ->
            val z = 1f + 0.06f * s
            scaleX = z
            scaleY = z
            label.alpha = Sel.DIM + (1f - Sel.DIM) * s
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pivotX = u * 1.2f
        pivotY = h / 2f
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fx.set(gainFocus)
    }

    override fun dispatchDraw(canvas: Canvas) {
        fx.drawGlow(canvas, width.toFloat(), height.toFloat())
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        fx.cancel()
        super.onDetachedFromWindow()
    }
}

/** Panel wyboru wejścia TV. Zwraca (widok, pierwszy wiersz do fokusu). */
fun buildInputPicker(ctx: Context, u: Float, items: List<TvInputs.Item>, onPick: (TvInputs.Item) -> Unit): Pair<View, View?> {
    val holder = FrameLayout(ctx)
    val panel = FrostPanel(ctx, u * 2f, hex(0x66161B22L), hex(0x33FFFFFFL), Math.max(1f, u * 0.055f))
    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding((u * 2.6f).toInt(), (u * 2.4f).toInt(), (u * 2.6f).toInt(), (u * 2.6f).toInt())
    col.clipChildren = false
    val title = TextView(ctx)
    title.text = "wejścia"
    title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.2f)
    title.setTextColor(Pal.TEXT_DIM)
    title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    title.letterSpacing = 0.06f
    col.addView(title)
    var first: View? = null
    for (it in items) {
        val r = ListRow(ctx, u, it.label)
        r.setOnClickListener { _ -> onPick(it) }
        val lp = LinearLayout.LayoutParams((u * 30f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 1.1f).toInt()
        col.addView(r, lp)
        if (first == null) first = r
    }
    panel.addView(col, FrameLayout.LayoutParams(-2, -2))
    holder.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    return Pair(holder, first)
}
