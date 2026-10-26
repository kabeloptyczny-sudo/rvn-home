package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zegar: cienka czcionka, bez gradientów i cieni; data po polsku małymi literami. Rozmiary od u (1% szerokości). */
class ClockView(ctx: Context, u: Float, k: Float = 1f, private val greeting: Boolean = false) : LinearLayout(ctx) {

    private val time = TextView(ctx)
    private val date = TextView(ctx)
    private val weather = TextView(ctx)
    private val wish = TextView(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private val pl = Locale("pl", "PL")
    private val fDate = SimpleDateFormat("EEEE, d MMMM", pl)
    private val fTime = SimpleDateFormat("HH:mm", pl)
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 40L)
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 8.4f * k)
        time.setTextColor(Pal.TEXT)
        time.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        time.includeFontPadding = false
        time.fontFeatureSettings = "tnum"
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.9f * k)
        date.setTextColor(Pal.TEXT_DIM)
        date.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        date.letterSpacing = 0.03f
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 0.5f * k).toInt()
        lp.leftMargin = (u * 0.4f * k).toInt()
        addView(date, lp)

        if (greeting) {
            // pod datą: pogoda (ukryta bez danych) i życzenie wg pory dnia, ta sama cienka czcionka
            for (t in arrayOf(weather, wish)) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.5f * k)
                t.setTextColor(Pal.TEXT_DIM)
                t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                t.letterSpacing = 0.03f
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                val l = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                l.topMargin = (u * 0.35f * k).toInt()
                l.leftMargin = (u * 0.4f * k).toInt()
                addView(t, l)
            }
            weather.visibility = GONE
        }
        refresh()
    }

    private fun refresh() {
        val now = Date()
        time.text = fTime.format(now)
        date.text = fDate.format(now).lowercase(pl)
        if (greeting) wish.text = HomeInfo.wish()
    }

    /** Linia pogody pod datą; null = ukryj. */
    fun setWeather(text: String?) {
        if (!greeting) return
        weather.text = text ?: ""
        weather.visibility = if (text == null) GONE else VISIBLE
    }

    fun start() {
        running = true
        ui.removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }
}
