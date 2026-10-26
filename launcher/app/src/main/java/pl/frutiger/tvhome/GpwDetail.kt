package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min

private fun mixC(a: Int, b: Int, f: Float): Int {
    val ia = (f.coerceIn(0f, 1f) * 256f).toInt()
    val r = ((a shr 16) and 255) + ((((b shr 16) and 255) - ((a shr 16) and 255)) * ia shr 8)
    val g = ((a shr 8) and 255) + ((((b shr 8) and 255) - ((a shr 8) and 255)) * ia shr 8)
    val bl = (a and 255) + (((b and 255) - (a and 255)) * ia shr 8)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

/**
 * Wartość: cienka czcionka, cyfry tabelaryczne. Zmieniające się cyfry przechodzą płynnie (roll/fade).
 * Przy [highlight] cała wartość dodatkowo świeci na zielono (wzrost) lub czerwono (spadek) i wygasa w ok. 3 s.
 * Bez alokacji w onDraw i w pętli animacji (animacja ~20 fps przy samym podświetleniu, przez [Ticker]).
 */
class RollingValueView(
    context: Context,
    private val sizePx: Float,
    face: String = "sans-serif-thin",
    private val alignEnd: Boolean = false
) : View(context), Tick {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cur = CharArray(0)
    private var old = CharArray(0)
    private var changed = BooleanArray(0)
    private var roll = 1f
    private var flashP = 1f
    private var flashColor = Pal.TEXT
    private var acc = 0f
    private val wid = FloatArray(48)
    private val dy = sizePx * 0.22f

    /** false = tylko animacja cyfr, bez zielonego/czerwonego podświetlenia (scena bezczynności). */
    var highlight = true

    init {
        p.textSize = sizePx
        p.color = Pal.TEXT
        p.typeface = Typeface.create(face, Typeface.NORMAL)
        p.fontFeatureSettings = "tnum"
    }

    /** @param flash 1 = wzrost (zielony), -1 = spadek (czerwony), 0 = bez rozbłysku */
    fun set(text: String, flash: Int) {
        val nc = text.toCharArray()
        val n = nc.size
        val had = cur.isNotEmpty()
        old = cur
        cur = nc
        changed = BooleanArray(n)
        var any = false
        for (k in 0 until n) {
            val oi = old.size - 1 - k
            val ch = oi < 0 || old[oi] != cur[n - 1 - k]
            changed[k] = ch
            if (ch) any = true
        }
        roll = if (had && any) 0f else 1f
        if (had && any && flash != 0 && highlight) {
            flashColor = if (flash > 0) Pal.UP else Pal.DOWN
            flashP = 0f
        }
        requestLayout()
        invalidate()
        if (roll < 1f || flashP < 1f) Ticker.add(this)
    }

    override fun onDetachedFromWindow() {
        Ticker.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(wm: Int, hm: Int) {
        val tw = if (cur.isEmpty()) 0f else p.measureText(cur, 0, cur.size)
        val h = (p.descent() - p.ascent() + 2f * dy).toInt()
        setMeasuredDimension(resolveSize(Math.ceil(tw.toDouble()).toInt() + 4, wm), resolveSize(h, hm))
    }

    override fun onTick(dt: Float): Boolean {
        if (roll < 1f) roll = min(1f, roll + dt / 0.28f)
        if (flashP < 1f) flashP = min(1f, flashP + dt / 3.0f)
        acc += dt
        if (roll < 1f || acc >= 0.05f) {
            acc = 0f
            invalidate()
        }
        return roll < 1f || flashP < 1f
    }

    override fun onDraw(c: Canvas) {
        val n = cur.size
        if (n == 0) return
        p.getTextWidths(cur, 0, n, wid)
        val base = dy - p.ascent()
        val col = mixC(flashColor, Pal.TEXT, flashP * flashP)
        val e = 1f - (1f - roll) * (1f - roll)
        var x = 0f
        if (alignEnd) {
            var tw = 0f
            for (i in 0 until n) tw += wid[i]
            x = Math.max(0f, width - tw)
        }
        for (i in 0 until n) {
            val k = n - 1 - i
            if (roll < 1f && changed[k]) {
                val oi = old.size - 1 - k
                if (oi >= 0) {
                    p.color = col
                    p.alpha = ((1f - e) * 255f).toInt()
                    c.drawText(old, oi, 1, x, base - e * dy, p)
                }
                p.color = col
                p.alpha = (e * 255f).toInt()
                c.drawText(cur, i, 1, x, base + (1f - e) * dy, p)
            } else {
                p.color = col
                p.alpha = 255
                c.drawText(cur, i, 1, x, base, p)
            }
            x += wid[i]
        }
    }
}

/**
 * Wykres: gładka krzywa, gradient pod linią, przerywana linia poprzedniego zamknięcia, pulsująca kropka na końcu,
 * minimalne etykiety godzin. Otwarcie: linia "rysuje się" od lewej (600 ms); nowy punkt wjeżdża animacją (350 ms).
 * Ścieżki budowane poza onDraw (przy zmianie danych/rozmiaru/skali), animacja ~30 fps przez [Ticker].
 */
class ChartView(context: Context, private val u: Float) : View(context), Tick {

    private var n = 0
    private var xs = FloatArray(0)
    private var vs = FloatArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var prev = 0f
    private var prevY = 0f
    private var color = Pal.FLAT
    private var yMin = 0f
    private var yMax = 1f
    private var tMin = 0f
    private var tMax = 1f
    private var haveRange = false
    private var revealStart = 0f
    private var revealP = 1f
    private var revealMs = 600f
    private var initialReveal = false
    private var pulse = 0f
    private var dtAcc = 0f
    private var lastCtx = ""
    private var lastNx = 0f
    private var tipX = 0f
    private var tipY = 0f
    private var attached = false
    private var pl = 0f
    private var pr = 0f
    private var pt = 0f
    private var pb = 0f
    private var yDirty = false
    private var paused = false
    private var isD1 = true
    private var tms = LongArray(0)

    // wolumen: słupki pod wykresem ceny na tej samej osi czasu
    private var volOn = false
    private var vols = FloatArray(0)
    private var vh = FloatArray(0)
    private var vup = BooleanArray(0)
    private var slotFrac = 0.01f
    private var vTop = 0f
    private var vBot = 0f
    private var curVol = ""
    private val pVolUp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVolDn = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Kursor zniknął sam (4 s bezczynności). */
    var onCursorGone: (() -> Unit)? = null

    // kursor (← →), siatka, znaczniki maks/min: teksty powstają przy zmianie danych/klawiszu, nigdy w onDraw
    private var cursor = -1
    private var cursorIdle = 0f
    private var curTime = ""
    private var curPrice = ""
    private var curChg = ""
    private var curCol = Pal.FLAT
    private var gridN = 0
    private val gridV = FloatArray(7)
    private val gridY = FloatArray(7)
    private val gridT = arrayOfNulls<String>(7)
    private var maxI = 0
    private var minI = 0
    private var maxT = ""
    private var minT = ""
    private var prevT = ""
    private val tip = RectF()
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTipEdge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTipT = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTipP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCur = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale("pl", "PL"))
    private val dayTimeFmt = SimpleDateFormat("EEE HH:mm", Locale("pl", "PL"))

    private val line = Path()
    private val fill = Path()
    private val dash = Path()
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private var labN = 0
    private val labX = FloatArray(8)
    private val labT = arrayOfNulls<String>(8)
    private val pl2 = Locale("pl", "PL")
    private val dayFmt = SimpleDateFormat("EEE", pl2)

    init {
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = Math.max(2f, u * 0.17f)
        pLine.strokeCap = Paint.Cap.ROUND
        pLine.strokeJoin = Paint.Join.ROUND
        pDash.style = Paint.Style.STROKE
        pDash.strokeWidth = Math.max(1.5f, u * 0.07f)
        pDash.color = hex(0x66EFE8DAL)
        pDash.pathEffect = DashPathEffect(floatArrayOf(u * 0.55f, u * 0.55f), 0f)
        pText.textSize = u * 1.0f
        pText.color = Pal.TEXT_DIM
        pText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        pText.textAlign = Paint.Align.CENTER
        dayFmt.timeZone = GpwClock.zone
        timeFmt.timeZone = GpwClock.zone
        dayTimeFmt.timeZone = GpwClock.zone
        pText.fontFeatureSettings = "tnum"
        pGrid.style = Paint.Style.STROKE
        pGrid.strokeWidth = 1f
        pGrid.color = hex(0x14EFE8DAL)
        pAxis.textSize = u * 0.9f
        pAxis.color = hex(0x70EFE8DAL)
        pAxis.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        pAxis.fontFeatureSettings = "tnum"
        pMark.textSize = u * 1.0f
        pMark.color = Pal.TEXT_DIM
        pMark.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        pMark.fontFeatureSettings = "tnum"
        pMark.textAlign = Paint.Align.CENTER
        pTipBg.color = hex(0xE6141A22L)
        pTipBg.setShadowLayer(u * 0.9f, 0f, u * 0.3f, hex(0x66000000L))
        pTipEdge.style = Paint.Style.STROKE
        pTipEdge.strokeWidth = 1f
        pTipEdge.color = hex(0x38FFFFFFL)
        pTipT.textSize = u * 0.95f
        pTipT.color = Pal.TEXT_DIM
        pTipT.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        pTipT.fontFeatureSettings = "tnum"
        pTipP.textSize = u * 1.55f
        pTipP.color = Pal.TEXT
        pTipP.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        pTipP.fontFeatureSettings = "tnum"
        pCur.style = Paint.Style.STROKE
        pCur.strokeWidth = Math.max(1f, u * 0.05f)
        pVolUp.color = (0x6B shl 24) or (Pal.UP and 0x00FFFFFF)
        pVolDn.color = (0x6B shl 24) or (Pal.DOWN and 0x00FFFFFF)
    }

    fun clear() {
        n = 0
        volOn = false
        lastCtx = ""
        cursor = -1
        invalidate()
    }

    fun pauseAnim() {
        paused = true
        Ticker.remove(this)
    }

    fun resumeAnim() {
        paused = false
        if (attached) Ticker.add(this)
    }

    /** Włącza kursor na ostatnim punkcie. @return false, gdy brak danych */
    fun showCursor(): Boolean {
        if (n < 2) return false
        cursor = n - 1
        cursorIdle = 0f
        cursorStrings()
        invalidate()
        return true
    }

    fun hideCursor() {
        if (cursor >= 0) {
            cursor = -1
            invalidate()
        }
    }

    /** Kursor: ← → przesuwa po punktach (krok ~1% serii), po 4 s bezczynności znika. */
    fun moveCursor(d: Int) {
        if (n < 2) return
        val step = Math.max(1, n / 90)
        cursor = if (cursor < 0) n - 1 else cursor
        cursor = (cursor + d * step).coerceIn(0, n - 1)
        cursorIdle = 0f
        cursorStrings()
        invalidate()
    }

    private fun f2(v: Float): String = String.format(pl2, "%.2f", v)

    private fun cursorStrings() {
        if (cursor < 0 || cursor >= n) return
        val v = vs[cursor]
        curTime = (if (isD1) timeFmt else dayTimeFmt).format(Date(tms[cursor])).replace(".", "")
        curPrice = f2(v)
        val d = v - prev
        val pct = if (prev != 0f) d / prev * 100f else 0f
        val sg = if (d > 0f) "+" else if (d < 0f) "−" else ""
        curChg = sg + f2(abs(d)) + "   " + sg + String.format(pl2, "%.2f%%", abs(pct))
        curCol = if (d > 0f) Pal.UP else if (d < 0f) Pal.DOWN else Pal.FLAT
        curVol = if (volOn && cursor < vols.size) "wol. " + fmtVolume(vols[cursor].toDouble()) else ""
    }

    private fun gridStrings() {
        gridN = 0
        val rng = tMax - tMin
        if (rng <= 0f) return
        val raw = rng / 3f
        val mag = 10f.pow(floor(log10(raw)))
        val nr = raw / mag
        val step = (if (nr < 1.5f) 1f else if (nr < 3.5f) 2f else if (nr < 7.5f) 5f else 10f) * mag
        val dec = if (step >= 1f) 0 else if (step >= 0.1f) 1 else 2
        var v = Math.ceil((tMin / step).toDouble()).toFloat() * step
        while (v <= tMax && gridN < 7) {
            gridV[gridN] = v
            gridT[gridN] = String.format(pl2, "%." + dec + "f", v)
            gridN++
            v += step
        }
    }

    /** @param mode 0 = bez animacji, 1 = rysowanie od lewej (otwarcie/zmiana), 2 = wjazd nowego punktu */
    fun setData(cd: ChartData, col: Int, mode: Int) {
        val m = cd.t.size
        val ctx = cd.key + "/" + cd.range
        val sameCtx = ctx == lastCtx && n > 0
        val ox = lastNx
        if (xs.size < m) {
            xs = FloatArray(m); vs = FloatArray(m); px = FloatArray(m); py = FloatArray(m)
        }
        n = m
        isD1 = cd.range == ChartRange.D1
        tms = cd.t
        val span = (cd.axisEnd - cd.axisStart).toFloat()
        for (i in 0 until m) {
            xs[i] = if (cd.range == ChartRange.D1 && span > 0f) ((cd.t[i] - cd.axisStart) / span).coerceIn(0f, 1f)
            else i / (m - 1f)
            vs[i] = cd.v[i]
        }
        prev = cd.prev.toFloat()
        color = col
        volOn = cd.hasVol
        if (volOn) {
            if (vols.size < m) { vols = FloatArray(m); vh = FloatArray(m); vup = BooleanArray(m) }
            var sum = 0f
            var cnt = 0
            var mxv = 0f
            for (i in 0 until m) {
                val v = cd.vol[i]
                vols[i] = v
                if (v > 0f) { sum += v; cnt++; if (v > mxv) mxv = v }
            }
            // wartości odstające (aukcja otwarcia) przycinamy do 4x średniej, żeby reszta słupków była czytelna
            val mean = if (cnt > 0) sum / cnt else 1f
            val cap = Math.max(1f, Math.min(mxv, mean * 4f))
            for (i in 0 until m) {
                vh[i] = Math.min(1f, vols[i] / cap)
                val pv = if (i == 0) prev else cd.v[i - 1]
                vup[i] = cd.v[i] >= pv
            }
            slotFrac = if (isD1 && span > 0f) 300_000f / span else 1f / Math.max(1, m - 1)
        }
        var mn = prev
        var mx = prev
        for (i in 0 until m) {
            if (vs[i] < mn) mn = vs[i]
            if (vs[i] > mx) mx = vs[i]
        }
        var pad = (mx - mn) * 0.12f
        if (pad <= 0f) pad = abs(mx) * 0.001f + 0.01f
        tMin = mn - pad
        tMax = mx + pad
        if (!haveRange || mode != 2 || !sameCtx) {
            yMin = tMin
            yMax = tMax
            haveRange = true
        }
        gridStrings()
        maxI = 0
        minI = 0
        for (i in 1 until m) {
            if (vs[i] > vs[maxI]) maxI = i
            if (vs[i] < vs[minI]) minI = i
        }
        maxT = f2(vs[maxI])
        minT = f2(vs[minI])
        prevT = "zamk. " + f2(prev)
        if (!sameCtx) cursor = -1
        if (cursor >= m) cursor = m - 1
        cursorStrings()
        // etykiety osi
        labN = 0
        if (cd.range == ChartRange.D1) {
            val hrs = intArrayOf(9, 13, 17)
            val s0 = 540f
            for (h in hrs) {
                labT[labN] = "$h:00"
                labX[labN] = ((h * 60 - s0) / 515f)
                labN++
            }
        } else {
            var lastDay = -1L
            for (i in 0 until m) {
                val day = (cd.t[i] + GpwClock.zone.getOffset(cd.t[i])) / 86_400_000L
                if (day != lastDay && labN < 6) {
                    lastDay = day
                    labT[labN] = dayFmt.format(Date(cd.t[i])).trimEnd('.')
                    labX[labN] = xs[i]
                    labN++
                }
            }
        }
        val newNx = xs[m - 1]
        when (mode) {
            1 -> { revealStart = 0f; revealP = 0f; revealMs = 600f; initialReveal = true }
            2 -> if (sameCtx) { revealStart = ox; revealP = 0f; revealMs = 350f; initialReveal = false } else { revealP = 1f }
            else -> revealP = 1f
        }
        lastNx = newNx
        lastCtx = ctx
        layoutPlot()
        rebuildShader()
        buildPaths()
        invalidate()
        if (attached && !paused) Ticker.add(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (!paused) Ticker.add(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        Ticker.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutPlot()
        rebuildShader()
        buildPaths()
    }

    /** Obszar ceny (góra) i opcjonalnie wolumenu (dół, ok. 22% wysokości) na wspólnej osi czasu. */
    private fun layoutPlot() {
        pl = u * 0.3f
        pr = width - u * 0.3f
        pt = u * 1.0f
        val bottom = height - u * 2.8f
        if (volOn) {
            val avail = bottom - pt
            pb = bottom - avail * 0.22f - u * 0.9f
            vTop = pb + u * 0.9f
            vBot = bottom
        } else {
            pb = bottom
            vTop = bottom
            vBot = bottom
        }
    }

    private fun rebuildShader() {
        if (pb <= pt) return
        val rgb = color and 0x00FFFFFF
        pFill.shader = LinearGradient(0f, pt, 0f, pb, (0x44 shl 24) or rgb, rgb, Shader.TileMode.CLAMP)
        pLine.color = color
        pDot.color = color
    }

    private fun buildPaths() {
        line.rewind()
        fill.rewind()
        dash.rewind()
        if (n < 2 || pr <= pl || pb <= pt) return
        val h = pb - pt
        var rng = yMax - yMin
        if (rng <= 0f) rng = 1f
        val w = pr - pl
        for (i in 0 until n) {
            px[i] = pl + xs[i] * w
            py[i] = pb - (vs[i] - yMin) / rng * h
        }
        line.moveTo(px[0], py[0])
        for (i in 0 until n - 1) {
            val p0 = if (i > 0) i - 1 else 0
            val p3 = if (i + 2 < n) i + 2 else n - 1
            val lo = min(py[i], py[i + 1])
            val hi = max(py[i], py[i + 1])
            val c1y = (py[i] + (py[i + 1] - py[p0]) / 6f).coerceIn(lo, hi)
            val c2y = (py[i + 1] - (py[p3] - py[i]) / 6f).coerceIn(lo, hi)
            line.cubicTo(
                px[i] + (px[i + 1] - px[p0]) / 6f, c1y,
                px[i + 1] - (px[p3] - px[i]) / 6f, c2y,
                px[i + 1], py[i + 1]
            )
        }
        for (k in 0 until gridN) gridY[k] = pb - (gridV[k] - yMin) / rng * h
        fill.addPath(line)
        fill.lineTo(px[n - 1], pb)
        fill.lineTo(px[0], pb)
        fill.close()
        prevY = pb - (prev - yMin) / rng * h
        dash.moveTo(pl, prevY)
        dash.lineTo(pr, prevY)
    }

    private fun revealNx(): Float {
        val e = 1f - (1f - revealP) * (1f - revealP) * (1f - revealP)
        return revealStart + (lastNx - revealStart) * e
    }

    private fun updateTip() {
        if (n < 2) return
        val rx = pl + revealNx() * (pr - pl)
        var i = 0
        while (i < n - 1 && px[i + 1] < rx) i++
        if (i >= n - 1) {
            tipX = px[n - 1]
            tipY = py[n - 1]
        } else {
            val a = px[i]
            val b = px[i + 1]
            val f = if (b > a) ((rx - a) / (b - a)).coerceIn(0f, 1f) else 0f
            tipX = a + (b - a) * f
            tipY = py[i] + (py[i + 1] - py[i]) * f
        }
    }

    override fun onTick(dt: Float): Boolean {
        if (!attached || paused) return false
        if (cursor >= 0) {
            cursorIdle += dt
            if (cursorIdle > 4f) {
                cursor = -1
                invalidate()
                onCursorGone?.invoke()
            }
        }
        pulse += dt
        dtAcc += dt
        if (dtAcc < 0.030f) return true
        val d = dtAcc
        dtAcc = 0f
        var animating = false
        if (revealP < 1f) {
            revealP = min(1f, revealP + d * 1000f / revealMs)
            animating = true
        }
        val gap = abs(tMin - yMin) + abs(tMax - yMax)
        if (gap > 1e-6f) {
            val k = 1f - exp(-9f * d)
            yMin += (tMin - yMin) * k
            yMax += (tMax - yMax) * k
            if (gap < (tMax - tMin) * 0.002f) {
                yMin = tMin
                yMax = tMax
            }
            yDirty = true
            animating = true
        }
        if (yDirty) {
            buildPaths()
            yDirty = false
        }
        if (animating || cursor >= 0) {
            invalidate()
        } else if (n >= 2) {
            updateTip()
            val r = u * 1.6f
            invalidate((tipX - r).toInt(), (tipY - r).toInt(), (tipX + r).toInt() + 1, (tipY + r).toInt() + 1)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (n < 2 || pb <= pt) return
        updateTip()

        // siatka (subtelna) + etykiety osi Y
        for (k in 0 until gridN) {
            val y = gridY[k]
            if (y < pt - 1f || y > pb + 1f) continue
            c.drawLine(pl, y, pr, y, pGrid)
            val s = gridT[k] ?: continue
            c.drawText(s, pr - pAxis.measureText(s) - u * 0.2f, y - u * 0.3f, pAxis)
        }
        for (i in 0 until labN) {
            val x = pl + labX[i] * (pr - pl)
            c.drawLine(x, pt, x, if (volOn) vBot else pb, pGrid)
        }

        // przerywana linia poprzedniego zamknięcia + mała etykieta
        c.drawPath(dash, pDash)
        c.drawText(prevT, pl + u * 0.2f, prevY - u * 0.3f, pAxis)

        val rx = pl + revealNx() * (pr - pl)
        val clipR = rx + 1f
        c.save()
        c.clipRect(0f, 0f, if (revealP >= 1f) width.toFloat() else clipR, height.toFloat())
        c.drawPath(fill, pFill)
        c.drawPath(line, pLine)
        c.restore()
        if (volOn) drawVolume(c, rx)

        // znaczniki maks/min dnia (pojawiają się, gdy linia je minie)
        if (maxI != minI) {
            if (px[maxI] <= rx) markPoint(c, maxI, maxT, true)
            if (px[minI] <= rx) markPoint(c, minI, minT, false)
        }

        // kropka z pulsowaniem
        val r = u * 0.36f
        val ph = (pulse % 1.8f) / 1.8f
        var da = 1f
        if (initialReveal && revealP < 1f) da = revealP * revealP * revealP
        pHalo.color = (((1f - ph) * 0x55 * da).toInt() shl 24) or (color and 0x00FFFFFF)
        c.drawCircle(tipX, tipY, r * (1.3f + 1.9f * ph), pHalo)
        pDot.alpha = (255f * da).toInt()
        c.drawCircle(tipX, tipY, r, pDot)

        // etykiety godzin
        val ty = height - u * 0.7f
        for (i in 0 until labN) {
            val s = labT[i] ?: continue
            val w2 = pText.measureText(s) / 2f
            val x = (pl + labX[i] * (pr - pl)).coerceIn(w2, width - w2)
            c.drawText(s, x, ty, pText)
        }

        if (cursor in 0 until n) drawCursor(c)
    }

    /** Słupki wolumenu: wjeżdżają razem z linią ceny (rx = bieżący zasięg rysowania). Stonowane, niska alfa. */
    private fun drawVolume(c: Canvas, rx: Float) {
        val bw = Math.max(1.5f, slotFrac * (pr - pl) * 0.64f)
        val hh = vBot - vTop
        val ramp = u * 2.5f
        val rad = bw * 0.22f
        c.drawLine(pl, vBot, pr, vBot, pGrid)
        for (i in 0 until n) {
            val x = px[i]
            if (x > rx) break
            var f = if (revealP >= 1f) 1f else (rx - x) / ramp
            if (f > 1f) f = 1f
            val h = vh[i] * hh * f
            if (h < 0.5f) continue
            c.drawRoundRect(x - bw / 2f, vBot - h, x + bw / 2f, vBot, rad, rad, if (vup[i]) pVolUp else pVolDn)
        }
    }

    private fun markPoint(c: Canvas, i: Int, s: String, above: Boolean) {
        pCur.color = hex(0xB3EFE8DAL)
        c.drawCircle(px[i], py[i], u * 0.24f, pCur)
        val w2 = pMark.measureText(s) / 2f
        val x = px[i].coerceIn(w2 + 2f, width - w2 - 2f)
        val y = if (above) py[i] - u * 0.7f else py[i] + u * 1.55f
        c.drawText(s, x, y, pMark)
    }

    private fun drawCursor(c: Canvas) {
        val x = px[cursor]
        val y = py[cursor]
        pCur.color = hex(0x55EFE8DAL)
        c.drawLine(x, pt, x, if (volOn) vBot else pb, pCur)
        pCur.color = Pal.TEXT
        pDot.alpha = 255
        c.drawCircle(x, y, u * 0.34f, pDot)
        c.drawCircle(x, y, u * 0.34f, pCur)
        // dymek: godzina, cena, zmiana
        val pad = u * 0.9f
        val wT = pTipT.measureText(curTime)
        val wP = pTipP.measureText(curPrice)
        val lh = u * 1.25f
        val bw = Math.max(wP, Math.max(wT, Math.max(pTipT.measureText(curChg), if (volOn) pTipT.measureText(curVol) else 0f))) + pad * 2f
        val showVol = volOn && curVol.isNotEmpty()
        val bh = pad * 2f + lh * 0.9f + u * 1.55f + lh + (if (showVol) lh else 0f)
        var left = x - bw / 2f
        left = left.coerceIn(0f, Math.max(0f, width - bw))
        var top = y - bh - u * 1.1f
        if (top < 0f) top = y + u * 1.1f
        tip.set(left, top, left + bw, top + bh)
        c.drawRoundRect(tip, u * 0.8f, u * 0.8f, pTipBg)
        c.drawRoundRect(tip, u * 0.8f, u * 0.8f, pTipEdge)
        var ty = top + pad + lh * 0.7f
        c.drawText(curTime, left + pad, ty, pTipT)
        ty += u * 1.55f + u * 0.15f
        c.drawText(curPrice, left + pad, ty, pTipP)
        ty += lh
        pTipT.color = curCol
        c.drawText(curChg, left + pad, ty, pTipT)
        pTipT.color = Pal.TEXT_DIM
        if (showVol) {
            ty += lh
            c.drawText(curVol, left + pad, ty, pTipT)
        }
    }
}

/**
 * Widok szczegółowy GPW: ← → przełącza WIG20 / mWIG40 / sWIG80, OK przełącza 1D / 5D.
 * Odświeżanie co 15 s tylko gdy panel jest otwarty ([start]) i trwa sesja; poza sesją jedno pobranie.
 * Błąd/429: wykładniczy backoff, ostatnie dane z napisem "nieaktualne". Sieć na wspólnym executorze.
 */
class GpwDetailView(
    ctx: Context,
    private val u: Float,
    private val worker: ExecutorService,
    private val ui: Handler,
    private val source: QuoteSource
) : FrostPanel(ctx, u * 2.0f, hex(0xB3141A22L), hex(0x38FFFFFFL), 1f) {

    private val pl = Locale("pl", "PL")
    private val nf = NumberFormat.getNumberInstance(pl)
    private val hm = SimpleDateFormat("HH:mm", pl)
    private val keys = GPW_CARDS.map { it.first }
    private val tabs = ArrayList<TextView>()
    private val tabGlow = GlowLayer(ctx)
    private val tabRow = LinearLayout(ctx)
    private var tabHost: FrameLayout? = null
    private var cursorMode = false
    private val rangeTabs = ArrayList<TextView>()
    private val status = TextView(ctx)
    private val value = RollingValueView(ctx, u * 8.0f)
    private val change = TextView(ctx)
    private val chart = ChartView(ctx, u)
    private val stOpen = TextView(ctx)
    private val stHigh = TextView(ctx)
    private val stLow = TextView(ctx)
    private val stPrev = TextView(ctx)
    private val stVol = TextView(ctx)
    private val stTurn = TextView(ctx)
    private var volBox: View? = null
    private var turnBox: View? = null

    private var idx = 0
    private var range = ChartRange.D1
    private val cache = HashMap<String, ChartData>()
    private var token = 0
    private var inflight = 0
    private var fails = 0
    private var running = false
    private var stale = false
    private var shownCtx = ""
    private var shownPrice = Double.NaN
    private var shownCd: ChartData? = null
    private val poll = Runnable { request(false) }

    init {
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        hm.timeZone = GpwClock.zone
        isFocusable = true
        defaultFocusHighlightEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((u * 3.4f).toInt(), (u * 2.6f).toInt(), (u * 3.4f).toInt(), (u * 2.0f).toInt())
        col.clipChildren = false
        addView(col, FrameLayout.LayoutParams(-1, -1))
        chart.onCursorGone = { cursorMode = false }

        // nagłówek: indeksy | zakres | status
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.clipChildren = false
        // karty (WIG20, mWIG40, sWIG80, TEN, GPP): ten sam język zaznaczenia, wspólna poświata przesuwa się między kartami
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.clipChildren = false
        for ((_, name) in GPW_CARDS) {
            val t = label(name, 2.4f, Pal.TEXT_DIM, "sans-serif-light")
            t.alpha = Sel.DIM
            t.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = (u * 3.4f).toInt()
            tabRow.addView(t, lp)
            tabs.add(t)
        }
        val tabHost = FrameLayout(ctx)
        tabHost.clipChildren = false
        tabHost.addView(tabGlow, FrameLayout.LayoutParams(-1, -1))
        tabHost.addView(tabRow, FrameLayout.LayoutParams(-2, -2))
        head.addView(tabHost, LinearLayout.LayoutParams(-2, -2))
        this.tabHost = tabHost
        head.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        for (name in arrayOf("1D", "5D")) {
            val t = label(name, 1.7f, Pal.TEXT_DIM, "sans-serif-light")
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.leftMargin = (u * 1.4f).toInt()
            head.addView(t, lp)
            rangeTabs.add(t)
        }
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        // wartość + zmiana + status
        val vrow = LinearLayout(ctx)
        vrow.orientation = LinearLayout.HORIZONTAL
        vrow.gravity = Gravity.BOTTOM
        vrow.addView(value, LinearLayout.LayoutParams(-2, -2))
        change.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.2f)
        change.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        change.fontFeatureSettings = "tnum"
        change.setTextColor(Pal.FLAT)
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = (u * 2.4f).toInt()
        clp.bottomMargin = (u * 1.4f).toInt()
        vrow.addView(change, clp)
        vrow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.1f)
        status.setTextColor(Pal.TEXT_DIM)
        status.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val slp = LinearLayout.LayoutParams(-2, -2)
        slp.bottomMargin = (u * 1.6f).toInt()
        vrow.addView(status, slp)
        val vlp = LinearLayout.LayoutParams(-1, -2)
        vlp.topMargin = (u * 1.2f).toInt()
        col.addView(vrow, vlp)

        // wykres
        val chp = LinearLayout.LayoutParams(-1, 0, 1f)
        chp.topMargin = (u * 0.8f).toInt()
        col.addView(chart, chp)

        // statystyki
        val stats = LinearLayout(ctx)
        stats.orientation = LinearLayout.HORIZONTAL
        addStat(stats, "otwarcie", stOpen)
        addStat(stats, "max", stHigh)
        addStat(stats, "min", stLow)
        addStat(stats, "poprzednie zamknięcie", stPrev)
        volBox = addStat(stats, "wolumen dziś", stVol)
        turnBox = addStat(stats, "obrót", stTurn)
        val stp = LinearLayout.LayoutParams(-1, -2)
        stp.topMargin = (u * 1.2f).toInt()
        col.addView(stats, stp)

        val hint = label("←  →  karta        ↓  kursor (←  →, ↑ wyłącza)        OK  1D / 5D        Wstecz  zamknij", 1.0f, Pal.TEXT_DIM, "sans-serif-light")
        hint.gravity = Gravity.END
        val hlp = LinearLayout.LayoutParams(-1, -2)
        hlp.topMargin = (u * 1.4f).toInt()
        col.addView(hint, hlp)

        // głębia szkła: miękki cień pod panelem (elevation) + subtelny połysk górnej krawędzi
        elevation = u * 1.4f
        val tag = brandTag(ctx, u)
        val tlp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        tlp.leftMargin = (u * 3.4f).toInt()
        tlp.bottomMargin = (u * 2.0f).toInt()
        addView(tag, tlp)
        updateTabs()
    }

    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        sheenRect.set(0f, 0f, w.toFloat(), h * 0.45f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width > 0) canvas.drawRoundRect(sheenRect, u * 2.0f, u * 2.0f, sheen)
    }

    private fun label(text: String, size: Float, color: Int, face: String): TextView {
        val t = TextView(context)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        t.setTextColor(color)
        t.typeface = Typeface.create(face, Typeface.NORMAL)
        t.includeFontPadding = false
        return t
    }

    private fun addStat(parent: LinearLayout, name: String, v: TextView): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.addView(label(name, 1.0f, Pal.TEXT_DIM, "sans-serif-light"))
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.9f)
        v.setTextColor(Pal.TEXT)
        v.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        v.includeFontPadding = false
        v.fontFeatureSettings = "tnum"
        v.text = "–"
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.topMargin = (u * 0.5f).toInt()
        box.addView(v, lp)
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        return box
    }

    // ------------------------------------------------------------ klawisze

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (cursorMode) chart.moveCursor(-1) else moveIndex(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (cursorMode) chart.moveCursor(1) else moveIndex(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!cursorMode) cursorMode = chart.showCursor(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { exitCursor(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) toggleRange()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun exitCursor() {
        cursorMode = false
        chart.hideCursor()
    }

    /** Wstecz najpierw wyłącza kursor; @return true, gdy zużyte. */
    fun handleBack(): Boolean {
        if (!cursorMode) return false
        exitCursor()
        return true
    }

    private fun moveIndex(d: Int) {
        idx = (idx + d + keys.size) % keys.size
        exitCursor()
        switchTo()
    }

    private fun toggleRange() {
        range = if (range == ChartRange.D1) ChartRange.D5 else ChartRange.D1
        exitCursor()
        switchTo()
    }

    private fun updateTabs() {
        for (i in tabs.indices) {
            val on = i == idx
            val t = tabs[i]
            t.typeface = Typeface.create(if (on) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
            val z = if (on) Sel.SCALE else 1f
            t.animate().cancel()
            t.animate().scaleX(z).scaleY(z).alpha(if (on) 1f else Sel.DIM)
                .setDuration(Sel.MS).setInterpolator(Motion.EASE).start()
        }
        rangeTabs[0].setTextColor(if (range == ChartRange.D1) Pal.TEXT else Pal.TEXT_DIM)
        rangeTabs[1].setTextColor(if (range == ChartRange.D5) Pal.TEXT else Pal.TEXT_DIM)
        val host = tabHost
        if (host != null && idx < tabs.size) tabGlow.focusOn(host, tabs[idx])
    }

    // ------------------------------------------------------------ dane

    fun start() {
        running = true
        chart.resumeAnim()
        switchTo()
    }

    fun stop() {
        running = false
        token++
        ui.removeCallbacks(poll)
        chart.pauseAnim()
        tabGlow.reset()
    }

    private fun switchTo() {
        token++
        fails = 0
        stale = false
        updateTabs()
        val c = cache[keys[idx] + "/" + range]
        if (c != null) {
            display(c, true)
        } else {
            shownCtx = ""
            shownPrice = Double.NaN
            shownCd = null
            value.set("–", 0)
            change.text = ""
            chart.clear()
            stOpen.text = "–"; stHigh.text = "–"; stLow.text = "–"; stPrev.text = "–"
            stVol.text = "–"; stTurn.text = "–"
            showVolStats(keys[idx] in GPW_TICKERS)
            refreshStatus()
        }
        request(true)
    }

    private fun request(forced: Boolean) {
        if (!running) return
        if (inflight > 0 && !forced) return
        ui.removeCallbacks(poll)
        inflight++
        val my = token
        val k = keys[idx]
        val r = range
        worker.execute {
            var res: ChartData? = null
            var bad = false
            try {
                res = source.fetchChart(k, r)
            } catch (e: Exception) {
                bad = true
            }
            val out = res
            val fail = bad || out == null
            ui.post { onResult(my, k, r, out, fail) }
        }
    }

    private fun onResult(my: Int, k: String, r: ChartRange, cd: ChartData?, fail: Boolean) {
        inflight--
        if (my != token || !running) return
        if (!fail && cd != null) {
            fails = 0
            stale = false
            cache["$k/$r"] = cd
            display(cd, false)
        } else {
            fails++
            stale = true
            refreshStatus()
        }
        val delay = when {
            fail -> Math.min(15_000L shl Math.min(fails, 5), 300_000L)
            GpwClock.inSession() -> 15_000L
            else -> -1L        // rynek zamknięty: bez pollingu
        }
        ui.removeCallbacks(poll)
        if (delay > 0L) ui.postDelayed(poll, delay)
    }

    // ------------------------------------------------------------ prezentacja

    private fun signed(v: Double): String =
        (if (v > 0.0) "+" else if (v < 0.0) "−" else "") + nf.format(abs(v))

    private fun display(cd: ChartData, full: Boolean) {
        val ctxKey = cd.key + "/" + cd.range
        val same = !full && ctxKey == shownCtx
        val delta = cd.price - cd.prev
        val pct = if (cd.prev != 0.0) delta / cd.prev * 100.0 else 0.0
        val up = delta > 0.0
        val down = delta < 0.0
        val col = if (up) Pal.UP else if (down) Pal.DOWN else Pal.FLAT

        var flash = 0
        if (same && !shownPrice.isNaN() && abs(cd.price - shownPrice) > 1e-9) flash = if (cd.price > shownPrice) 1 else -1
        value.set(nf.format(cd.price), flash)

        val sb = SpannableStringBuilder(if (up) "▲" else if (down) "▼" else "–")
        sb.setSpan(RelativeSizeSpan(0.6f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(signed(delta)).append("   ").append(
            (if (pct > 0.0) "+" else if (pct < 0.0) "−" else "") + String.format(pl, "%.2f%%", abs(pct))
        )
        change.text = sb
        change.setTextColor(col)

        chart.setData(cd, col, if (same) 2 else 1)
        stOpen.text = nf.format(cd.open)
        stHigh.text = nf.format(cd.high)
        stLow.text = nf.format(cd.low)
        stPrev.text = nf.format(cd.prev)
        showVolStats(cd.hasVol)
        if (cd.hasVol) {
            stVol.text = fmtVolume(cd.volToday)
            stTurn.text = fmtTurnover(cd.turnover)
        }

        shownCtx = ctxKey
        shownPrice = cd.price
        shownCd = cd
        refreshStatus()
    }

    /** Wolumen pusty lub same zera = ukryj "wolumen dziś" i "obrót" (słupki chowa sam wykres). */
    private fun showVolStats(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        volBox?.visibility = v
        turnBox?.visibility = v
    }

    private fun refreshStatus() {
        val tm = shownCd?.marketTime ?: 0L
        val t = if (tm > 0L) hm.format(Date(tm)) else ""
        status.text = when {
            stale -> "nieaktualne" + (if (t.isNotEmpty()) " · $t" else "")
            GpwClock.inSession() -> "opóźnione" + (if (t.isNotEmpty()) " · $t" else "")
            else -> "rynek zamknięty, ostatnia sesja"
        }
    }
}
