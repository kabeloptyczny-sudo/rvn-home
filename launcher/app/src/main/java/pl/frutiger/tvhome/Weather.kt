package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.ExecutorService

/** Współrzędne Raciborza - jedna stała. */
const val WEATHER_LAT = 50.0919
const val WEATHER_LON = 18.2194

class WeatherNow(val temp: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int)

/** Ikona pogody rysowana kodem (statyczna, odświeżana tylko przy zmianie danych). */
class WeatherIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var code = 0
    private var day = true

    fun set(code: Int, day: Boolean) {
        this.code = code
        this.day = day
        invalidate()
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.color = col
        c.drawCircle(cx - s * 0.28f, cy, s * 0.26f, p)
        c.drawCircle(cx + s * 0.05f, cy - s * 0.12f, s * 0.32f, p)
        c.drawCircle(cx + s * 0.34f, cy + s * 0.02f, s * 0.22f, p)
        r.set(cx - s * 0.54f, cy, cx + s * 0.56f, cy + s * 0.26f)
        c.drawRoundRect(r, s * 0.13f, s * 0.13f, p)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.FILL
        p.color = hex(0xFFFFD25AL)
        c.drawCircle(cx, cy, s * 0.22f, p)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.07f
        for (i in 0 until 8) {
            c.save()
            c.rotate(i * 45f, cx, cy)
            c.drawLine(cx, cy - s * 0.33f, cx, cy - s * 0.44f, p)
            c.restore()
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.FILL
        p.color = hex(0xFFE9EEFFL)
        c.drawCircle(cx, cy, s * 0.26f, p)
        p.color = hex(0xFF2A3350L)
        c.drawCircle(cx + s * 0.12f, cy - s * 0.07f, s * 0.23f, p)
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val white = hex(0xF2FFFFFFL)
        val grey = hex(0xCCDDE4F0L)
        when {
            code == 0 || code == 1 -> if (day) sun(c, cx, cy, s) else moon(c, cx, cy, s)
            code == 2 -> {
                if (day) sun(c, cx - s * 0.12f, cy - s * 0.12f, s * 0.8f) else moon(c, cx - s * 0.12f, cy - s * 0.12f, s * 0.8f)
                cloud(c, cx + s * 0.04f, cy + s * 0.08f, s * 0.82f, white)
            }
            code == 3 -> cloud(c, cx, cy, s, grey)
            code == 45 || code == 48 -> {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = s * 0.07f; p.color = grey
                for (i in 0 until 4) c.drawLine(cx - s * 0.32f, cy - s * 0.2f + i * s * 0.14f, cx + s * 0.32f - (i % 2) * s * 0.1f, cy - s * 0.2f + i * s * 0.14f, p)
            }
            code in 71..77 || code == 85 || code == 86 -> {
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, grey)
                p.style = Paint.Style.FILL; p.color = white
                for (i in 0 until 3) c.drawCircle(cx - s * 0.22f + i * s * 0.22f, cy + s * 0.3f + (i % 2) * s * 0.06f, s * 0.035f, p)
            }
            code >= 95 -> {
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, hex(0xFFA9B4C8L))
                p.style = Paint.Style.FILL; p.color = hex(0xFFFFD25AL)
                c.drawRect(cx - s * 0.04f, cy + s * 0.2f, cx + s * 0.06f, cy + s * 0.32f, p)
                c.drawRect(cx - s * 0.1f, cy + s * 0.3f, cx + s * 0.02f, cy + s * 0.42f, p)
            }
            else -> {   // deszcz / mżawka / przelotne
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, grey)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = s * 0.05f; p.color = hex(0xFF7CC4FFL)
                for (i in 0 until 3) c.drawLine(cx - s * 0.22f + i * s * 0.22f, cy + s * 0.22f, cx - s * 0.28f + i * s * 0.22f, cy + s * 0.36f, p)
            }
        }
    }
}

/** Szklany widżet pogody: ikona, temperatura, opis, min/max. Ukryty, gdy brak danych. */
class WeatherWidget(ctx: Context, private val u: Float) : GlassView(ctx, u * 1.9f, hex(0x38FFFFFFL), hex(0x10FFFFFFL), true) {

    private val icon = WeatherIcon(ctx)
    private val temp = TextView(ctx)
    private val desc = TextView(ctx)
    private val range = TextView(ctx)

    init {
        lens = false
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding((u * 1.5f).toInt(), (u * 0.9f).toInt(), (u * 2.0f).toInt(), (u * 0.9f).toInt())
        row.addView(icon, LinearLayout.LayoutParams((u * 4.4f).toInt(), (u * 4.4f).toInt()))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        temp.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 3.0f)
        temp.setTextColor(hex(0xFFFFFFFFL))
        temp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        temp.includeFontPadding = false
        desc.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        desc.setTextColor(hex(0xE6FFFFFFL))
        desc.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        range.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.0f)
        range.setTextColor(hex(0xAAFFFFFFL))
        for (t in arrayOf(temp, desc, range)) t.setShadowLayer(u * 0.5f, 0f, u * 0.12f, hex(0x66000000L))
        col.addView(temp)
        col.addView(desc)
        col.addView(range)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (u * 1.1f).toInt()
        row.addView(col, lp)
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }

    private fun describe(c: Int): String = when {
        c == 0 -> "Bezchmurnie"
        c == 1 -> "Prawie bezchmurnie"
        c == 2 -> "Częściowe zachmurzenie"
        c == 3 -> "Pochmurno"
        c == 45 || c == 48 -> "Mgła"
        c in 51..57 -> "Mżawka"
        c in 61..67 -> "Deszcz"
        c in 71..77 -> "Śnieg"
        c in 80..82 -> "Przelotny deszcz"
        c == 85 || c == 86 -> "Przelotny śnieg"
        c >= 95 -> "Burza"
        else -> "Zmienna pogoda"
    }

    fun bind(w: WeatherNow?) {
        if (w == null) {
            visibility = View.GONE
            return
        }
        icon.set(w.code, w.day)
        temp.text = "${w.temp}°"
        desc.text = describe(w.code)
        range.text = "↑ ${w.hi}°   ↓ ${w.lo}°   Racibórz"
        visibility = View.VISIBLE
    }
}

/** Open-Meteo, co 30 min, na wspólnym executorze. Błąd sieci = widżet ukryty. */
class WeatherController(
    private val worker: ExecutorService,
    private val ui: Handler,
    private val widget: WeatherWidget
) {
    private var running = false
    private var busy = false

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            fetch()
            ui.postDelayed(this, 1_800_000L)
        }
    }

    fun start() {
        running = true
        ui.removeCallbacks(loop)
        ui.postDelayed(loop, 600L)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(loop)
    }

    private fun fetch() {
        if (busy) return
        busy = true
        worker.execute {
            val res: WeatherNow? = try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$WEATHER_LAT&longitude=$WEATHER_LON" +
                        "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                        "&timezone=Europe%2FWarsaw&forecast_days=1"
                val o = JSONObject(Net.get(url))
                val cur = o.getJSONObject("current")
                val d = o.getJSONObject("daily")
                WeatherNow(
                    Math.round(cur.getDouble("temperature_2m")).toInt(),
                    cur.getInt("weather_code"),
                    cur.optInt("is_day", 1) == 1,
                    Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
                )
            } catch (e: Exception) {
                null
            }
            ui.post {
                busy = false
                widget.bind(res)
            }
        }
    }
}
