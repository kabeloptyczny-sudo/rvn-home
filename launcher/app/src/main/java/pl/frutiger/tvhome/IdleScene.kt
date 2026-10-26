package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
 * Scena bezczynności: duży zegar, po jego prawej data, pogoda i życzenie; na dole pasek bez tła z notowaniami
 * (WIG20, mWIG40, sWIG80, TEN, GPP). Wszystko dopasowane do zawartości (wrap_content, AT_MOST), nic nie jest
 * rozciągane na cały ekran. Przy zmianie wartości tylko animacja cyfr, bez podświetlenia.
 */
class IdleScene(ctx: Context, private val u: Float) : FrameLayout(ctx), QuoteSink {

    private class Cell(
        val box: LinearLayout, val value: RollingValueView, val chg: TextView, val none: TextView,
        var last: Double = Double.NaN
    )

    private val pl = Locale("pl", "PL")
    private val fDate = SimpleDateFormat("EEEE, d MMMM", pl)
    private val fTime = SimpleDateFormat("HH:mm", pl)
    private val nf = NumberFormat.getNumberInstance(pl)
    private val ui = Handler(Looper.getMainLooper())
    private val time = TextView(ctx)
    private val date = TextView(ctx)
    private val weather = TextView(ctx)
    private val wish = TextView(ctx)
    private val group = LinearLayout(ctx)
    private val cells = LinkedHashMap<String, Cell>()
    private var running = false
    private var step = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            // ochrona przed wypaleniem: co minutę grupa zegara przesuwa się o ułamek jednostki
            step++
            val dx = ((step % 3) - 1) * u * 0.5f
            val dy = (((step / 3) % 3) - 1) * u * 0.4f
            group.animate().translationX(dx).translationY(dy).setDuration(2500).start()
            ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 40L)
        }
    }

    init {
        clipChildren = false
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2

        // zegar + kolumna informacji po prawej
        group.orientation = LinearLayout.HORIZONTAL
        group.gravity = Gravity.CENTER_VERTICAL
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 15f)
        time.setTextColor(Pal.TEXT)
        time.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        time.includeFontPadding = false
        time.fontFeatureSettings = "tnum"
        group.addView(time, LinearLayout.LayoutParams(-2, -2))

        val info = LinearLayout(ctx)
        info.orientation = LinearLayout.VERTICAL
        style(date, 2.5f, Pal.TEXT)
        style(weather, 2.1f, Pal.TEXT_DIM)
        style(wish, 2.1f, Pal.TEXT_DIM)
        weather.visibility = View.GONE
        info.addView(date, LinearLayout.LayoutParams(-2, -2))
        for (t in arrayOf(weather, wish)) {
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = (u * 0.7f).toInt()
            info.addView(t, lp)
        }
        val ilp = LinearLayout.LayoutParams(-2, -2)
        ilp.leftMargin = (u * 3.2f).toInt()
        group.addView(info, ilp)

        val glp = LayoutParams(-2, -2, Gravity.CENTER)
        glp.bottomMargin = (Backdrop.screenH * 0.14f).toInt()
        addView(group, glp)

        // dolny pasek notowań (bez tła)
        val strip = LinearLayout(ctx)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.BOTTOM
        var first = true
        for ((k, n) in GPW_CARDS) {
            val cell = makeCell(ctx, n)
            val lp = LinearLayout.LayoutParams(-2, -2)
            if (!first) lp.leftMargin = (u * 3.4f).toInt()
            first = false
            strip.addView(cell.box, lp)
            cells[k] = cell
        }
        val slp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        slp.bottomMargin = (Backdrop.screenH * 0.065f).toInt()
        addView(strip, slp)
        refresh()
    }

    private fun style(t: TextView, size: Float, color: Int) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        t.setTextColor(color)
        t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        t.letterSpacing = 0.03f
        t.includeFontPadding = false
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun makeCell(ctx: Context, name: String): Cell {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val n = TextView(ctx)
        n.text = name
        n.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        n.setTextColor(Pal.TEXT_DIM)
        n.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        n.includeFontPadding = false
        box.addView(n, LinearLayout.LayoutParams(-2, -2))

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val v = RollingValueView(ctx, u * 1.9f, "sans-serif-light", false)
        v.highlight = false
        val c = TextView(ctx)
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        c.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.includeFontPadding = false
        c.fontFeatureSettings = "tnum"
        c.setTextColor(Pal.FLAT)
        val none = TextView(ctx)
        none.text = "brak danych"
        none.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.3f)
        none.setTextColor(Pal.TEXT_DIM)
        none.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        none.includeFontPadding = false
        row.addView(v, LinearLayout.LayoutParams(-2, -2))
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = (u * 0.9f).toInt()
        row.addView(c, clp)
        row.addView(none, LinearLayout.LayoutParams(-2, -2))
        box.addView(row, LinearLayout.LayoutParams(-2, -2))
        v.visibility = View.GONE
        c.visibility = View.GONE
        return Cell(box, v, c, none)
    }

    /** Odświeża zegar, datę i życzenie (pogoda przez [refreshInfo]). */
    private fun refresh() {
        val now = Date()
        time.text = fTime.format(now)
        date.text = fDate.format(now).lowercase(pl)
        wish.text = HomeInfo.idleWish()
        refreshInfo()
    }

    fun refreshInfo() {
        val w = HomeInfo.weather
        weather.text = w ?: ""
        weather.visibility = if (w == null) View.GONE else View.VISIBLE
    }

    fun start() {
        running = true
        ui.removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
        group.animate().cancel()
    }

    override fun bind(data: Map<String, Quote>, newest: Long, stale: Boolean, loc: Locale, tz: TimeZone) {
        for ((k, cell) in cells) {
            val q = data[k]
            if (q == null) {
                cell.value.visibility = View.GONE
                cell.chg.visibility = View.GONE
                cell.none.visibility = View.VISIBLE
                continue
            }
            cell.none.visibility = View.GONE
            cell.value.visibility = View.VISIBLE
            cell.chg.visibility = View.VISIBLE
            cell.value.set(nf.format(q.price), 0)       // tylko animacja cyfr (roll/fade), bez podświetlenia
            cell.last = q.price
            val p = q.changePct
            val up = p > 0.004
            val down = p < -0.004
            cell.chg.setTextColor(if (up) Pal.UP else if (down) Pal.DOWN else Pal.FLAT)
            cell.chg.text = (if (up) "▲ " else if (down) "▼ " else "– ") + String.format(loc, "%.2f%%", Math.abs(p))
        }
    }
}
