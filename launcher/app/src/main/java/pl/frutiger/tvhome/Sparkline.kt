package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Mały wykres dnia. Ścieżka budowana poza onDraw (przy zmianie danych/rozmiaru). */
class SparklineView(context: Context, strokePx: Float) : View(context) {

    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var data = FloatArray(0)
    private var has = false

    init {
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokePx
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    fun set(values: FloatArray, color: Int) {
        data = values
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
        has = data.size >= 2 && width > 0 && height > 0
        if (!has) return
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
        for (i in data.indices) {
            val x = pad + w * i / (data.size - 1)
            val y = if (range <= 0f) pad + h / 2f else pad + h * (1f - (data[i] - mn) / range)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (has) canvas.drawPath(path, p)
    }
}
