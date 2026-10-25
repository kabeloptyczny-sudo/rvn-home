package pl.frutiger.tvhome

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class GradText(ctx: Context) : TextView(ctx) {
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (h > 0) {
            paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(hex(0xFFFFFFFFL), hex(0xF2F4F8FFL), hex(0xB8CFDCFFL)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
        }
    }
}

/**
 * Duża, gruba, zaokrąglona godzina z gradientem "szkła" i miękkim cieniem + data po polsku.
 * Zaokrąglenie: FILL_AND_STROKE z okrągłym łączeniem. Rozmiary od jednostki u (1% szerokości).
 */
class GlassClockView(ctx: Context, u: Float, k: Float = 1f) : LinearLayout(ctx) {

    val dateRow = LinearLayout(ctx)
    private val time = GradText(ctx)
    private val date = TextView(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private val fDate = SimpleDateFormat("EEEE, d MMMM", Locale("pl", "PL"))
    private val fTime = SimpleDateFormat("HH:mm", Locale("pl", "PL"))
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
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 9.4f * k)
        time.setTextColor(-1)
        time.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 700, false) else Typeface.DEFAULT_BOLD
        time.includeFontPadding = false
        time.letterSpacing = -0.015f
        time.paint.style = Paint.Style.FILL_AND_STROKE
        time.paint.strokeJoin = Paint.Join.ROUND
        time.paint.strokeWidth = u * 0.30f * k
        time.setShadowLayer(u * 1.6f * k, 0f, u * 0.45f * k, hex(0x66000000L))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.0f * k)
        date.setTextColor(hex(0xDDFFFFFFL))
        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        date.setShadowLayer(u * 0.8f * k, 0f, u * 0.2f * k, hex(0x66000000L))
        dateRow.orientation = HORIZONTAL
        dateRow.gravity = Gravity.CENTER_VERTICAL
        dateRow.clipChildren = false
        dateRow.addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = (u * 0.3f * k).toInt()
        lp.leftMargin = (u * 0.5f * k).toInt()
        addView(dateRow, lp)
        refresh()
    }

    private fun refresh() {
        val now = Date()
        time.text = fTime.format(now)
        val d = fDate.format(now)
        date.text = d.substring(0, 1).uppercase(Locale("pl", "PL")) + d.substring(1)
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
