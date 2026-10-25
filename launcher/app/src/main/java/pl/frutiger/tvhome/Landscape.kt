package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Spokojna natura: mgliste góry nad jeziorem, 4 warstwy z lekką paralaksą, miękkie odbicie,
 * winieta + przyciemnienie ok. 25%. Kolory płynnie zależą od pory dnia (świt, dzień, zmierzch, noc z gwiazdami).
 * Góry są wstępnie rysowane do masek ALPHA_8 (połowa rozdzielczości) - w onDraw tylko kilka tekstur.
 * Gdy klatki spadną poniżej 45 fps na 3 s: [simple] = true (bez paralaksy i odbić, brak pętli animacji).
 */
class NatureView(context: Context) : View(context) {

    var running = false
        private set
    var simple = false
        private set
    var parallaxTarget = 0f
    var hourOverride = -1f

    private var W = 1920f
    private var H = 1080f
    private var y0 = 756f
    private var t = 0f
    private var par = 0f
    private var lastMs = 0L
    private var emaDt = 0.0167f
    private var slowSince = 0L

    private val layers = 4
    private val hMaxK = floatArrayOf(0.30f, 0.22f, 0.15f, 0.085f)
    private val freqK = floatArrayOf(2.0f, 2.9f, 4.2f, 6.0f)
    private val parK = floatArrayOf(0.006f, 0.012f, 0.022f, 0.038f)
    private val fogK = floatArrayOf(0.55f, 0.45f, 0.34f, 0.22f)
    private val fogH = floatArrayOf(0.26f, 0.22f, 0.16f, 0.10f)
    private val mixK = floatArrayOf(0.15f, 0.40f, 0.68f, 1f)
    private val MASK_S = 0.5f
    private val EXTRA = 0.06f

    private val masks = arrayOfNulls<Bitmap>(layers)
    private val topY = FloatArray(layers)
    private val nz = FloatArray(1024)

    // paleta: [skyTop, skyHor, mtnFar, mtnNear, mist, waterTop, waterBot, celestial], [stars, sun, moon]
    private val NIGHT = intArrayOf(hex(0xFF0B101CL), hex(0xFF1B2538L), hex(0xFF1C2638L), hex(0xFF0A0E16L), hex(0xFF2A3548L), hex(0xFF1A2434L), hex(0xFF0A0F18L), hex(0xFFE8E2D2L))
    private val DAWN = intArrayOf(hex(0xFF3B4A68L), hex(0xFFD6B9A0L), hex(0xFF8A8FA3L), hex(0xFF2F3A4AL), hex(0xFFD9C4B0L), hex(0xFFA8A6ACL), hex(0xFF3A465AL), hex(0xFFF0DDBDL))
    private val DAY = intArrayOf(hex(0xFF6C8CA6L), hex(0xFFE3DACBL), hex(0xFF93A8ACL), hex(0xFF3F5B54L), hex(0xFFE6E0D2L), hex(0xFFC9D2D0L), hex(0xFF4F6E78L), hex(0xFFF2E6C9L))
    private val DUSK = intArrayOf(hex(0xFF2C3654L), hex(0xFFCE9F82L), hex(0xFF76728AL), hex(0xFF262C40L), hex(0xFFC9A58FL), hex(0xFFA08C90L), hex(0xFF2A3248L), hex(0xFFF0C9A0L))
    private val F_NIGHT = floatArrayOf(1f, 0f, 1f)
    private val F_DAWN = floatArrayOf(0.25f, 1f, 0.35f)
    private val F_DAY = floatArrayOf(0f, 1f, 0f)
    private val F_DUSK = floatArrayOf(0.30f, 1f, 0.35f)

    private val cur = IntArray(8)
    private val curF = FloatArray(3)
    private val layerCol = IntArray(layers)
    private val reflCol = IntArray(layers)
    private var sunX = 0f
    private var sunY = 0f
    private var moonX = 0f
    private var moonY = 0f

