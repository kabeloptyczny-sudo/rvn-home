package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Filmowa natura rysowana kodem: niebo zależne od pory dnia (świt, dzień, złota godzina, noc),
 * słońce/księżyc, gwiazdy, chmury, 4 warstwy gór z mgłą i paralaksą, woda z odbiciem i połyskiem,
 * promienie słońca, bloom, ptaki / świetliki, ziarno filmowe, winieta.
 * Wszystkie obiekty (Paint, Path, Bitmap, shadery) powstają poza onDraw. Jakość spada sama przy wolnych klatkach.
 */
class NatureView(context: Context) : View(context) {

    var quality = 2
    var running = false
        private set
    var parallaxTarget = 0f
    var hourOverride = -1f

    private var W = 1920f
    private var H = 1080f
    private var y0 = 756f
    private var t = 0f
    private var lastNs = 0L
    private var envAcc = 99f
    private var bad = 0
    private var par = 0f
    private var snap = false
    private var grainFrame = 0
    private val cal = Calendar.getInstance()
    private val rnd = Random(5)

    // ------------------------------------------------------------ paleta wg wysokości słońca
    private val ES = floatArrayOf(-0.25f, -0.08f, 0f, 0.12f, 0.35f, 1f)
    private val TOP = intArrayOf(hex(0xFF02030AL), hex(0xFF0A1033L), hex(0xFF1C2B63L), hex(0xFF3A5FA8L), hex(0xFF2E78D0L), hex(0xFF1C6BD0L))
    private val MID = intArrayOf(hex(0xFF070B1EL), hex(0xFF2A2358L), hex(0xFFB4527AL), hex(0xFFE8A27CL), hex(0xFF6FB1EAL), hex(0xFF5BA7EFL))
    private val HOR = intArrayOf(hex(0xFF101A33L), hex(0xFF6B4A6FL), hex(0xFFFF9A55L), hex(0xFFFFC98AL), hex(0xFFCFE6F5L), hex(0xFFBFE0F7L))
    private val SUNC = intArrayOf(hex(0xFFFF8A4AL), hex(0xFFFF8A4AL), hex(0xFFFF8A4AL), hex(0xFFFFB878L), hex(0xFFFFE6B8L), hex(0xFFFFF6E6L))
    private val CLOUD = intArrayOf(hex(0xFF1A2038L), hex(0xFF3A3560L), hex(0xFFFFB090L), hex(0xFFFFD8B8L), hex(0xFFF4F8FFL), hex(0xFFFFFFFFL))
    private val NEAR = intArrayOf(hex(0xFF02040AL), hex(0xFF0A0C1CL), hex(0xFF22162AL), hex(0xFF2C2230L), hex(0xFF1F3347L), hex(0xFF1A2C40L))

    // ------------------------------------------------------------ stan środowiska
    private var eLev = 0.5f
    private var sunX = 0f
    private var sunY = 0f
    private var sunAmt = 0f
    private var moonX = 0f
    private var moonY = 0f
    private var moonAmt = 0f
    private var nightAmt = 0f
    private var rayAmt = 0f
    private var lightX = 0f
    private var lightAmt = 0f
    private var lightCol = 0
    private var cTop = 0
    private var cMid = 0
    private var cHor = 0
    private var sunCol = 0
    private var cloudLit = 0
    private var cloudShade = 0
    private var waterCol = 0
    private var flyAmt = 0f
    private var birdAmt = 0f

    // ------------------------------------------------------------ góry
    private val layers = 4
    private val ridge = Array(layers) { Path() }
    private val hMaxK = floatArrayOf(0.30f, 0.24f, 0.17f, 0.10f)
    private val freqK = floatArrayOf(2.2f, 3.1f, 4.6f, 7f)
    private val parK = floatArrayOf(0.006f, 0.012f, 0.022f, 0.040f)
    private val fogK = floatArrayOf(0.60f, 0.48f, 0.34f, 0.20f)
    private val fogH = floatArrayOf(0.30f, 0.26f, 0.20f, 0.12f)
    private val mixK = floatArrayOf(0.20f, 0.40f, 0.66f, 1f)
    private val layerCol = IntArray(layers)
    private val nz = FloatArray(512)

