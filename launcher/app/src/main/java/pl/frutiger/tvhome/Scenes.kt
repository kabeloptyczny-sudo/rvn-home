package pl.frutiger.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random

/** Wbudowane tapety w stylu Frutiger Aero, rysowane kodem (bez internetu, bez plików). */
object Scenes {

    private fun c(v: Long): Int = v.toInt()

    fun render(index: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(1000L + index * 7919L)
        when (index % 3) {
            0 -> meadow(cv, p, w, h, rnd)
            1 -> ocean(cv, p, w, h, rnd)
            else -> sunset(cv, p, w, h, rnd)
        }
        bubbles(cv, p, w, h, rnd)
        return bmp
    }

    private fun sky(cv: Canvas, p: Paint, w: Int, h: Int, top: Int, bottom: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, top, bottom, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
    }

    private fun glow(cv: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        p.shader = RadialGradient(
            cx, cy, r, intArrayOf(c(0xDDFFFFFF), c(0x00FFFFFF)), null, Shader.TileMode.CLAMP
        )
        cv.drawCircle(cx, cy, r, p)
        p.shader = null
    }

    private fun clouds(cv: Canvas, p: Paint, w: Int, h: Int, rnd: Random, count: Int) {
        p.style = Paint.Style.FILL
        p.color = c(0x66FFFFFF)
        repeat(count) {
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h * 0.35f
            val s = 80f + rnd.nextFloat() * 120f
            for (k in 0..4) {
                val dy = if (k % 2 == 0) 0f else -s * 0.15f
                cv.drawCircle(cx + k * s * 0.45f, cy + dy, s * (0.35f + 0.1f * (k % 3)), p)
            }
        }
    }

    private fun hill(
        cv: Canvas, p: Paint, w: Int, h: Int,
        baseY: Float, amp: Float, phase: Double, top: Int, bottom: Int
    ) {
        val path = Path()
        path.moveTo(0f, h.toFloat())
        var x = 0
        while (x <= w) {
            val y = (baseY + Math.sin(x.toDouble() / w * Math.PI * 2 + phase) * amp).toFloat()
            if (x == 0) path.lineTo(0f, y) else path.lineTo(x.toFloat(), y)
            x += 20
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, baseY - amp, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null
    }

    private fun meadow(cv: Canvas, p: Paint, w: Int, h: Int, rnd: Random) {
        sky(cv, p, w, h, c(0xFF1E88E5), c(0xFFB3E5FC))
        glow(cv, p, w * 0.78f, h * 0.22f, h * 0.45f)
        clouds(cv, p, w, h, rnd, 5)
        hill(cv, p, w, h, h * 0.66f, h * 0.05f, 0.5, c(0xFF8BC34A), c(0xFF2E7D32))
        hill(cv, p, w, h, h * 0.78f, h * 0.04f, 2.2, c(0xFF66BB6A), c(0xFF1B5E20))
    }

    private fun ocean(cv: Canvas, p: Paint, w: Int, h: Int, rnd: Random) {
        sky(cv, p, w, h, c(0xFF00ACC1), c(0xFFE0F7FA))
        glow(cv, p, w * 0.3f, h * 0.2f, h * 0.5f)
        clouds(cv, p, w, h, rnd, 3)
        hill(cv, p, w, h, h * 0.58f, h * 0.015f, 0.0, c(0xFF4DD0E1), c(0xFF006064))
        hill(cv, p, w, h, h * 0.68f, h * 0.025f, 1.3, c(0xAA26C6DA), c(0xFF00838F))
        hill(cv, p, w, h, h * 0.80f, h * 0.03f, 3.1, c(0x9900ACC1), c(0xFF004D5A))
    }

    private fun sunset(cv: Canvas, p: Paint, w: Int, h: Int, rnd: Random) {
        sky(cv, p, w, h, c(0xFF5C6BC0), c(0xFFFFCC80))
        glow(cv, p, w * 0.5f, h * 0.62f, h * 0.6f)
        clouds(cv, p, w, h, rnd, 4)
        hill(cv, p, w, h, h * 0.70f, h * 0.05f, 1.0, c(0xFF558B2F), c(0xFF1B3A1B))
        hill(cv, p, w, h, h * 0.82f, h * 0.04f, 4.0, c(0xFF33691E), c(0xFF102A10))
    }

    private fun bubbles(cv: Canvas, p: Paint, w: Int, h: Int, rnd: Random) {
        repeat(16) {
            val r = 18f + rnd.nextFloat() * 70f
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(
                cx - r * 0.3f, cy - r * 0.3f, r * 1.2f,
                intArrayOf(c(0x77FFFFFF), c(0x14BFEFFF), c(0x33FFFFFF)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
            cv.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = c(0x88FFFFFF)
            cv.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL
            p.color = c(0xAAFFFFFF)
            cv.drawOval(RectF(cx - r * 0.55f, cy - r * 0.65f, cx - r * 0.1f, cy - r * 0.35f), p)
        }
    }
}