    private val sky = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
    private val water = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
    private val rampPx = IntArray(64)
    private val ramp = Bitmap.createBitmap(4, 32, Bitmap.Config.ALPHA_8)
    private var vigSh: RadialGradient? = null
    private var stars = FloatArray(0)

    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pMist = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDisc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVig = Paint()
    private val rf = RectF()
    private val srcSky = Rect(0, 0, 1, 64)
    private val srcRamp = Rect(0, 0, 4, 32)
    private val cal = Calendar.getInstance()

    private val envTask = object : Runnable {
        override fun run() {
            if (!running) return
            updateEnv()
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    init {
        val r = Random(11)
        for (i in nz.indices) nz[i] = r.nextFloat()
        // rampa alfa 0 -> 255 (kwadratowa), do mgły i zanikania odbicia
        val rp = Paint()
        rp.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, 32f,
            intArrayOf(0x00FFFFFF, 0x0DFFFFFF, 0x40FFFFFF, hex(0x99FFFFFFL), -1),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(ramp).drawRect(0f, 0f, 4f, 32f, rp)
        pVig.isDither = true
        updateEnv()
    }

    // ------------------------------------------------------------ cykl życia

    fun start() {
        if (running) return
        running = true
        lastMs = SystemClock.uptimeMillis()
        slowSince = 0L
        updateEnv()
        removeCallbacks(envTask)
        postDelayed(envTask, 1000L)
        invalidate()
    }

    fun stop() {
        running = false
        removeCallbacks(envTask)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ geometria

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        W = w.toFloat()
        H = h.toFloat()
        y0 = H * 0.70f
        buildMasks()
        vigSh = RadialGradient(
            W / 2f, H * 0.48f, hypot(W, H) * 0.56f,
            intArrayOf(hex(0x40000000L), hex(0x40000000L), hex(0x99000000L)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        pVig.shader = vigSh
        val r = Random(7)
        stars = FloatArray(90 * 4)
        for (i in 0 until 90) {
            stars[i * 4] = r.nextFloat()
            stars[i * 4 + 1] = r.nextFloat() * 0.92f
            stars[i * 4 + 2] = 0.5f + r.nextFloat()
            stars[i * 4 + 3] = r.nextFloat() * 6.28f
        }
    }

    private fun noise(x: Float, seed: Int): Float {
        val xi = floor(x).toInt()
        val f = x - xi
        val a = nz[(xi + seed * 131) and 1023]
        val b = nz[(xi + 1 + seed * 131) and 1023]
        val s = (1f - cos(f * PI.toFloat())) * 0.5f
        return a + (b - a) * s
    }

    private fun buildMasks() {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = -1
        val path = Path()
        for (i in 0 until layers) {
            val top = y0 - hMaxK[i] * H * 1.06f
            topY[i] = top
            val mw = ceil(W * (1f + 2f * EXTRA) * MASK_S).toInt()
            val mh = ceil((y0 - top) * MASK_S).toInt() + 1
            masks[i]?.recycle()
            val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            path.rewind()
            path.moveTo(0f, mh.toFloat())
            var x = 0
            while (x <= mw + 4) {
                val xn = (x / MASK_S - W * EXTRA) / W
                val n = 0.55f * noise(xn * freqK[i], i) + 0.30f * noise(xn * freqK[i] * 2.3f + 9f, i) +
                        0.15f * noise(xn * freqK[i] * 5.1f + 17f, i)
                val hf = 0.18f + 0.82f * n
                val yy = (y0 - hf * hMaxK[i] * H - top) * MASK_S
                path.lineTo(x.toFloat(), yy)
                x += 4
            }
            path.lineTo(mw.toFloat() + 4f, mh.toFloat())
            path.close()
            c.drawPath(path, fill)
            masks[i] = bmp
        }
    }

    // ------------------------------------------------------------ środowisko (pora dnia)

    private fun lc(a: Int, b: Int, f: Float): Int {
        val ia = (f * 256f).toInt()
        val ra = (a shr 16) and 255
        val ga = (a shr 8) and 255
        val ba = a and 255
        val r = ra + ((((b shr 16) and 255) - ra) * ia shr 8)
        val g = ga + ((((b shr 8) and 255) - ga) * ia shr 8)
        val bl = ba + (((b and 255) - ba) * ia shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun setEnv(a: IntArray, fa: FloatArray, b: IntArray, fb: FloatArray, f: Float) {
        for (i in 0 until 8) cur[i] = lc(a[i], b[i], f)
        for (i in 0 until 3) curF[i] = fa[i] + (fb[i] - fa[i]) * f
    }

    private fun updateEnv() {
        cal.timeInMillis = System.currentTimeMillis()
        val hour = if (hourOverride >= 0f) hourOverride else
            cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f + cal.get(Calendar.SECOND) / 3600f
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val c = cos(2.0 * PI * (doy - 172) / 365.0).toFloat()
        val sr = 6.25f - 1.75f * c        // orientacyjny wschód (godz.), bez lokalizacji
        val ss = 18.25f + 2.75f * c       // orientacyjny zachód
        val t0 = sr - 1.0f
        val t1 = sr
        val t2 = sr + 1.6f
        val t3 = ss - 1.8f
        val t4 = ss
        val t5 = ss + 1.0f
        when {
            hour <= t0 || hour >= t5 -> setEnv(NIGHT, F_NIGHT, NIGHT, F_NIGHT, 0f)
            hour < t1 -> setEnv(NIGHT, F_NIGHT, DAWN, F_DAWN, sm((hour - t0) / (t1 - t0)))
            hour < t2 -> setEnv(DAWN, F_DAWN, DAY, F_DAY, sm((hour - t1) / (t2 - t1)))
            hour < t3 -> setEnv(DAY, F_DAY, DAY, F_DAY, 0f)
            hour < t4 -> setEnv(DAY, F_DAY, DUSK, F_DUSK, sm((hour - t3) / (t4 - t3)))
            else -> setEnv(DUSK, F_DUSK, NIGHT, F_NIGHT, sm((hour - t4) / (t5 - t4)))
        }
        for (i in 0 until layers) {
            layerCol[i] = lc(cur[2], cur[3], mixK[i])
            reflCol[i] = lc(layerCol[i], cur[5], 0.35f)
        }
        for (i in 0 until 64) {
            val f = i / 63f
            rampPx[i] = lc(cur[0], cur[1], Math.pow(f.toDouble(), 1.4).toFloat())
        }
        sky.setPixels(rampPx, 0, 1, 0, 0, 1, 64)
        for (i in 0 until 64) {
            val f = i / 63f
            rampPx[i] = lc(cur[5], cur[6], Math.pow(f.toDouble(), 0.8).toFloat())
        }
        water.setPixels(rampPx, 0, 1, 0, 0, 1, 64)

        // tor słońca i księżyca (łuk wschód -> zachód; poniżej horyzontu przykrywa je woda)
        val u = (hour - sr) / (ss - sr)
        sunX = W * (0.18f + 0.64f * u)
        sunY = y0 - sin(PI.toFloat() * u) * H * 0.34f
        val h2 = if (hour < sr) hour + 24f else hour
        val v = (h2 - ss) / (24f - (ss - sr))
        moonX = W * (0.18f + 0.64f * v)
        moonY = y0 - sin(PI.toFloat() * v) * H * 0.34f
    }

    private fun sm(x: Float): Float {
        val a = if (x < 0f) 0f else if (x > 1f) 1f else x
        return a * a * (3f - 2f * a)
    }

    // ------------------------------------------------------------ rysowanie

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var dt = (now - lastMs) / 1000f
        lastMs = now
        if (dt > 0.1f) dt = 0.0167f
        if (running && !simple) {
            measure(dt, now)
            t += dt
            if (t > 6000f) t = 0f
            val k = 1f - Math.exp((-7f * dt).toDouble()).toFloat()   // ease-out, stała czasowa ok. 140 ms
            par += (parallaxTarget - par) * k
        }
        drawScene(canvas)
        if (running && !simple) postInvalidateOnAnimation()
    }

    private fun measure(dt: Float, now: Long) {
        emaDt += (dt - emaDt) * 0.08f
        if (emaDt > 1f / 45f) {
            if (slowSince == 0L) slowSince = now
            else if (now - slowSince >= 3000L) {
                simple = true      // uproszczenie: bez paralaksy i odbić
                par = 0f
            }
        } else slowSince = 0L
    }

    /** Rysuje całą scenę w układzie ekranu (W x H); używane też do małej klatki dla szkła. */
    private fun drawScene(c: Canvas) {
        // niebo
        rf.set(0f, 0f, W, y0)
        c.drawBitmap(sky, srcSky, rf, pBmp)

        // gwiazdy
        val starAmt = curF[0]
        if (starAmt > 0.02f) {
            val n = stars.size / 4
            for (i in 0 until n) {
                val tw = if (simple) 1f else 0.78f + 0.22f * sin(t * 0.7f + stars[i * 4 + 3])
                val a = (starAmt * (0.30f + 0.45f * (stars[i * 4 + 2] - 0.5f)) * tw * 255f).toInt()
                pStar.setARGB(if (a < 0) 0 else if (a > 255) 255 else a, 240, 235, 220)
                c.drawCircle(W * stars[i * 4], y0 * stars[i * 4 + 1], W * 0.0007f * stars[i * 4 + 2], pStar)
            }
        }

        // słońce i księżyc: płaskie tarcze, bez poświat
        if (curF[1] > 0.02f) {
            pDisc.color = cur[7]
            pDisc.alpha = (curF[1] * 215f).toInt()
            c.drawCircle(sunX, sunY, W * 0.020f, pDisc)
        }
        if (curF[2] > 0.02f) {
            pDisc.color = cur[7]
            pDisc.alpha = (curF[2] * 200f).toInt()
            c.drawCircle(moonX, moonY, W * 0.014f, pDisc)
        }

        // góry + mgła
        for (i in 0 until layers) {
            val m = masks[i] ?: continue
            val left = -W * EXTRA - par * parK[i] * W
            rf.set(left, topY[i], left + m.width / MASK_S, y0)
            pMask.color = layerCol[i]
            c.drawBitmap(m, null, rf, pMask)
            val breathe = if (simple) 1f else 0.88f + 0.12f * sin(t * 0.22f + i * 1.7f)
            rf.set(0f, y0 - fogH[i] * H, W, y0)
            pMist.color = cur[4]
            pMist.alpha = (fogK[i] * breathe * 255f).toInt()
            c.drawBitmap(ramp, srcRamp, rf, pMist)
        }

        // woda
        rf.set(0f, y0, W, H)
        c.drawBitmap(water, srcSky, rf, pBmp)

        if (!simple) {
            // odbicie gór: lustro względem horyzontu, zanika w głąb jeziora
            c.save()
            c.clipRect(0f, y0, W, H)
            c.translate(0f, 2f * y0)
            c.scale(1f, -1f)
            for (i in 0 until layers) {
                val m = masks[i] ?: continue
                val left = -W * EXTRA - par * parK[i] * W
                rf.set(left, topY[i], left + m.width / MASK_S, y0)
                pMask.color = reflCol[i]
                pMask.alpha = 120
                c.drawBitmap(m, null, rf, pMask)
            }
            c.restore()
            pMask.alpha = 255
            rf.set(0f, y0, W, H)
            pMist.color = cur[6]
            pMist.alpha = 235
            c.drawBitmap(ramp, srcRamp, rf, pMist)
        }

        // przyciemnienie ok. 25% + winieta w jednym przebiegu
        c.drawRect(0f, 0f, W, H, pVig)
    }

    /** Mała klatka (np. 128x72) dla szkła. */
    fun snapshotTo(bmp: Bitmap, cv: Canvas) {
        cv.save()
        cv.scale(bmp.width / W, bmp.height / H)
        drawScene(cv)
        cv.restore()
    }
}
