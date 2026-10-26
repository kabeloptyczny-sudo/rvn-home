package pl.frutiger.tvhome

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Poziomy rząd o równych odstępach na całą szerokość (space-between) z elementami wyśrodkowanymi w pionie.
 * Kolejność i liczba dzieci bez zmian, więc działa z indeksami używanymi przy przestawianiu ikon.
 */
class SpreadRow(ctx: Context) : LinearLayout(ctx) {
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var cnt = 0
        var sum = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            cnt++
            sum += c.measuredWidth
        }
        if (cnt == 0) return
        val availW = r - l - paddingLeft - paddingRight
        val availH = b - t - paddingTop - paddingBottom
        val free = Math.max(0, availW - sum).toFloat()
        val gap = if (cnt > 1) free / (cnt - 1) else 0f
        var x = paddingLeft + if (cnt == 1) free / 2f else 0f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val mw = c.measuredWidth
            val mh = c.measuredHeight
            val top = paddingTop + (availH - mh) / 2
            val xi = Math.round(x)
            c.layout(xi, top, xi + mw, top + mh)
            x += mw + gap
        }
    }
}
