package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

class GpwDetailView(
    ctx: Context,
    private val u: Float,
    private val worker: ExecutorService,
    private val ui: Handler,
    private val source: QuoteSource
) : FrameLayout(ctx) {

    private val pl = Locale("pl", "PL")
    private val nf = NumberFormat.getNumberInstance(pl)
    private val tz = TimeZone.getTimeZone("Europe/Warsaw")
    private var chartData: ChartData? = null
    private var running = false
    private var cursorIndex = -1
    
    private val tabs = LinearLayout(ctx)
    private val chartView = DetailChartView(ctx, u)
    private val infoPanel = LinearLayout(ctx)
    private val priceText = TextView(ctx)
    private val changeText = TextView(ctx)
    private val volText = TextView(ctx)
    
    private val keys = GPW_CARDS.map { it.first }
    private var currentKey = ""
    private var currentIdx = 0
    
    private val refreshTask = object : Runnable {
        override fun run() {
            if (running) {
                refresh()
                val inSession = GpwClock.inSession()
                val delay = if (inSession) 15000L else 60000L
                ui.postDelayed(this, delay)
            }
        }
    }
    
    init {
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        
        clipChildren = false
        
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding((u * 1.5f).toInt(), (u * 1f).toInt(), (u * 1.5f).toInt(), (u * 1f).toInt())
        
        for (i in GPW_CARDS.indices) {
            val (k, n) = GPW_CARDS[i]
            val t = TextView(ctx)
            t.text = n
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 1.2f)
            t.setTextColor(Pal.TEXT_DIM)
            t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            t.includeFontPadding = false
            t.gravity = Gravity.CENTER
            t.setPadding((u * 1.2f).toInt(), (u * 0.6f).toInt(), (u * 1.2f).toInt(), (u * 0.6f).toInt())
            t.setOnClickListener { 
                currentIdx = i
                currentKey = k
                refresh()
            }
            t.id = View.generateViewId()
            tabs.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        
        infoPanel.orientation = LinearLayout.VERTICAL
        infoPanel.setPadding((u * 2f).toInt(), (u * 1.5f).toInt(), (u * 2f).toInt(), (u * 1.5f).toInt())
        
        priceText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 2.5f)
        priceText.setTextColor(Pal.TEXT)
        priceText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        priceText.includeFontPadding = false
        
        changeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 1.3f)
        changeText.setTextColor(Pal.FLAT)
        changeText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        changeText.includeFontPadding = false
        
        volText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 1.1f)
        volText.setTextColor(Pal.TEXT_DIM)
        volText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        volText.includeFontPadding = false
        
        infoPanel.addView(priceText)
        infoPanel.addView(changeText)
        infoPanel.addView(volText)
        
        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL
        container.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(chartView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(infoPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        
        addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        
        isFocusable = true
        isFocusableInTouchMode = true
        
        currentKey = keys[0]
    }
    
    fun start() {
        running = true
        ui.removeCallbacks(refreshTask)
        refresh()
        ui.postDelayed(refreshTask, 15000L)
    }
    
    fun stop() {
        running = false
        ui.removeCallbacks(refreshTask)
    }
    
    fun handleBack(): Boolean {
        if (cursorIndex >= 0) {
            cursorIndex = -1
            chartView.setCursor(-1)
            return true
        }
        return false
    }
    
    private fun refresh() {
        if (!running) return
        worker.execute {
            try {
                val cd = source.fetchChart(currentKey, ChartRange.D1)
                ui.post {
                    chartData = cd
                    if (cd != null) {
                        updateInfo(cd)
                        chartView.setData(cd)
                        (tabs.getChildAt(currentIdx) as? TextView)?.setTextColor(Pal.TEXT)
                    } else {
                        Log.e("GpwDetail", "Brak danych dla $currentKey")
                        chartView.setData(null)
                    }
                }
            } catch (e: Exception) {
                Log.e("GpwDetail", "Błąd pobierania: ${e.message}")
                ui.post { chartView.setData(null) }
            }
        }
    }
    
    private fun updateInfo(cd: ChartData) {
        priceText.text = nf.format(cd.price)
        val pct = (cd.price - cd.prev) / cd.prev * 100.0
        val up = pct > 0.004
        val down = pct < -0.004
        changeText.text = if (up) "▲ %.2f%%".format(Math.abs(pct)) else if (down) "▼ %.2f%%".format(Math.abs(pct)) else "– %.2f%%".format(Math.abs(pct))
        changeText.setTextColor(if (up) Pal.UP else if (down) Pal.DOWN else Pal.FLAT)
        volText.text = "Obrót: ${fmtTurnover(cd.turnover)}  ·  Wolumen: ${fmtVolume(cd.volToday)}"
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val cd = chartData ?: return super.dispatchKeyEvent(event)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (cursorIndex > 0) {
                        cursorIndex--
                        chartView.setCursor(cursorIndex)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (cursorIndex < cd.t.size - 1) {
                        cursorIndex++
                        chartView.setCursor(cursorIndex)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    
    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
    
    class DetailChartView(ctx: Context, private val u: Float) : View(ctx) {
        private val linePath = Path()
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val volPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cursorRect = RectF()
        private val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale("pl", "PL"))
        
        private var data: ChartData? = null
        private var cursorIdx = -1
        
        init {
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
        }
        
        init {
            linePaint.style = Paint.Style.STROKE
            linePaint.strokeWidth = u * 0.12f
            linePaint.strokeCap = Paint.Cap.ROUND
            linePaint.strokeJoin = Paint.Join.ROUND
            
            volPaint.style = Paint.Style.FILL
            volPaint.alpha = 0x40
            
            cursorPaint.style = Paint.Style.STROKE
            cursorPaint.strokeWidth = u * 0.08f
            cursorPaint.color = Pal.TEXT_DIM
            
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        
        fun setData(cd: ChartData?) {
            data = cd
            invalidate()
        }
        
        fun setCursor(idx: Int) {
            cursorIdx = idx
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            val cd = data
            if (cd == null || cd.t.size < 2) {
                textPaint.color = Pal.TEXT_DIM
                textPaint.textSize = u * 1.5f
                canvas.drawText("Brak danych", width / 2f, height / 2f, textPaint)
                return
            }
            
            val pad = u * 0.5f
            val w = width - 2f * pad
            val h = height - 2f * pad
            val volH = h * 0.2f
            val priceH = h - volH - u * 1f
            
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (v in cd.v) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val range = mx - mn
            val lo = if (range <= 0f) mn - 0.05f * Math.abs(mn) else mn - range * 0.05f
            val hi = if (range <= 0f) mx + 0.05f * Math.abs(mx) else mx + range * 0.05f
            val span = hi - lo
            
            val t0 = cd.t[0].toDouble()
            val tSpan = (cd.t[cd.t.size - 1] - t0).toDouble()
            
            linePath.rewind()
            for (i in cd.t.indices) {
                val fx = (cd.t[i] - t0) / tSpan
                val x = pad + w * fx.toFloat()
                val y = if (span <= 0f) pad + priceH / 2f else pad + priceH * (1f - (cd.v[i] - lo) / span)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            
            val up = cd.price >= cd.prev
            linePaint.color = if (up) Pal.UP else Pal.DOWN
            canvas.drawPath(linePath, linePaint)
            
            if (cd.hasVol) {
                val maxVol = cd.vol.maxOrNull() ?: 1f
                for (i in cd.t.indices) {
                    val fx = (cd.t[i] - t0) / tSpan
                    val x = pad + w * fx.toFloat()
                    val vw = w / cd.t.size * 0.8f
                    val vh = (cd.vol[i] / maxVol) * volH
                    val vy = pad + priceH + u * 0.5f + volH - vh
                    cursorRect.set(x - vw / 2f, vy, x + vw / 2f, pad + priceH + u * 0.5f + volH)
                    volPaint.color = if (cd.v[i] > 0) if (up) Pal.UP else Pal.DOWN else Pal.FLAT
                    canvas.drawRect(cursorRect, volPaint)
                }
            }
            
            if (cursorIdx >= 0 && cursorIdx < cd.t.size) {
                val fx = (cd.t[cursorIdx] - t0) / tSpan
                val cx = pad + w * fx.toFloat()
                canvas.drawLine(cx, pad, cx, pad + priceH + volH, cursorPaint)
                
                textPaint.color = Pal.TEXT
                textPaint.textSize = u * 1.1f
                val priceStr = nf.format(cd.v[cursorIdx].toDouble())
                canvas.drawText(priceStr, cx, pad + priceH * 0.3f, textPaint)
            }
        }
    }
}