    // ------------------------------------------------------------ obiekty rysujące
    private val sky = Bitmap.createBitmap(2, 64, Bitmap.Config.ARGB_8888)
    private val skyPx = IntArray(128)
    private val glow = Bitmap.createBitmap(128, 128, Bitmap.Config.ALPHA_8)
    private val rays = Bitmap.createBitmap(256, 256, Bitmap.Config.ALPHA_8)
    private val vGrad = Bitmap.createBitmap(4, 32, Bitmap.Config.ALPHA_8)
    private val wGrad = Bitmap.createBitmap(4, 32, Bitmap.Config.ALPHA_8)
    private val clouds = arrayOfNulls<Bitmap>(3)
    private val grainSh: BitmapShader
    private val grainM = Matrix()
    private var vigSh: RadialGradient? = null

    private val pSky = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pAdd = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrain = Paint()
    private val pVig = Paint()
    private val rf = RectF()
    private val fogRect = RectF()
    private val birdPts = FloatArray(16)

    // ------------------------------------------------------------ elementy sceny
    private val NS = 170
    private val stX = FloatArray(NS); private val stY = FloatArray(NS); private val stSz = FloatArray(NS)
    private val stPh = FloatArray(NS); private val stSp = FloatArray(NS); private val stB = FloatArray(NS)

    private val NG = 64
    private val gX = FloatArray(NG); private val gY = FloatArray(NG); private val gW = FloatArray(NG)
    private val gP = FloatArray(NG); private val gS = FloatArray(NG)
    private val NR = 36
    private val rX = FloatArray(NR); private val rY = FloatArray(NR); private val rL = FloatArray(NR)
    private val rP = FloatArray(NR); private val rS = FloatArray(NR)

    private val NC = 7
    private val cX = FloatArray(NC); private val cY = FloatArray(NC); private val cSc = FloatArray(NC)
    private val cSp = FloatArray(NC); private val cA = FloatArray(NC); private val cK = IntArray(NC)
    private val cPar = FloatArray(NC)

    private val NM = 7
    private val mX = FloatArray(NM); private val mL = IntArray(NM); private val mSp = FloatArray(NM)
    private val mW = FloatArray(NM); private val mY = FloatArray(NM)

    private val NB = 6
    private val bX = FloatArray(NB); private val bY = FloatArray(NB); private val bSp = FloatArray(NB)
    private val bSz = FloatArray(NB); private val bPh = FloatArray(NB)

    private val NF = 30
    private val fX = FloatArray(NF); private val fY = FloatArray(NF); private val fSx = FloatArray(NF)
    private val fSy = FloatArray(NF); private val fPh = FloatArray(NF); private val fSp = FloatArray(NF)
    private val fSz = FloatArray(NF)

