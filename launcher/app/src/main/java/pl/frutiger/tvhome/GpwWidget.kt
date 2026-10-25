package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
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

/** Szklany, niefokusowalny panel z notowaniami GPW. Same TextView - zero pracy w onDraw. */
class GpwWidget(ctx: Context, private val u: Float) : GlassView(ctx, u * 1.7f, hex(0x38FFFFFFL), hex(0x10FFFFFFL), true) {

    private class RowViews(val name: TextView, val value: TextView, val chg: TextView, val row: LinearLayout)

    private val rows = LinkedHashMap<String, RowViews>()
    private val status = TextView(ctx)
    private val nf = NumberFormat.getNumberInstance(Locale("pl", "PL"))
    private val clockFmt = SimpleDateFormat("HH:mm", Locale("pl", "PL"))
    private val up = hex(0xFF30D158L)
    private val down = hex(0xFFFF453AL)
    private val flat = hex(0xCCFFFFFFL)

    init {
        lens = false
        isFocusable = false
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((u * 1.6f).toInt(), (u * 1.0f).toInt(), (u * 1.6f).toInt(), (u * 1.1f).toInt())

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ctx)
        title.text = "GPW"
        txt(title, 1.25f, 0xF2FFFFFFL, true)
        title.letterSpacing = 0.08f
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        txt(status, 0.95f, 0x99FFFFFFL, false)
        head.addView(status)
        col.addView(head)

        for ((k, n) in GPW_INDICES) addRow(ctx, col, k, n)
        for (t in GPW_TICKERS) addRow(ctx, col, t, t)
        addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }

    private fun txt(t: TextView, size: Float, color: Long, medium: Boolean) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        t.setTextColor(hex(color))
        t.typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        t.includeFontPadding = false
        t.fontFeatureSettings = "tnum"
        t.setShadowLayer(u * 0.5f, 0f, u * 0.12f, hex(0x66000000L))
    }

    private fun addRow(ctx: Context, col: LinearLayout, key: String, label: String) {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val n = TextView(ctx); n.text = label; txt(n, 1.3f, 0xF2FFFFFFL, true)
        val v = TextView(ctx); txt(v, 1.3f, 0xE6FFFFFFL, false); v.gravity = Gravity.END
        val c = TextView(ctx); txt(c, 1.2f, 0xCCFFFFFFL, true); c.gravity = Gravity.END
        r.addView(n, LinearLayout.LayoutParams((u * 6.0f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(v, LinearLayout.LayoutParams((u * 6.6f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(c, LinearLayout.LayoutParams((u * 5.6f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 0.75f).toInt()
        col.addView(r, lp)
        rows[key] = RowViews(n, v, c, r)
    }

    fun bind(data: Map<String, Quote>, newest: Long, stale: Boolean, loc: Locale, tz: TimeZone) {
        clockFmt.timeZone = tz
        val time = if (newest > 0L) clockFmt.format(Date(newest)) else ""
        status.text = if (stale) "nieaktualne" + (if (time.isNotEmpty()) " · $time" else "")
        else (if (time.isNotEmpty()) "$time · " else "") + "opóźnione"
        for ((k, rv) in rows) {
            val q = data[k]
            if (q == null) {
                rv.row.visibility = View.GONE
                continue
            }
            rv.row.visibility = View.VISIBLE
            rv.value.text = nf.format(q.price)
            val p = q.changePct
            val arrow = if (p > 0.004) "▲" else if (p < -0.004) "▼" else "■"
            rv.chg.text = arrow + " " + String.format(loc, "%.2f%%", Math.abs(p))
            rv.chg.setTextColor(if (p > 0.004) up else if (p < -0.004) down else flat)
        }
    }
}
