package pl.frutiger.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import java.util.Random

/**
 * Wbudowane tapety: miękkie, świecące gradienty (aurora) w stylu iOS 26.
 * Rysowane kodem w małej rozdzielczości, rozmywane i skalowane w górę (gładkie przejścia),
 * na końcu delikatne ziarno przeciw pasmom na dużym ekranie.
 */
object Scenes {

    const val COUNT = 5

    private class Blob(val x: Float, val y: Float, val r: Float, val color: Long, val a: Int)

    private class Ribbon(
        val y: Float, val amp: Float, val freq: Float, val phase: Float,
        val thick: Float, val c1: Long, val c2: Long, val a: Int
    )

    private class Spec(val bg0: Long, val bg1: Long, val blobs: List<Blob>, val ribbons: List<Ribbon>)

    private val specs: List<Spec> = listOf(
        // 0 Aurora
        Spec(
            0xFF050B2EL, 0xFF1A0F5CL,
            listOf(
                Blob(0.15f, 0.85f, 0.55f, 0xFF00D4FFL, 200),
                Blob(0.85f, 0.15f, 0.50f, 0xFF8A2BFFL, 220),
                Blob(0.55f, 0.55f, 0.35f, 0xFFFF3D9AL, 140),
                Blob(0.95f, 0.95f, 0.45f, 0xFF00FFC8L, 150)
            ),
            listOf(
                Ribbon(0.62f, 0.16f, 1.0f, 0.4f, 0.30f, 0xFF00E5FFL, 0xFFB44DFFL, 150),
                Ribbon(0.35f, 0.12f, 1.4f, 2.1f, 0.22f, 0xFF7C4DFFL, 0xFFFF4DA6L, 130)
            )
        ),
        // 1 Zachód słońca
        Spec(
            0xFF1B0B33L, 0xFF4A1250L,
            listOf(
                Blob(0.20f, 0.20f, 0.50f, 0xFFFF7A18L, 210),
                Blob(0.80f, 0.80f, 0.55f, 0xFFFF2E63L, 210),
                Blob(0.50f, 0.50f, 0.30f, 0xFFFFC857L, 120),
                Blob(0.95f, 0.10f, 0.40f, 0xFF6A3DE8L, 200)
            ),
            listOf(
                Ribbon(0.55f, 0.15f, 1.1f, 1.0f, 0.30f, 0xFFFFA24DL, 0xFFFF3D7FL, 150),
                Ribbon(0.80f, 0.10f, 1.6f, 3.0f, 0.20f, 0xFF8A4DFFL, 0xFFFF7A18L, 120)
            )
        ),
        // 2 Ocean
        Spec(
            0xFF021426L, 0xFF053B58L,
            listOf(
                Blob(0.10f, 0.50f, 0.50f, 0xFF00E5FFL, 190),
                Blob(0.90f, 0.30f, 0.50f, 0xFF1E6BFFL, 210),
                Blob(0.60f, 0.95f, 0.50f, 0xFF00FFA3L, 150),
                Blob(0.40f, 0.05f, 0.30f, 0xFF7C4DFFL, 140)
            ),
            listOf(
                Ribbon(0.50f, 0.18f, 0.9f, 0.2f, 0.32f, 0xFF00FFD5L, 0xFF2979FFL, 140),
                Ribbon(0.30f, 0.10f, 1.5f, 1.8f, 0.20f, 0xFF18FFFFL, 0xFF7C4DFFL, 110)
            )
        ),
        // 3 Grafit
        Spec(
            0xFF0B0C12L, 0xFF1B1D2BL,
            listOf(
                Blob(0.20f, 0.30f, 0.50f, 0xFF3A4BFFL, 150),
                Blob(0.80f, 0.70f, 0.50f, 0xFFB14DFFL, 150),
                Blob(0.60f, 0.15f, 0.30f, 0xFFFF6AA2L, 110)
            ),
            listOf(
                Ribbon(0.60f, 0.14f, 1.0f, 0.7f, 0.28f, 0xFF4D6BFFL, 0xFFD14DFFL, 110)
            )
        ),
        // 4 Szmaragd
        Spec(
            0xFF04140FL, 0xFF0A3B2BL,
            listOf(
                Blob(0.15f, 0.70f, 0.50f, 0xFF00E676L, 170),
                Blob(0.85f, 0.25f, 0.50f, 0xFF00B8D4L, 190),
                Blob(0.60f, 0.90f, 0.40f, 0xFFB2FF59L, 110)
            ),
            listOf(
                Ribbon(0.55f, 0.16f, 1.1f, 1.4f, 0.30f, 0xFF1DE9B6L, 0xFF00B0FFL, 140),
                Ribbon(0.30f, 0.10f, 1.7f, 0.3f, 0.18f, 0xFF76FF03L, 0xFF00E5FFL, 100)
            )
        )
    )

