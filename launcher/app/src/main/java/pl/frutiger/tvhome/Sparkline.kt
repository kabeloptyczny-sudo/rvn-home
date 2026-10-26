package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View

/**
 * Mały wykres dnia. Oś X proporcjonalna do znaczników czasu (bez czasów: równomiernie), skala Y z niepustych
 * wartości + 5% marginesu (min == max: linia pozioma na środku). Brak danych: dyskretny napis.
 * Ścieżka i położenie napisu liczone poza onDraw (przy zmianie danych/rozmiaru).
 */
class SparklineView(context: Context, strokePx: Float) : View(context) {

    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private var data = FloatArray(0)
    private var times = LongArray(0)
    private var has = false
    private var tx = 0f
    private var ty = 0f

    init {
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokePx
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        tp.color = Pal.TEXT_DIM
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    fun set(values: FloatArray, ts: LongArray, color: Int) {
        data = values
        times = ts
        p.color = color
        build()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        build()
    }

    private fun build() {
        path.rewind()
        has = false
        if (width <= 0 || height <= 0) return
        val n = data.size
        if (n < 2) {
            var sz = height * 0.55f
            tp.textSize = sz
            val tw = tp.measureText(NO_DATA)
            val avail = width * 0.96f
            if (tw > avail) { sz *= avail / tw; tp.textSize = sz }
            val fm = tp.fontMetrics
            tx = width / 2f
            ty = height / 2f - (fm.ascent + fm.descent) / 2f
            return
        }
        has = true
        var mn = data[0]
        var mx = data[0]
        for (v in data) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val pad = p.strokeWidth
        val w = width - 2f * pad
        val h = height - 2f * pad
        val range = mx - mn
        val lo = mn - range * 0.05f
        val span = (mx + range * 0.05f) - lo
        val useT = times.size == n && times[n - 1] > times[0]
        val t0 = if (useT) times[0] else 0L
        val tSpan = if (useT) (times[n - 1] - times[0]).toDouble() else (n - 1).toDouble()
        for (i in 0 until n) {
            val fx = if (useT) (times[i] - t0) / tSpan else i / tSpan
            val x = pad + w * fx.toFloat()
            val y = if (range <= 0f) pad + h / 2f else pad + h * (1f - (data[i] - lo) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (has) canvas.drawPath(path, p) else canvas.drawText(NO_DATA, tx, ty, tp)
    }

    private companion object {
        const val NO_DATA = "brak danych"
    }
}
