package pl.frutiger.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/** Okrągły, fokusowalny przycisk z ikoną rysowaną kodem; podświetlenie sprężyste. */
class IconButton(ctx: Context, private val kind: Int, private val onClick: () -> Unit) : View(ctx), Tick {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val spring = Spring(420f, 0.55f)

    init {
        isFocusable = true
        isClickable = true
        setDefaultFocusHighlightEnabled(false)
        p.strokeCap = Paint.Cap.ROUND
        setOnClickListener { onClick() }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, prev)
        spring.target = if (gainFocus) 1f else 0f
        Ticker.add(this)
    }

    override fun onDetachedFromWindow() {
        Ticker.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onTick(dt: Float): Boolean {
        val m = spring.step(dt)
        invalidate()
        return m
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val f = spring.x
        val rad = w / 2f * (0.78f + 0.22f * f)
        p.style = Paint.Style.FILL
        p.color = (((0.26f * f.coerceIn(0f, 1f)) * 255f).toInt() shl 24) or 0x00FFFFFF
        c.drawCircle(cx, cy, rad, p)
        p.color = hex(0xF2FFFFFFL)
        val s = w * 0.5f * (1f + 0.06f * f)
        if (kind == 0) {   // koło zębate
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.13f
            c.drawCircle(cx, cy, s * 0.27f, p)
            p.strokeWidth = s * 0.16f
            for (i in 0 until 8) {
                c.save()
                c.rotate(i * 45f, cx, cy)
                c.drawLine(cx, cy - s * 0.36f, cx, cy - s * 0.47f, p)
                c.restore()
            }
        } else {            // Wi-Fi
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.10f
            for (i in 1..3) {
                val rr = s * 0.17f * i
                r.set(cx - rr, cy + s * 0.2f - rr, cx + rr, cy + s * 0.2f + rr)
                c.drawArc(r, 225f, 90f, false, p)
            }
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy + s * 0.2f, s * 0.06f, p)
        }
    }
}

/** Szklany pasek skrótów (ustawienia, Wi-Fi) - prawy górny róg. */
class ShortcutBar(ctx: Context, u: Float, open: (Intent) -> Unit) : GlassView(ctx, u * 2.4f, hex(0x38FFFFFFL), hex(0x10FFFFFFL), true) {
    init {
        lens = false
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = (u * 0.5f).toInt()
        row.setPadding(pad, pad, pad, pad)
        val sz = (u * 3.4f).toInt()
        row.addView(IconButton(ctx, 0) { open(Intent(Settings.ACTION_SETTINGS)) }, LinearLayout.LayoutParams(sz, sz))
        row.addView(IconButton(ctx, 1) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }, LinearLayout.LayoutParams(sz, sz))
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}