    private fun withAlpha(color: Long, a: Int): Int =
        (color and 0x00FFFFFFL).toInt() or (a shl 24)

    fun render(index: Int, w: Int, h: Int): Bitmap {
        val spec = specs[index % specs.size]
        val lw = 320
        val lh = 180
        val low = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(low)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // tło
        p.shader = LinearGradient(
            0f, 0f, lw.toFloat(), lh.toFloat(),
            spec.bg0.toInt(), spec.bg1.toInt(), Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), p)
        p.shader = null

        // świetliste plamy
        for (b in spec.blobs) {
            val cx = b.x * lw
            val cy = b.y * lh
            val r = b.r * lw
            p.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(b.color, b.a), withAlpha(b.color, 0)),
                null, Shader.TileMode.CLAMP
            )
            cv.drawCircle(cx, cy, r, p)
        }
        p.shader = null

        // faliste wstęgi światła
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (rb in spec.ribbons) {
            val path = Path()
            var x = -10
            var first = true
            while (x <= lw + 10) {
                val t = x.toDouble() / lw
                val wave = rb.amp * Math.sin(t * rb.freq * 2.0 * Math.PI + rb.phase) +
                        rb.amp * 0.4 * Math.sin(t * rb.freq * 4.6 * Math.PI + rb.phase * 1.7)
                val y = ((rb.y + wave) * lh).toFloat()
                if (first) {
                    path.moveTo(x.toFloat(), y)
                    first = false
                } else {
                    path.lineTo(x.toFloat(), y)
                }
                x += 4
            }
            p.strokeWidth = rb.thick * lh
            p.shader = LinearGradient(
                0f, 0f, lw.toFloat(), 0f,
                withAlpha(rb.c1, rb.a), withAlpha(rb.c2, rb.a), Shader.TileMode.CLAMP
            )
            cv.drawPath(path, p)
        }
        p.shader = null
        p.style = Paint.Style.FILL

        // rozmycie (jedwabiste przejścia)
        val px = IntArray(lw * lh)
        low.getPixels(px, 0, lw, 0, 0, lw, lh)
        Blur.boxBlur(px, lw, lh, 3, 2)
        low.setPixels(px, 0, lw, 0, 0, lw, lh)

        // winieta
        p.shader = RadialGradient(
            lw / 2f, lh / 2f, Math.hypot(lw.toDouble(), lh.toDouble()).toFloat() / 2f * 1.05f,
            intArrayOf(0x00000000, 0x00000000, 0x73000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), p)
        p.shader = null

        // skalowanie w górę do docelowego rozmiaru
        val big = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bigCanvas = Canvas(big)
        val up = Paint(Paint.FILTER_BITMAP_FLAG)
        bigCanvas.drawBitmap(low, Rect(0, 0, lw, lh), Rect(0, 0, w, h), up)

        // delikatne ziarno przeciw pasmom
        val all = IntArray(w * h)
        big.getPixels(all, 0, w, 0, 0, w, h)
        val rnd = Random(77L + index)
        for (i in all.indices) {
            val n = rnd.nextInt(7) - 3
            val c = all[i]
            var r = ((c shr 16) and 255) + n
            var g = ((c shr 8) and 255) + n
            var b = (c and 255) + n
            if (r < 0) r = 0 else if (r > 255) r = 255
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (b < 0) b = 0 else if (b > 255) b = 255
            all[i] = (c and 0xFF000000L.toInt()) or (r shl 16) or (g shl 8) or b
        }
        big.setPixels(all, 0, w, 0, 0, w, h)
        return big
    }
}
