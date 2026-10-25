package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spokojna natura: 6 warstw mglistych gór nad jeziorem (perspektywa atmosferyczna: dalsze jaśniejsze, w kolorze mgły),
 * miękkie rozproszone światło i promienie, powoli dryfujące chmury (jednorazowo wyrenderowane z szumu),
 * mgła między warstwami, połysk i odbicie na wodzie z wolną falą, migoczące gwiazdy i rzadka spadająca gwiazda.
 * Pory dnia (noc, świt, dzień, złota godzina, zmierzch) płynnie wg zegara.
 *
 * Wszystkie bitmapy powstają raz, w wątku w tle (maski ALPHA_8 w połowie rozdzielczości, chmury i mgła w połowie,
 * niebo/woda RGB_565). W onDraw nic się nie alokuje. Animacja max 30 fps; poniżej 25 fps przez 3 s: [simple] = true
 * (bez mgły, migotania i spadającej gwiazdy).
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
    private var emaDt = 0.0333f
    private var slowSince = 0L
    private var td = 0.0
    private var lastPostNs = 0L
    private var snap = false

    // ---- geometria warstw (0 = najdalsza)
    private val L = 6
    private val hMaxK = floatArrayOf(0.34f, 0.27f, 0.21f, 0.155f, 0.11f, 0.068f)
    private val freqK = floatArrayOf(1.7f, 2.3f, 3.1f, 4.2f, 5.6f, 7.5f)
    private val parK = floatArrayOf(0.004f, 0.008f, 0.014f, 0.022f, 0.032f, 0.045f)
    private val hazeK = floatArrayOf(0.74f, 0.60f, 0.46f, 0.31f, 0.16f, 0.03f)   // domieszka koloru mgły
    private val mixK = floatArrayOf(0.00f, 0.18f, 0.38f, 0.58f, 0.80f, 1.00f)     // far -> near
    private val rampK = floatArrayOf(0.50f, 0.42f, 0.35f, 0.28f, 0.21f, 0.13f)
    private val rampH = floatArrayOf(0.30f, 0.25f, 0.20f, 0.16f, 0.12f, 0.08f)
    private val driftV = floatArrayOf(1.5f, 2.5f, 3.5f, 5f, 6.5f, 8f)            // px/min przy szer. 1920
    private val driftA = floatArrayOf(0.003f, 0.005f, 0.007f, 0.009f, 0.011f, 0.013f)
    private val driftPh = floatArrayOf(0f, 1.3f, 2.6f, 3.9f, 5.2f, 0.7f)
    private val driftW = FloatArray(L) { driftV[it] / (60f * 1920f * driftA[it]) }
    private val dOff = FloatArray(L)
    private val MASK_S = 0.5f
    private val EXTRA = 0.06f

    // ---- bitmapy (budowane w tle)
    private class Assets(
        val masks: Array<Bitmap>, val topY: FloatArray,
        val clouds: Array<Bitmap>, val fog: Array<Bitmap>,
        val rays: Bitmap, val glow: Bitmap
    )

    @Volatile private var assets: Assets? = null
    @Volatile private var gen = 0
    private var asm: Assets? = null          // kopia dla wątku UI (bez volatile w onDraw)
    private val sky = Bitmap.createBitmap(1, 64, Bitmap.Config.RGB_565)
    private val water = Bitmap.createBitmap(1, 64, Bitmap.Config.RGB_565)
    private val rampPx = IntArray(64)
    private val ramp = Bitmap.createBitmap(4, 32, Bitmap.Config.ALPHA_8)
    private val nz = FloatArray(1024)

    // ---- chmury: [bitmapa, y (ułamek y0), skala, prędkość px/s@1920, start 0..1, mnożnik alfa]
    private val NC = 5
    private val cB = intArrayOf(0, 1, 2, 0, 1)
    private val cY = floatArrayOf(0.20f, 0.34f, 0.13f, 0.50f, 0.26f)
    private val cS = floatArrayOf(1.7f, 1.3f, 2.1f, 1.0f, 1.5f)
    private val cV = floatArrayOf(5f, 8f, 3.5f, 11f, 6.5f)
    private val cP = floatArrayOf(0.10f, 0.45f, 0.72f, 0.88f, 0.28f)
    private val cA = floatArrayOf(0.85f, 0.70f, 0.60f, 0.45f, 0.65f)

    // ---- mgła między warstwami: pasma przy warstwach 0..4
    private val NF = 5
    private val fV = floatArrayOf(7f, -5f, 9f, -6.5f, 11f)
    private val fAlpha = floatArrayOf(0.42f, 0.38f, 0.34f, 0.30f, 0.26f)
    private val fHk = floatArrayOf(0.40f, 0.46f, 0.50f, 0.54f, 0.58f)   // wysokość środka pasma względem hMaxK
    private val fx = FloatArray(NF)

    // ---- paleta: [skyTop, skyHor, mtnFar, mtnNear, mist, waterTop, waterBot, celestial, cloud, light]
    //     czynniki: [stars, sun, moon, clouds, rays, glow]
    private val NIGHT = intArrayOf(hex(0xFF0B101CL), hex(0xFF1B2538L), hex(0xFF1F2A3EL), hex(0xFF090D14L), hex(0xFF2A3548L), hex(0xFF1A2434L), hex(0xFF0A0F18L), hex(0xFFE8E2D2L), hex(0xFF3A465EL), hex(0xFF9AA8C8L))
    private val DAWN = intArrayOf(hex(0xFF3B4A68L), hex(0xFFD6B9A0L), hex(0xFF8A8FA3L), hex(0xFF2F3A4AL), hex(0xFFD9C4B0L), hex(0xFFA8A6ACL), hex(0xFF3A465AL), hex(0xFFF0DDBDL), hex(0xFFE9C9B0L), hex(0xFFFFD9A8L))
    private val DAY = intArrayOf(hex(0xFF6C8CA6L), hex(0xFFE3DACBL), hex(0xFF93A8ACL), hex(0xFF3F5B54L), hex(0xFFE6E0D2L), hex(0xFFC9D2D0L), hex(0xFF4F6E78L), hex(0xFFF2E6C9L), hex(0xFFF4F1EAL), hex(0xFFFFF3D6L))
    private val GOLD = intArrayOf(hex(0xFF46587AL), hex(0xFFF0B27AL), hex(0xFFB08A8CL), hex(0xFF2C3442L), hex(0xFFEBC08FL), hex(0xFFD9A584L), hex(0xFF3C4558L), hex(0xFFFFD9A0L), hex(0xFFF6C79AL), hex(0xFFFFC68AL))
    private val DUSK = intArrayOf(hex(0xFF2C3654L), hex(0xFFCE9F82L), hex(0xFF76728AL), hex(0xFF262C40L), hex(0xFFC9A58FL), hex(0xFFA08C90L), hex(0xFF2A3248L), hex(0xFFF0C9A0L), hex(0xFFB88FA0L), hex(0xFFE8A98CL))
    private val F_NIGHT = floatArrayOf(1f, 0f, 1f, 0.25f, 0f, 0f)
    private val F_DAWN = floatArrayOf(0.25f, 1f, 0.35f, 0.70f, 0.55f, 0.80f)
    private val F_DAY = floatArrayOf(0f, 1f, 0f, 0.80f, 0.30f, 0.50f)
    private val F_GOLD = floatArrayOf(0f, 1f, 0f, 0.85f, 0.70f, 1.00f)
    private val F_DUSK = floatArrayOf(0.30f, 1f, 0.35f, 0.60f, 0.35f, 0.60f)
    private val pals = arrayOf(NIGHT, DAWN, DAY, GOLD, DUSK)
    private val palF = arrayOf(F_NIGHT, F_DAWN, F_DAY, F_GOLD, F_DUSK)
    private val kP = intArrayOf(0, 1, 2, 2, 3, 4, 0)
    private val kT = FloatArray(7)

    private val cur = IntArray(10)
    private val curF = FloatArray(6)
    private val layerCol = IntArray(L)
    private val reflCol = IntArray(L)
    private var sunX = 0f
    private var sunY = 0f
    private var moonX = 0f
    private var moonY = 0f
    private var lightX = 0f
    private var lightAmt = 0f

    // ---- gwiazdy i spadająca gwiazda
    private var stars = FloatArray(0)
    private val rnd = Random(5)
    private var ssT = -1f
    private var ssNext = 70f
    private var ssX = 0f
    private var ssY = 0f
    private var ssDir = 1f

    // ---- farby i obiekty robocze (alokowane raz)
    private var vigSh: RadialGradient? = null
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pMist = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pCloud = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDisc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSS = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShim = Paint()
    private val pGlow = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pVig = Paint()
    private val rf = RectF()
    private val srcSky = Rect(0, 0, 1, 64)
    private val srcRamp = Rect(0, 0, 4, 32)
    private val mt = Matrix()
    private val cal = Calendar.getInstance()

    private val envTask = object : Runnable {
        override fun run() {
            if (!running) return
            updateEnv()
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (frameTimeNanos - lastPostNs >= 28_000_000L) {   // ~30 fps przy 60 Hz
                lastPostNs = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        val r = Random(11)
        for (i in nz.indices) nz[i] = r.nextFloat()
        // rampa alfa 0 -> 255 (do mgły u podstawy warstw i zanikania odbicia)
        val rp = Paint()
        rp.shader = LinearGradient(
            0f, 0f, 0f, 32f,
            intArrayOf(0x00FFFFFF, 0x0DFFFFFF, 0x40FFFFFF, hex(0x99FFFFFFL), -1),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(ramp).drawRect(0f, 0f, 4f, 32f, rp)
        pVig.isDither = true
        pSS.style = Paint.Style.STROKE
        pSS.strokeCap = Paint.Cap.ROUND
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
        Choreographer.getInstance().removeFrameCallback(frameCb)
        Choreographer.getInstance().postFrameCallback(frameCb)
        invalidate()
    }

    fun stop() {
        running = false
        removeCallbacks(envTask)
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        stop()
        gen++
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ geometria + bitmapy w tle

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        W = w.toFloat()
        H = h.toFloat()
        y0 = H * 0.70f
        for (b in 0 until NF) fx[b] = (b * 0.23f) * W
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
        val my = ++gen
        val ww = W
        val hh = H
        val yy = y0
        Thread {
            val a = try {
                buildAssets(ww, hh, yy)
            } catch (e: Throwable) {
                null
            }
            if (a != null) post {
                if (my == gen) {
                    val old = asm
                    asm = a
                    assets = a
                    invalidate()
                    if (old != null) recycleAssets(old)
                } else recycleAssets(a)
            }
        }.start()
    }

    private fun recycleAssets(a: Assets) {
        for (b in a.masks) b.recycle()
        for (b in a.clouds) b.recycle()
        for (b in a.fog) b.recycle()
        a.rays.recycle()
        a.glow.recycle()
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Szum wartościowy 2D; per > 0 = okres w osi X (bezszwowe zapętlenie). */
    private fun vn2(x: Float, y: Float, s: Int, per: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val x0 = if (per > 0) Math.floorMod(xi, per) else xi
        val x1 = if (per > 0) Math.floorMod(xi + 1, per) else xi + 1
        val a = hash(x0, yi, s)
        val b = hash(x1, yi, s)
        val c = hash(x0, yi + 1, s)
        val d = hash(x1, yi + 1, s)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    private fun fbm2(x: Float, y: Float, s: Int, per: Int, oct: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var f = 1f
        var p = per
        var norm = 0f
        for (o in 0 until oct) {
            sum += amp * vn2(x * f, y * f, s + o * 17, p)
            norm += amp
            amp *= 0.5f
            f *= 2f
            if (p > 0) p *= 2
        }
        return sum / norm
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val a = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return a * a * (3f - 2f * a)
    }

    private fun noise(x: Float, seed: Int): Float {
        val xi = floor(x).toInt()
        val f = x - xi
        val a = nz[(xi + seed * 131) and 1023]
        val b = nz[(xi + 1 + seed * 131) and 1023]
        val s = (1f - cos(f * PI.toFloat())) * 0.5f
        return a + (b - a) * s
    }

    /** Wątek tła: maski gór, chmury, pasma mgły, promienie, poświata. */
    private fun buildAssets(w: Float, h: Float, base: Float): Assets {
        val topY = FloatArray(L)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = -1
        val path = Path()
        val masks = Array(L) { i ->
            val top = base - hMaxK[i] * h * 1.06f
            topY[i] = top
            val mw = ceil(w * (1f + 2f * EXTRA) * MASK_S).toInt()
            val mh = ceil((base - top) * MASK_S).toInt() + 1
            val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            path.rewind()
            path.moveTo(0f, mh.toFloat())
            var x = 0
            while (x <= mw + 4) {
                val xn = (x / MASK_S - w * EXTRA) / w
                val n = 0.50f * noise(xn * freqK[i], i) + 0.27f * noise(xn * freqK[i] * 2.3f + 9f, i) +
                        0.15f * noise(xn * freqK[i] * 5.1f + 17f, i) + 0.08f * noise(xn * freqK[i] * 11f + 31f, i)
                val hf = 0.16f + 0.84f * n
                val yy = (base - hf * hMaxK[i] * h - top) * MASK_S
                path.lineTo(x.toFloat(), yy)
                x += 3
            }
            path.lineTo(mw.toFloat() + 4f, mh.toFloat())
            path.close()
            c.drawPath(path, fill)
            bmp
        }

        // chmury: 3 sprite'y z szumu, miękkie krawędzie, połowa rozdzielczości
        val clouds = Array(3) { k ->
            val cw = 448
            val ch = 144
            val px = IntArray(cw * ch)
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val nx = (x + 0.5f) / cw * 2f - 1f
                    var ny = (y + 0.5f) / ch * 2f - 1f
                    var shape = 1f - (nx * nx * 0.85f + ny * ny * 1.6f)
                    if (ny > 0.15f) shape -= (ny - 0.15f) * 1.2f
                    val n = fbm2(x * 0.022f + k * 7f, y * 0.045f, 40 + k * 5, 0, 5)
                    val v = shape * 1.15f + (n - 0.5f) * 0.95f - 0.10f
                    val a = smooth(0f, 0.55f, v)
                    px[y * cw + x] = (a * 255f).toInt() shl 24
                }
            }
            val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ALPHA_8)
            bmp.setPixels(px, 0, cw, 0, 0, cw, ch)
            bmp
        }

        // pasma mgły (bezszwowe w poziomie), połowa rozdzielczości ekranu
        val fw = Math.max(64, (w * 0.5f).toInt())
        val fh = Math.max(16, (h * 0.14f * 0.5f).toInt())
        val fog = Array(2) { k ->
            val per = 8
            val sc = per / fw.toFloat()
            val px = IntArray(fw * fh)
            for (y in 0 until fh) {
                val ev = sin(PI.toFloat() * (y + 0.5f) / fh)
                for (x in 0 until fw) {
                    val n = fbm2(x * sc, y / fh.toFloat() * 2.2f + k * 3f, 90 + k * 11, per, 4)
                    val a = smooth(0.30f, 0.78f, n) * ev * ev
                    px[y * fw + x] = (a * 255f).toInt() shl 24
                }
            }
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8)
            bmp.setPixels(px, 0, fw, 0, 0, fw, fh)
            bmp
        }

        // promienie (radialne smugi) i poświata
        val rs = 256
        val rpx = IntArray(rs * rs)
        for (y in 0 until rs) {
            for (x in 0 until rs) {
                val dx = (x + 0.5f - rs / 2f) / (rs / 2f)
                val dy = (y + 0.5f - rs / 2f) / (rs / 2f)
                val r = sqrt(dx * dx + dy * dy)
                if (r >= 1f) continue
                val ang = (atan2(dy, dx) / (2f * PI.toFloat()) + 0.5f)
                val n = 0.6f * vn2(ang * 24f, 0.5f, 3, 24) + 0.4f * vn2(ang * 48f, 1.5f, 5, 48)
                val streak = 0.25f + 0.75f * smooth(0.35f, 0.75f, n)
                val fall = (1f - r) * (1f - r)
                rpx[y * rs + x] = ((streak * fall * 0.85f).coerceIn(0f, 1f) * 255f).toInt() shl 24
            }
        }
        val rays = Bitmap.createBitmap(rs, rs, Bitmap.Config.ALPHA_8)
        rays.setPixels(rpx, 0, rs, 0, 0, rs, rs)
        val gs = 128
        val gpx = IntArray(gs * gs)
        for (y in 0 until gs) {
            for (x in 0 until gs) {
                val dx = (x + 0.5f - gs / 2f) / (gs / 2f)
                val dy = (y + 0.5f - gs / 2f) / (gs / 2f)
                val r = sqrt(dx * dx + dy * dy)
                if (r >= 1f) continue
                val a = (1f - r)
                gpx[y * gs + x] = ((a * a * a).coerceIn(0f, 1f) * 255f).toInt() shl 24
            }
        }
        val glow = Bitmap.createBitmap(gs, gs, Bitmap.Config.ALPHA_8)
        glow.setPixels(gpx, 0, gs, 0, 0, gs, gs)
        return Assets(masks, topY, clouds, fog, rays, glow)
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

    private fun setEnv(a: Int, b: Int, f: Float) {
        val pa = pals[a]
        val pb = pals[b]
        for (i in 0 until 10) cur[i] = lc(pa[i], pb[i], f)
        val fa = palF[a]
        val fb = palF[b]
        for (i in 0 until 6) curF[i] = fa[i] + (fb[i] - fa[i]) * f
    }

    private fun updateEnv() {
        cal.timeInMillis = System.currentTimeMillis()
        val hour = if (hourOverride >= 0f) hourOverride else
            cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f + cal.get(Calendar.SECOND) / 3600f
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val c = cos(2.0 * PI * (doy - 172) / 365.0).toFloat()
        val sr = 6.25f - 1.75f * c        // orientacyjny wschód (godz.), bez lokalizacji
        val ss = 18.25f + 2.75f * c       // orientacyjny zachód
        kT[0] = sr - 1.3f
        kT[1] = sr - 0.1f
        kT[2] = sr + 1.8f
        kT[3] = ss - 2.6f
        kT[4] = ss - 0.9f
        kT[5] = ss + 0.2f
        kT[6] = ss + 1.3f
        if (hour <= kT[0] || hour >= kT[6]) setEnv(0, 0, 0f)
        else {
            var i = 0
            while (i < 5 && hour >= kT[i + 1]) i++
            setEnv(kP[i], kP[i + 1], sm((hour - kT[i]) / (kT[i + 1] - kT[i])))
        }
        for (i in 0 until L) {
            layerCol[i] = lc(lc(cur[2], cur[3], mixK[i]), cur[4], hazeK[i])
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
        val sunUp = ((y0 - sunY) / (H * 0.06f)).coerceIn(0f, 1f) * curF[1]
        val moonUp = ((y0 - moonY) / (H * 0.06f)).coerceIn(0f, 1f) * curF[2]
        if (sunUp >= moonUp * 0.7f) {
            lightX = sunX
            lightAmt = sunUp
        } else {
            lightX = moonX
            lightAmt = moonUp * 0.7f
        }
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
        if (dt > 0.2f) dt = 0.0333f
        if (running) {
            measure(dt, now)
            t += dt
            if (t > 6000f) {
                t = 0f
                if (ssNext > 120f) ssNext = 60f
            }
            td += dt
            val k = 1f - Math.exp((-7f * dt).toDouble()).toFloat()   // ease-out, stała czasowa ok. 140 ms
            par += (parallaxTarget - par) * k
            if (!simple) {
                val sc = W / 1920f
                for (b in 0 until NF) {
                    var x = fx[b] + fV[b] * sc * dt
                    if (x >= W) x -= W else if (x < 0f) x += W
                    fx[b] = x
                }
                stepShooting(dt)
            } else ssT = -1f
        }
        snap = false
        drawScene(canvas)
    }

    /** Poniżej 25 fps (średnio) przez 3 s: wyłącz mgłę, migotanie i spadającą gwiazdę. */
    private fun measure(dt: Float, now: Long) {
        emaDt += (dt - emaDt) * 0.08f
        if (emaDt > 0.0425f) {
            if (slowSince == 0L) slowSince = now
            else if (!simple && now - slowSince >= 3000L) simple = true
        } else slowSince = 0L
    }

    private fun stepShooting(dt: Float) {
        if (ssT < 0f) {
            if (curF[0] > 0.9f && t >= ssNext) {
                ssDir = if (rnd.nextBoolean()) 1f else -1f
                ssX = W * (0.2f + 0.6f * rnd.nextFloat())
                ssY = y0 * (0.05f + 0.25f * rnd.nextFloat())
                ssT = 0f
            }
        } else {
            ssT += dt
            if (ssT > 1.1f) {
                ssT = -1f
                ssNext = t + 90f + 150f * rnd.nextFloat()      // bardzo rzadko: co 1.5-4 min
            }
        }
    }

    /** Rysuje całą scenę w układzie ekranu (W x H); używane też do małej klatki dla szkła. */
    private fun drawScene(c: Canvas) {
        for (i in 0 until L) dOff[i] = (driftA[i] * W * sin(td * driftW[i] + driftPh[i])).toFloat()
        // niebo
        rf.set(0f, 0f, W, y0)
        c.drawBitmap(sky, srcSky, rf, pBmp)

        // gwiazdy (migotanie wyłączane w trybie simple)
        val starAmt = curF[0]
        if (starAmt > 0.02f) {
            val n = stars.size / 4
            for (i in 0 until n) {
                val tw = if (simple || snap) 1f else 0.74f + 0.26f * sin(t * (0.45f + 0.5f * stars[i * 4 + 2]) + stars[i * 4 + 3])
                val a = (starAmt * (0.30f + 0.45f * (stars[i * 4 + 2] - 0.5f)) * tw * 255f).toInt()
                pStar.setARGB(if (a < 0) 0 else if (a > 255) 255 else a, 240, 235, 220)
                c.drawCircle(W * stars[i * 4], y0 * stars[i * 4 + 1], W * 0.0007f * stars[i * 4 + 2], pStar)
            }
            if (ssT >= 0f && !snap) drawShooting(c, starAmt)
        }

        val a = asm
        val sunA = curF[1]
        val moonA = curF[2]
        // miękkie rozproszone światło wokół słońca i księżyca
        if (a != null) {
            if (sunA > 0.02f && curF[5] > 0.02f) {
                pGlow.color = cur[9]
                glowAt(c, a.glow, sunX, sunY, W * 0.70f, 0.20f * curF[5] * sunA)
                glowAt(c, a.glow, sunX, sunY, W * 0.26f, 0.42f * curF[5] * sunA)
            }
            if (moonA > 0.02f) {
                pGlow.color = cur[9]
                glowAt(c, a.glow, moonX, moonY, W * 0.16f, 0.30f * moonA)
            }
            // promienie: bardzo wolno obracane, przycięte do nieba
            if (!snap && curF[4] > 0.02f && sunA > 0.02f) {
                val vis = ((y0 - sunY) / (H * 0.04f)).coerceIn(0f, 1f)
                val s = W * 1.15f / a.rays.width
                mt.setRotate(7f * sin(t * 0.03f), a.rays.width / 2f, a.rays.height / 2f)
                mt.postScale(s, s)
                mt.postTranslate(sunX - a.rays.width / 2f * s, sunY - a.rays.height / 2f * s)
                pGlow.color = cur[9]
                pGlow.alpha = (curF[4] * vis * sunA * 0.30f * 255f).toInt()
                c.save()
                c.clipRect(0f, 0f, W, y0)
                c.drawBitmap(a.rays, mt, pGlow)
                c.restore()
            }
        }

        // słońce i księżyc: tarcze
        if (sunA > 0.02f) {
            pDisc.color = cur[7]
            pDisc.alpha = (sunA * 225f).toInt()
            c.drawCircle(sunX, sunY, W * 0.019f, pDisc)
        }
        if (moonA > 0.02f) {
            pDisc.color = cur[7]
            pDisc.alpha = (moonA * 205f).toInt()
            c.drawCircle(moonX, moonY, W * 0.013f, pDisc)
        }

        if (a == null) {
            // bitmapy jeszcze się generują w tle: samo niebo + woda
            rf.set(0f, y0, W, H)
            c.drawBitmap(water, srcSky, rf, pBmp)
            c.drawRect(0f, 0f, W, H, pVig)
            return
        }

        // chmury (przesuwane macierzą, miękkie krawędzie)
        if (curF[3] > 0.02f) {
            for (i in 0 until NC) {
                val bmp = a.clouds[cB[i]]
                val s = cS[i] * W / 1920f * 2f
                val cw = bmp.width * s
                val span = W + 2f * cw
                var x = (cP[i] * span + cV[i] * (W / 1920f) * td.toFloat()) % span
                if (x < 0f) x += span
                x = x - cw - par * parK[2] * W
                mt.setScale(s, s)
                mt.postTranslate(x, y0 * cY[i] - bmp.height * s / 2f)
                pCloud.color = cur[8]
                pCloud.alpha = (curF[3] * cA[i] * 215f).toInt()
                c.drawBitmap(bmp, mt, pCloud)
            }
        }

        // góry + mgła
        val showFog = !simple
        for (i in 0 until L) {
            val m = a.masks[i]
            val left = -W * EXTRA - par * parK[i] * W + dOff[i]
            rf.set(left, a.topY[i], left + m.width / MASK_S, y0)
            pMask.color = layerCol[i]
            c.drawBitmap(m, null, rf, pMask)
            val breathe = if (simple) 1f else 0.88f + 0.12f * sin(t * 0.22f + i * 1.7f)
            rf.set(0f, y0 - rampH[i] * H, W, y0)
            pMist.color = cur[4]
            pMist.alpha = (rampK[i] * breathe * 255f).toInt()
            c.drawBitmap(ramp, srcRamp, rf, pMist)
            if (showFog && i < NF) {
                val fb = a.fog[i and 1]
                val bandH = H * 0.14f
                val cy = y0 - hMaxK[i] * H * fHk[i]
                pMist.alpha = (fAlpha[i] * breathe * (0.55f + 0.45f * curF[3] + 0.0f) * 255f).toInt()
                var x = -fx[i] - par * parK[i] * W
                rf.set(x, cy - bandH / 2f, x + W, cy + bandH / 2f)
                c.drawBitmap(fb, null, rf, pMist)
                rf.set(x + W, cy - bandH / 2f, x + 2f * W, cy + bandH / 2f)
                c.drawBitmap(fb, null, rf, pMist)
                rf.set(x - W, cy - bandH / 2f, x, cy + bandH / 2f)
                c.drawBitmap(fb, null, rf, pMist)
            }
        }

        // woda
        rf.set(0f, y0, W, H)
        c.drawBitmap(water, srcSky, rf, pBmp)

        // odbicie gór: lustro względem horyzontu, 3 pasy z wolną falą (przesunięcie poziome), zanika w głąb jeziora
        val wh = H - y0
        for (b in 0 until 3) {
            val yb0 = y0 + wh * b / 3f
            val yb1 = y0 + wh * (b + 1) / 3f
            val shift = if (snap) 0f else sin(t * 0.55f + b * 1.9f) * W * 0.0016f * (1f + b)
            c.save()
            c.clipRect(0f, yb0, W, yb1)
            c.translate(shift, 2f * y0)
            c.scale(1f, -1f)
            for (i in 0 until L) {
                val m = a.masks[i]
                val left = -W * EXTRA - par * parK[i] * W + dOff[i]
                rf.set(left, a.topY[i], left + m.width / MASK_S, y0)
                pMask.color = reflCol[i]
                pMask.alpha = 120
                c.drawBitmap(m, null, rf, pMask)
            }
            c.restore()
        }
        pMask.alpha = 255
        rf.set(0f, y0, W, H)
        pMist.color = cur[6]
        pMist.alpha = 235
        c.drawBitmap(ramp, srcRamp, rf, pMist)

        // połysk: cienka linia horyzontu + migotliwa ścieżka światła słońca/księżyca + delikatne fale
        if (!snap) drawWaterShine(c)

        // przyciemnienie ok. 25% + winieta w jednym przebiegu
        c.drawRect(0f, 0f, W, H, pVig)
    }

    private fun glowAt(c: Canvas, bmp: Bitmap, x: Float, y: Float, radius: Float, alpha: Float) {
        val s = radius / (bmp.width / 2f)
        mt.setScale(s, s)
        mt.postTranslate(x - radius, y - radius)
        pGlow.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        c.drawBitmap(bmp, mt, pGlow)
    }

    private fun drawWaterShine(c: Canvas) {
        val wh = H - y0
        pShim.color = cur[4]
        pShim.alpha = 70
        c.drawRect(0f, y0 - 0.5f, W, y0 + 1.5f, pShim)
        val n = 34
        // szerokie, bardzo słabe pasy fal
        pShim.color = cur[7]
        for (k in 0 until 8) {
            val f = (k + 0.5f) / 8f
            val y = y0 + wh * f * f
            val ph = sin(t * 0.35f + k * 2.1f)
            pShim.alpha = (7f + 5f * ph).toInt()
            val wdt = W * (0.35f + 0.25f * f)
            val cx = W * 0.5f + ph * W * 0.08f
            c.drawRect(cx - wdt / 2f, y, cx + wdt / 2f, y + 1f + f * 3f, pShim)
        }
        if (lightAmt < 0.03f) return
        for (k in 0 until n) {
            val f = (k + 0.5f) / n
            val y = y0 + wh * f * f * 0.95f
            val tw = 0.5f + 0.5f * sin(t * (0.9f + 0.05f * k) + k * 2.3f)
            val wdt = W * (0.012f + 0.11f * f) * (0.55f + 0.45f * sin(t * 0.6f + k * 1.7f))
            val ox = sin(t * 0.4f + k * 0.9f) * W * 0.01f * (0.3f + f)
            pShim.alpha = (lightAmt * tw * (60f - 28f * f)).toInt()
            c.drawRect(lightX + ox - wdt / 2f, y, lightX + ox + wdt / 2f, y + 1f + f * 3.2f, pShim)
        }
    }

    private fun drawShooting(c: Canvas, night: Float) {
        val env = sin(PI.toFloat() * (ssT / 1.1f).coerceIn(0f, 1f))
        val vx = ssDir * W * 0.42f
        val vy = W * 0.17f
        val hx = ssX + vx * ssT
        val hy = ssY + vy * ssT
        val len = hypot(vx, vy)
        val ux = vx / len
        val uy = vy / len
        val tl = W * 0.11f
        val seg = 8
        for (k in 0 until seg) {
            val a0 = tl * k / seg
            val a1 = tl * (k + 1) / seg
            pSS.setARGB((env * (1f - k / seg.toFloat()) * night * 215f).toInt(), 245, 240, 228)
            pSS.strokeWidth = Math.max(1f, W * 0.0013f * (1f - k * 0.09f))
            c.drawLine(hx - ux * a0, hy - uy * a0, hx - ux * a1, hy - uy * a1, pSS)
        }
    }

    /** Mała klatka (np. 128x72) dla szkła. */
    fun snapshotTo(bmp: Bitmap, cv: Canvas) {
        cv.save()
        cv.scale(bmp.width / W, bmp.height / H)
        snap = true
        drawScene(cv)
        snap = false
        cv.restore()
    }
}
