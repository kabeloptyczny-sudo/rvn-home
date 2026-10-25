package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Wiersz listy w stylistyce kafelków: matowe tło, po zaznaczeniu jasna obwódka i delikatna skala. */
class ListRow(ctx: Context, private val u: Float, text: String) : FrameLayout(ctx) {
    private val ring = View(ctx)

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        val bg = GradientDrawable()
        bg.setColor(Pal.TILE)
        bg.cornerRadius = u * 0.9f
        background = bg
        val rg = GradientDrawable()
        rg.setColor(0)
        rg.cornerRadius = u * 0.9f
        rg.setStroke(Math.max(2, (u * 0.22f).toInt()), Pal.RING)
        ring.background = rg
        ring.alpha = 0f
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.7f)
        t.setTextColor(Pal.TEXT)
        t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding((u * 2.2f).toInt(), (u * 1.3f).toInt(), (u * 2.2f).toInt(), (u * 1.3f).toInt())
        addView(t, LayoutParams(-1, -2))
        addView(ring, LayoutParams(-1, -1))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val z = if (gainFocus) 1.03f else 1f
        animate().scaleX(z).scaleY(z).setDuration(Motion.MS).setInterpolator(Motion.EASE).start()
        ring.animate().alpha(if (gainFocus) 1f else 0f).setDuration(Motion.MS).setInterpolator(Motion.EASE).start()
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