    init {
        pAdd.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        pStar.strokeCap = Paint.Cap.ROUND
        pLine.style = Paint.Style.STROKE
        pLine.strokeCap = Paint.Cap.ROUND
        pLine.strokeJoin = Paint.Join.ROUND
        for (i in nz.indices) nz[i] = rnd.nextFloat()

        // poświata (radialna)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(
            64f, 64f, 64f,
            intArrayOf(hex(0xFFFFFFFFL), hex(0xB0FFFFFFL), hex(0x50FFFFFFL), hex(0x14FFFFFFL), hex(0x00FFFFFFL)),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(glow).drawCircle(64f, 64f, 64f, gp)

        // promienie słońca (kliny z zanikiem)
        val rp = Paint(Paint.ANTI_ALIAS_FLAG)
        rp.shader = RadialGradient(
            128f, 128f, 128f, hex(0x70FFFFFFL), hex(0x00FFFFFFL), Shader.TileMode.CLAMP
        )
        val rc = Canvas(rays)
        val path = Path()
        val rr = Random(21)
        for (i in 0 until 28) {
            val ang = rr.nextFloat() * 6.2831855f
            val w = 0.012f + rr.nextFloat() * 0.05f
            path.rewind()
            path.moveTo(128f, 128f)
            path.lineTo(128f + 260f * Math.cos((ang - w).toDouble()).toFloat(), 128f + 260f * Math.sin((ang - w).toDouble()).toFloat())
            path.lineTo(128f + 260f * Math.cos((ang + w).toDouble()).toFloat(), 128f + 260f * Math.sin((ang + w).toDouble()).toFloat())
            path.close()
            rp.alpha = 120 + rr.nextInt(136)
            rc.drawPath(path, rp)
        }

        // gradienty pionowe (mgła, woda)
        val vp = Paint()
        vp.shader = LinearGradient(0f, 0f, 0f, 32f, hex(0x00FFFFFFL), hex(0xFFFFFFFFL), Shader.TileMode.CLAMP)
        Canvas(vGrad).drawRect(0f, 0f, 4f, 32f, vp)
        vp.shader = LinearGradient(0f, 0f, 0f, 32f, hex(0x3CFFFFFFL), hex(0xC8FFFFFFL), Shader.TileMode.CLAMP)
        Canvas(wGrad).drawRect(0f, 0f, 4f, 32f, vp)

        // sprite'y chmur
        val cp = Paint(Paint.ANTI_ALIAS_FLAG)
        cp.shader = RadialGradient(
            0f, 0f, 1f, intArrayOf(hex(0xE6FFFFFFL), hex(0xAAFFFFFFL), hex(0x00FFFFFFL)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        for (k in 0 until 3) {
            val b = Bitmap.createBitmap(256, 96, Bitmap.Config.ALPHA_8)
            val cv = Canvas(b)
            val cr = Random(100L + k)
            val n = 16 + k * 3
            for (i in 0 until n) {
                val u = (i + 0.5f) / n
                val cx = 20f + 216f * u + (cr.nextFloat() - 0.5f) * 14f
                val bump = sin(PI * u).toFloat()
                val rx = 22f + 30f * bump * (0.5f + cr.nextFloat() * 0.7f)
                val ry = rx * (0.42f + cr.nextFloat() * 0.18f)
                val cy = 72f - ry * 0.7f - cr.nextFloat() * bump * 18f
                cv.save()
                cv.translate(cx, cy)
                cv.scale(rx, ry)
                cv.drawCircle(0f, 0f, 1f, cp)
                cv.restore()
            }
            clouds[k] = b
        }

        // ziarno filmowe (czarne/białe punkty o losowej przezroczystości)
        val gpx = IntArray(256 * 256)
        val gr = Random(9)
        for (i in gpx.indices) {
            val c = if (gr.nextBoolean()) 255 else 0
            gpx[i] = (gr.nextInt(26) shl 24) or (c shl 16) or (c shl 8) or c
        }
        val grain = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        grain.setPixels(gpx, 0, 256, 0, 0, 256, 256)
        grainSh = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        pGrain.shader = grainSh

        // losowe elementy
        val er = Random(33)
        for (i in 0 until NS) {
            stX[i] = er.nextFloat(); stY[i] = Math.pow(er.nextDouble(), 1.4).toFloat()
            stSz[i] = 0.7f + er.nextFloat() * 1.7f; stPh[i] = er.nextFloat() * 6.28f
            stSp[i] = 0.6f + er.nextFloat() * 2.2f; stB[i] = 0.35f + er.nextFloat() * 0.65f
        }
        for (i in 0 until NG) {
            gX[i] = er.nextFloat() * 2f - 1f; gY[i] = er.nextFloat(); gW[i] = er.nextFloat()
            gP[i] = er.nextFloat() * 6.28f; gS[i] = 0.8f + er.nextFloat() * 2.4f
        }
        for (i in 0 until NR) {
            rX[i] = er.nextFloat(); rY[i] = er.nextFloat(); rL[i] = 0.4f + er.nextFloat()
            rP[i] = er.nextFloat() * 6.28f; rS[i] = 0.3f + er.nextFloat() * 0.8f
        }
        for (i in 0 until NC) {
            cX[i] = er.nextFloat(); cY[i] = 0.06f + er.nextFloat() * 0.36f
            cSc[i] = 0.22f + er.nextFloat() * 0.30f; cSp[i] = 0.004f + er.nextFloat() * 0.010f
            cA[i] = 0.55f + er.nextFloat() * 0.4f; cK[i] = er.nextInt(3); cPar[i] = 0.008f + er.nextFloat() * 0.02f
        }
        for (i in 0 until NM) {
            mX[i] = er.nextFloat(); mL[i] = i % 3; mSp[i] = 0.004f + er.nextFloat() * 0.008f
            mW[i] = 0.45f + er.nextFloat() * 0.4f; mY[i] = 0.10f + er.nextFloat() * 0.4f
        }
        for (i in 0 until NB) {
            bX[i] = er.nextFloat(); bY[i] = 0.22f + er.nextFloat() * 0.22f; bSp[i] = 0.012f + er.nextFloat() * 0.018f
            bSz[i] = 0.008f + er.nextFloat() * 0.008f; bPh[i] = er.nextFloat() * 6.28f
        }
        for (i in 0 until NF) {
            fX[i] = er.nextFloat(); fY[i] = 0.64f + er.nextFloat() * 0.33f; fSx[i] = 0.2f + er.nextFloat() * 0.5f
            fSy[i] = 0.2f + er.nextFloat() * 0.5f; fPh[i] = er.nextFloat() * 6.28f; fSp[i] = 0.8f + er.nextFloat() * 1.6f
            fSz[i] = 0.007f + er.nextFloat() * 0.008f
        }
    }

    // ============================================================ sterowanie

    fun start() {
        if (running) return
        running = true
        lastNs = 0L
        invalidate()
    }

    fun stop() {
        running = false
    }

    /** Rysuje scenę w małym bitmapie (np. 128x72) - baza do rozmytego tła szkła. Bez ziarna i winiety. */
    fun snapshotTo(bmp: Bitmap, cv: Canvas) {
        if (envAcc > 1000f || t == 0f) updateEnv()
        snap = true
        cv.save()
        cv.scale(bmp.width / W, bmp.height / H)
        drawScene(cv)
        cv.restore()
        snap = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        W = w.toFloat()
        H = h.toFloat()
        y0 = H * 0.70f
        buildRidges()
        vigSh = RadialGradient(
            W / 2f, H / 2f, hypot(W, H) / 2f * 1.02f,
            intArrayOf(0x00000000, 0x00000000, hex(0x80000000L)), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        pVig.shader = vigSh
        envAcc = 99f
    }

    // ============================================================ geometria

    private fun noise(x: Float): Float {
        val i = Math.floor(x.toDouble()).toInt()
        val f = x - i
        val a = nz[i and 511]
        val b = nz[(i + 1) and 511]
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    private fun buildRidges() {
        val n = 200
        val x0 = -0.08f * W
        val x1 = 1.08f * W
        for (l in 0 until layers) {
            val p = ridge[l]
            p.rewind()
            p.moveTo(x0, y0 + 2f)
            for (i in 0..n) {
                val u = i / n.toFloat()
                var f = 0f
                var a = 0.55f
                var fr = freqK[l]
                for (o in 0 until 4) {
                    f += a * noise(u * fr + l * 37.7f + o * 11.3f)
                    a *= 0.5f
                    fr *= 2.1f
                }
                val env = 0.75f + 0.25f * sin(u * 6.28f * (0.7f + 0.2f * l) + l * 1.9f)
                val h = hMaxK[l] * H * (0.25f + 0.9f * f) * env
                p.lineTo(x0 + (x1 - x0) * u, y0 - h)
            }
            p.lineTo(x1, y0 + 2f)
            p.close()
        }
    }

    // ============================================================ kolory

    private fun lc(a: Int, b: Int, f0: Float): Int {
        val f = if (f0 < 0f) 0f else if (f0 > 1f) 1f else f0
        val aa = (a ushr 24) + (((b ushr 24) - (a ushr 24)) * f).toInt()
        val r = ((a shr 16) and 255) + ((((b shr 16) and 255) - ((a shr 16) and 255)) * f).toInt()
        val g = ((a shr 8) and 255) + ((((b shr 8) and 255) - ((a shr 8) and 255)) * f).toInt()
        val bl = (a and 255) + (((b and 255) - (a and 255)) * f).toInt()
        return (aa shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun pal(a: IntArray, e: Float): Int {
        if (e <= ES[0]) return a[0]
        for (i in 1 until ES.size) {
            if (e <= ES[i]) return lc(a[i - 1], a[i], (e - ES[i - 1]) / (ES[i] - ES[i - 1]))
        }
        return a[a.size - 1]
    }

    private fun cl(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun setCA(p: Paint, col: Int, a: Int) {
        p.color = (col and 0x00FFFFFF) or ((if (a < 0) 0 else if (a > 255) 255 else a) shl 24)
    }

    // ============================================================ środowisko (co 0,5 s)

    private fun updateEnv() {
        val hour: Float
        if (hourOverride >= 0f) {
            hour = hourOverride
        } else {
            cal.timeInMillis = System.currentTimeMillis()
            hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f + cal.get(Calendar.SECOND) / 3600f
        }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val dst = if (cal.get(Calendar.DST_OFFSET) > 0) 1f else 0f
        val dayLen = 12f + 4.2f * sin(2.0 * PI * (doy - 80) / 365.0).toFloat()
        val noon = 11.8f + dst
        val rise = noon - dayLen / 2f
        val set = noon + dayLen / 2f
        val e: Float
        var moonVis = 0f
        if (hour >= rise && hour <= set) {
            val x = (hour - rise) / dayLen
            val s = sin(PI * x).toFloat()
            e = 0.95f * s
            sunX = W * (0.12f + 0.76f * x)
            sunY = y0 - s * 0.50f * H + 0.012f * H
            sunAmt = cl((e + 0.06f) / 0.10f)
        } else {
            val hh = if (hour >= set) hour - set else hour + 24f - set
            val nl = 24f - dayLen
            val x = hh / nl
            val s = sin(PI * x).toFloat()
            e = -0.25f * s
            moonX = W * (0.12f + 0.76f * x)
            moonY = y0 - s * 0.46f * H
            moonVis = min(1f, s * 5f)
            sunAmt = 0f
            sunY = y0 + 0.2f * H
        }
        eLev = e
        cTop = pal(TOP, e); cMid = pal(MID, e); cHor = pal(HOR, e)
        sunCol = pal(SUNC, e)
        cloudLit = pal(CLOUD, e)
        cloudShade = lc(cloudLit, cMid, 0.55f)
        val near = pal(NEAR, e)
        for (l in 0 until layers) layerCol[l] = lc(cHor, near, mixK[l])
        waterCol = lc(cMid, near, 0.6f)
        nightAmt = cl(-e / 0.12f)
        moonAmt = nightAmt * moonVis
        rayAmt = sunAmt * Math.max(0.18f, cl((0.55f - e) / 0.5f))
        if (sunAmt >= moonAmt) {
            lightX = sunX; lightAmt = sunAmt; lightCol = lc(sunCol, -1, 0.5f)
        } else {
            lightX = moonX; lightAmt = moonAmt * 0.55f; lightCol = hex(0xFFC8D6FFL)
        }
        flyAmt = cl((0.14f - e) / 0.2f)
        birdAmt = cl((e - 0.02f) / 0.12f)

        for (j in 0 until 64) {
            val f = j / 63f
            val c = if (f < 0.55f) {
                val q = f / 0.55f
                lc(cTop, cMid, q * q * (3f - 2f * q))
            } else {
                val q = (f - 0.55f) / 0.45f
                lc(cMid, cHor, q * q * (3f - 2f * q))
            }
            skyPx[j * 2] = c
            skyPx[j * 2 + 1] = c
        }
        sky.setPixels(skyPx, 0, 2, 0, 0, 2, 64)
    }

    // ============================================================ rysowanie

    override fun onDraw(canvas: Canvas) {
        val ns = SystemClock.elapsedRealtimeNanos()
        var dt = if (lastNs == 0L) 0.016f else (ns - lastNs) / 1e9f
        lastNs = ns
        if (dt > 0.1f) dt = 0.016f
        t += dt
        par += (parallaxTarget - par) * min(1f, dt * 2.0f)
        if (dt > 0.026f) bad++ else if (bad > 0) bad--
        if (bad > 150 && quality > 0) {
            quality--
            bad = 0
        }
        envAcc += dt
        if (envAcc > 0.5f) {
            envAcc = 0f
            updateEnv()
        }
        drawScene(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun glowAt(c: Canvas, cx: Float, cy: Float, sw: Float, sh: Float, col: Int, a: Int) {
        if (a <= 0) return
        setCA(pAdd, col, a)
        rf.set(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f)
        c.drawBitmap(glow, null, rf, pAdd)
    }

    private fun drawScene(c: Canvas) {
        rf.set(0f, 0f, W, y0 + 1f)
        c.drawBitmap(sky, null, rf, pSky)
        if (nightAmt > 0.02f) drawStars(c)
        if (moonAmt > 0.02f) drawMoon(c)
        if (sunAmt > 0.02f) drawSun(c)
        drawClouds(c)
        drawMountains(c)
        if (quality >= 1 && rayAmt > 0.02f) drawRays(c)
        if (birdAmt > 0.02f) drawBirds(c)
        drawWater(c)
        if (flyAmt > 0.02f) drawFireflies(c)
        if (!snap) {
            if (quality >= 1) {
                grainFrame++
                if ((grainFrame and 1) == 0) {
                    grainM.setTranslate(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat())
                    grainSh.setLocalMatrix(grainM)
                    pGrain.shader = grainSh
                }
                c.drawRect(0f, 0f, W, H, pGrain)
            }
            c.drawRect(0f, 0f, W, H, pVig)
        }
    }

    private fun drawStars(c: Canvas) {
        val k = W / 1920f
        pStar.color = -1
        for (i in 0 until NS) {
            val tw = 0.55f + 0.45f * sin(t * stSp[i] + stPh[i])
            val a = (nightAmt * tw * stB[i] * 255f).toInt()
            pStar.alpha = if (a > 255) 255 else if (a < 0) 0 else a
            pStar.strokeWidth = stSz[i] * k
            c.drawPoint(stX[i] * W - par * W * 0.002f, stY[i] * y0 * 0.92f, pStar)
        }
    }

    private fun drawMoon(c: Canvas) {
        val r = W * 0.013f
        glowAt(c, moonX, moonY, W * 0.28f, W * 0.28f, hex(0xFFAFC4FFL), (95 * moonAmt).toInt())
        setCA(pFill, hex(0xFFF3F5FFL), (255 * moonAmt).toInt())
        c.drawCircle(moonX, moonY, r, pFill)
        setCA(pFill, cMid, (255 * moonAmt).toInt())
        c.drawCircle(moonX + r * 0.42f, moonY - r * 0.12f, r * 0.92f, pFill)
    }

    private fun drawSun(c: Canvas) {
        val a = sunAmt
        glowAt(c, sunX, sunY, W * 1.0f, W * 1.0f, sunCol, (150 * a).toInt())
        glowAt(c, sunX, sunY, W * 0.30f, W * 0.30f, lc(sunCol, -1, 0.45f), (230 * a).toInt())
        val low = cl(1f - eLev / 0.4f)
        if (low > 0.02f) {
            setCA(pAdd, sunCol, (170 * low * a).toInt())
            rf.set(sunX - W * 0.65f, y0 - H * 0.13f, sunX + W * 0.65f, y0 + H * 0.07f)
            c.drawBitmap(glow, null, rf, pAdd)
        }
        setCA(pFill, lc(sunCol, -1, 0.85f), (255 * a).toInt())
        c.drawCircle(sunX, sunY, W * 0.0165f, pFill)
        glowAt(c, sunX, sunY, W * 0.075f, W * 0.075f, -1, (200 * a).toInt())
    }

    private fun drawClouds(c: Canvas) {
        val span = W * 1.6f
        val dim = 1f - 0.35f * nightAmt
        for (i in 0 until NC) {
            val spr = clouds[cK[i]] ?: continue
            val w = cSc[i] * W
            val h = w * 96f / 256f
            val x = (cX[i] * span + t * cSp[i] * W) % span - 0.3f * W - par * W * cPar[i]
            val y = cY[i] * y0
            val al = cA[i] * dim
            rf.set(x, y + h * 0.07f, x + w, y + h * 1.07f)
            setCA(pTint, cloudShade, (al * 200f).toInt())
            c.drawBitmap(spr, null, rf, pTint)
            rf.set(x, y, x + w, y + h)
            setCA(pTint, cloudLit, (al * 215f).toInt())
            c.drawBitmap(spr, null, rf, pTint)
        }
    }

    private fun drawMist(c: Canvas, layer: Int) {
        if (quality < 1) return
        val span = W * 1.6f
        for (i in 0 until NM) {
            if (mL[i] != layer) continue
            val w = mW[i] * W
            val x = (mX[i] * span + t * mSp[i] * W) % span - 0.3f * W
            val y = y0 - mY[i] * hMaxK[layer] * H
            setCA(pAdd, cHor, (46 * (0.4f + 0.6f * cl(eLev * 3f + 0.4f))).toInt())
            rf.set(x, y - H * 0.03f, x + w, y + H * 0.03f)
            c.drawBitmap(glow, null, rf, pAdd)
        }
    }

    private fun drawMountains(c: Canvas) {
        for (l in 0 until layers) {
            val off = -par * W * parK[l] + sin(t * 0.05f + l) * W * 0.004f * (l + 1)
            c.save()
            c.translate(off, 0f)
            pFill.color = layerCol[l]
            c.drawPath(ridge[l], pFill)
            c.restore()
            fogRect.set(0f, y0 - fogH[l] * H, W, y0 + 2f)
            setCA(pTint, cHor, (fogK[l] * 255f).toInt())
            c.drawBitmap(vGrad, null, fogRect, pTint)
            if (l < 3) drawMist(c, l)
        }
    }

    private fun drawRays(c: Canvas) {
        val sz = W * 1.9f
        setCA(pAdd, sunCol, (rayAmt * 125f).toInt())
        c.save()
        c.rotate(sin(t * 0.07f) * 4f, sunX, sunY)
        rf.set(sunX - sz / 2f, sunY - sz / 2f, sunX + sz / 2f, sunY + sz / 2f)
        c.drawBitmap(rays, null, rf, pAdd)
        c.restore()
    }

    private fun drawBirds(c: Canvas) {
        val span = W * 1.3f
        pLine.color = lc(layerCol[3], hex(0xFF000000L), 0.3f)
        for (i in 0 until NB) {
            val s = bSz[i] * W
            val x = (bX[i] * span + t * bSp[i] * W) % span - 0.15f * W - par * W * 0.01f
            val y = bY[i] * y0 + sin(t * 0.3f + bPh[i]) * H * 0.01f
            val wing = sin(t * (4.2f + bPh[i] * 0.2f) + bPh[i])
            birdPts[0] = x; birdPts[1] = y
            birdPts[2] = x - s * 0.5f; birdPts[3] = y - s * 0.28f * (0.5f + wing * 0.5f)
            birdPts[4] = x - s * 0.5f; birdPts[5] = y - s * 0.28f * (0.5f + wing * 0.5f)
            birdPts[6] = x - s; birdPts[7] = y + s * 0.18f * wing
            birdPts[8] = x; birdPts[9] = y
            birdPts[10] = x + s * 0.5f; birdPts[11] = y - s * 0.28f * (0.5f + wing * 0.5f)
            birdPts[12] = x + s * 0.5f; birdPts[13] = y - s * 0.28f * (0.5f + wing * 0.5f)
            birdPts[14] = x + s; birdPts[15] = y + s * 0.18f * wing
            pLine.alpha = (birdAmt * 215f).toInt()
            pLine.strokeWidth = Math.max(1.5f, s * 0.11f)
            c.drawLines(birdPts, pLine)
        }
    }

    private fun drawWater(c: Canvas) {
        val wh = H - y0
        c.save()
        c.clipRect(0f, y0, W, H)

        // odbite niebo
        c.save()
        c.scale(1f, -1f, 0f, y0)
        rf.set(0f, 0f, W, y0)
        c.drawBitmap(sky, null, rf, pSky)
        c.restore()

        // odbite góry (paski z falowaniem)
        if (quality >= 1) {
            val ns = if (quality >= 2) 8 else 4
            val first = if (quality >= 2) 1 else 2
            val sh = wh / ns
            for (i in 0 until ns) {
                val ya = y0 + i * sh
                val dx = sin(t * 0.9f + i * 1.7f) * W * 0.0018f * (1f + i * 0.35f)
                c.save()
                c.clipRect(0f, ya, W, ya + sh + 1f)
                c.scale(1f, -1f, 0f, y0)
                c.translate(dx, 0f)
                for (l in first until layers) {
                    val off = -par * W * parK[l] + sin(t * 0.05f + l) * W * 0.004f * (l + 1)
                    c.translate(off, 0f)
                    setCA(pFill, layerCol[l], (215f * (1f - 0.8f * i / ns)).toInt())
                    c.drawPath(ridge[l], pFill)
                    c.translate(-off, 0f)
                }
                c.restore()
            }
        }

        // przyciemnienie wody (głębia)
        rf.set(0f, y0, W, H)
        setCA(pTint, waterCol, 255)
        c.drawBitmap(wGrad, null, rf, pTint)

        val k = W / 1920f
        // delikatne zmarszczki na całej tafli
        if (quality >= 1) {
            pLine.color = lc(cHor, -1, 0.35f)
            for (i in 0 until NR) {
                val d = rY[i]
                val y = y0 + 0.012f * H + d * d * (wh - 0.02f * H)
                val x = rX[i] * W + sin(t * rS[i] + rP[i]) * W * 0.01f
                val len = (0.01f + 0.06f * d) * W * rL[i]
                val a = (0.5f + 0.5f * sin(t * rS[i] * 1.7f + rP[i])) * 0.12f * (0.3f + eLev.coerceIn(0f, 1f) * 0.7f + nightAmt * 0.3f)
                pLine.alpha = (a * 255f).toInt()
                pLine.strokeWidth = (1.2f + 2.2f * d) * k
                c.drawLine(x, y, x + len, y, pLine)
            }
        }

        // połysk słońca / księżyca na wodzie
        if (lightAmt > 0.03f) {
            pLine.color = lightCol
            val n = if (quality >= 2) NG else NG / 2
            for (i in 0 until n) {
                val d = gY[i]
                val y = y0 + 0.02f * H + Math.pow(d.toDouble(), 1.6).toFloat() * (wh - 0.04f * H)
                val spread = (0.02f + 0.16f * d) * W
                val x = lightX - par * W * 0.012f + gX[i] * spread + sin(t * 0.4f + gP[i]) * W * 0.003f
                val sp = sin(t * gS[i] + gP[i])
                val sparkle = if (sp > 0f) sp * sp * sp else 0f
                val len = (0.006f + 0.05f * d) * W * (0.5f + gW[i])
                pLine.alpha = (lightAmt * (0.25f + 0.75f * sparkle) * 255f).toInt()
                pLine.strokeWidth = (1.3f + 3.2f * d) * k
                c.drawLine(x - len / 2f, y, x + len / 2f, y, pLine)
            }
            // słup światła pod słońcem
            glowAt(c, lightX - par * W * 0.012f, y0 + wh * 0.35f, W * 0.10f, wh * 1.5f, lightCol, (80 * lightAmt).toInt())
        }

        // linia horyzontu
        setCA(pFill, lc(cHor, -1, 0.45f), 150)
        c.drawRect(0f, y0 - 1f, W, y0 + 1.5f, pFill)
        c.restore()
    }

    private fun drawFireflies(c: Canvas) {
        val col = hex(0xFFE6F27AL)
        for (i in 0 until NF) {
            val x = fX[i] * W + sin(t * fSx[i] + fPh[i]) * W * 0.02f - par * W * 0.03f
            val y = fY[i] * H + Math.cos((t * fSy[i] + fPh[i]).toDouble()).toFloat() * H * 0.02f
            val s = sin(t * fSp[i] + fPh[i]) * 0.5f + 0.5f
            val a = s * s * flyAmt
            val sz = fSz[i] * W
            glowAt(c, x, y, sz * 5f, sz * 5f, col, (a * 190f).toInt())
            glowAt(c, x, y, sz * 1.4f, sz * 1.4f, -1, (a * 255f).toInt())
        }
    }
}
