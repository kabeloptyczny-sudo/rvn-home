package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wąski, szklany, fokusowalny panel (OK = widok szczegółowy): WIG20, mWIG40, sWIG80.
 * Dopasowany do zawartości (max 35% x 35% ekranu). Wartości z animacją cyfr i zielonym/czerwonym podświetleniem
 * przy zmianie (znika w ok. 3 s). Zaznaczenie: wspólny język ([SelFx]).
 */
class GpwWidget(ctx: Context, private val u: Float) :
    FrostPanel(ctx, u * 1.5f, hex(0x4D1B2027L), hex(0x2EFFFFFFL), Math.max(1f, u * 0.055f)), QuoteSink {

    private class RowViews(
        val name: TextView, val value: RollingValueView, val chg: TextView,
        val spark: SparklineView, val row: LinearLayout, var last: Double = Double.NaN
    )

    private val rows = LinkedHashMap<String, RowViews>()
    private val status = TextView(ctx)
    private val content = LinearLayout(ctx)
    private val sfx = SelFx(this, u)
    var onOpen: (() -> Unit)? = null
    /** Zmiana widoczności (do przepięcia nawigacji fokusem). */
    var onShown: (() -> Unit)? = null
    private val pl = Locale("pl", "PL")
    private val nf = NumberFormat.getNumberInstance(pl)
    private val clockFmt = SimpleDateFormat("HH:mm", pl)

    init {
        fx = sfx
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        setOnClickListener { onOpen?.invoke() }
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        val col = content
        col.alpha = Sel.DIM
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((u * 1.6f).toInt(), (u * 1.2f).toInt(), (u * 1.6f).toInt(), (u * 1.1f).toInt())
        for ((k, n) in GPW_INDICES) addRow(ctx, col, k, n)
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 0.95f)
        status.setTextColor(Pal.TEXT_DIM)
        status.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        status.includeFontPadding = false
        status.gravity = Gravity.END
        val sp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sp.topMargin = (u * 0.7f).toInt()
        col.addView(status, sp)
        addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        maxWFrac = 0.35f
        maxHFrac = 0.35f
        visibility = View.GONE
        sfx.onStep = SelStep { s ->
            val z = 1f + (Sel.SCALE - 1f) * s
            scaleX = z
            scaleY = z
            content.alpha = Sel.DIM + (1f - Sel.DIM) * s
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pivotX = w.toFloat()
        pivotY = 0f
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        sfx.set(gainFocus)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) onShown?.invoke()
    }

    override fun onDetachedFromWindow() {
        sfx.cancel()
        super.onDetachedFromWindow()
    }

    private fun txt(t: TextView, size: Float, color: Int, face: String) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        t.setTextColor(color)
        t.typeface = Typeface.create(face, Typeface.NORMAL)
        t.includeFontPadding = false
        t.fontFeatureSettings = "tnum"
    }

    private fun addRow(ctx: Context, col: LinearLayout, key: String, label: String) {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val n = TextView(ctx); n.text = label; txt(n, 1.15f, Pal.TEXT_DIM, "sans-serif")
        val v = RollingValueView(ctx, u * 1.7f, "sans-serif-light", true)
        val c = TextView(ctx); txt(c, 1.1f, Pal.FLAT, "sans-serif"); c.gravity = Gravity.END
        val s = SparklineView(ctx, Math.max(1.5f, u * 0.10f))
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        r.addView(n, LinearLayout.LayoutParams((u * 4.6f).toInt(), wrap))
        r.addView(v, LinearLayout.LayoutParams((u * 7.4f).toInt(), wrap))
        r.addView(c, LinearLayout.LayoutParams((u * 5.4f).toInt(), wrap))
        val slp = LinearLayout.LayoutParams((u * 4.8f).toInt(), (u * 1.7f).toInt())
        slp.leftMargin = (u * 1.1f).toInt()
        r.addView(s, slp)
        val lp = LinearLayout.LayoutParams(wrap, wrap)
        lp.topMargin = (u * 0.6f).toInt()
        col.addView(r, lp)
        rows[key] = RowViews(n, v, c, s, r)
    }

    override fun bind(data: Map<String, Quote>, newest: Long, stale: Boolean, loc: Locale, tz: TimeZone) {
        var any = false
        clockFmt.timeZone = tz
        val time = if (newest > 0L) clockFmt.format(Date(newest)) else ""
        status.text = if (stale) "nieaktualne" + (if (time.isNotEmpty()) " · $time" else "") else "opóźnione"
        for ((k, rv) in rows) {
            val q = data[k]
            if (q == null) {
                rv.row.visibility = View.GONE
                continue
            }
            any = true
            rv.row.visibility = View.VISIBLE
            val flash = if (!rv.last.isNaN() && Math.abs(q.price - rv.last) > 1e-9) (if (q.price > rv.last) 1 else -1) else 0
            rv.value.set(nf.format(q.price), flash)
            rv.last = q.price
            val p = q.changePct
            val up = p > 0.004
            val down = p < -0.004
            val color = if (up) Pal.UP else if (down) Pal.DOWN else Pal.FLAT
            val sb = SpannableStringBuilder(if (up) "▲" else if (down) "▼" else "–")
            sb.setSpan(RelativeSizeSpan(0.6f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ').append(String.format(loc, "%.2f%%", Math.abs(p)))
            rv.chg.text = sb
            rv.chg.setTextColor(color)
            rv.spark.set(q.series, q.ts, if (up) Pal.UP else if (down) Pal.DOWN else Pal.SAND)
            rv.spark.visibility = View.VISIBLE
        }
        visibility = if (any) View.VISIBLE else View.GONE
    }
}
