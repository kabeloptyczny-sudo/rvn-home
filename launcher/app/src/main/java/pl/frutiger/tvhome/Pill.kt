package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout

/**
 * Miękka "pigułka" podświetlenia. Płynnie przesuwa się i zmienia rozmiar między elementami
 * (220 ms, ease-out). Animatory i prostokąty powstają raz; w onDraw nic się nie alokuje.
 */
class PillView(ctx: Context, private val u: Float) : View(ctx) {

    private val cur = RectF()
    private val from = RectF()
    private val to = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var a = 0f
    private var fadeFrom = 0f
    private var fadeTo = 0f
    private var placed = false
    private val mover = ValueAnimator.ofFloat(0f, 1f)
    private val fader = ValueAnimator.ofFloat(0f, 1f)
    private val hideTask = Runnable { fade(0f) }
    private val rad = u * 1.6f

    init {
        mover.duration = Motion.MS
        mover.interpolator = Motion.EASE
        mover.addUpdateListener {
            val f = it.animatedFraction
            cur.left = from.left + (to.left - from.left) * f
            cur.top = from.top + (to.top - from.top) * f
            cur.right = from.right + (to.right - from.right) * f
            cur.bottom = from.bottom + (to.bottom - from.bottom) * f
            invalidate()
        }
        fader.addUpdateListener {
            a = fadeFrom + (fadeTo - fadeFrom) * it.animatedFraction
            invalidate()
        }
    }

    private fun fade(target: Float) {
        if (fadeTo == target && fader.isRunning) return
        if (a == target) return
        fader.cancel()
        fadeFrom = a
        fadeTo = target
        fader.duration = Motion.LABEL_MS
        fader.start()
    }

    /** Przesuwa pigułkę na nowy prostokąt (współrzędne w układzie hosta). */
    fun moveTo(l: Float, t: Float, r: Float, b: Float) {
        removeCallbacks(hideTask)
        to.set(l, t, r, b)
        if (!placed || a <= 0.02f) {
            mover.cancel()
            cur.set(to)
            placed = true
        } else {
            mover.cancel()
            from.set(cur)
            mover.start()
        }
        fade(1f)
    }

    /** Ukrywa z lekkim opóźnieniem (gdy fokus przeskakuje między kafelkami, nie mruga). */
    fun hide() {
        removeCallbacks(hideTask)
        postDelayed(hideTask, 90L)
    }

    override fun onDetachedFromWindow() {
        mover.cancel()
        fader.cancel()
        removeCallbacks(hideTask)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (a <= 0.01f || !placed) return
        var g = u * 0.40f
        p.color = ((0x0C * a).toInt() shl 24) or 0xEFE8DA
        c.drawRoundRect(cur.left - g, cur.top - g, cur.right + g, cur.bottom + g, rad + g, rad + g, p)
        g = u * 0.18f
        p.color = ((0x14 * a).toInt() shl 24) or 0xEFE8DA
        c.drawRoundRect(cur.left - g, cur.top - g, cur.right + g, cur.bottom + g, rad + g, rad + g, p)
        p.color = ((0x2C * a).toInt() shl 24) or 0xEFE8DA
        c.drawRoundRect(cur, rad, rad, p)
    }
}

/** Kontener z pigułką pod zawartością (rząd doku, siatka aplikacji). Przewija się razem z zawartością. */
class PillHost(ctx: Context, private val u: Float) : FrameLayout(ctx) {

    val pill = PillView(ctx, u)

    init {
        clipChildren = false
        clipToPadding = false
        addView(pill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun focused(tile: View) {
        if (tile.isLayoutRequested) {
            tile.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    place(v)
                }
            })
        } else place(tile)
    }

    fun blurred() {
        pill.hide()
    }

    private fun place(tile: View) {
        if (tile.width <= 0) return
        var x = 0
        var y = 0
        var v: View = tile
        while (v !== this) {
            x += v.left
            y += v.top
            v = (v.parent as? View) ?: return
        }
        val px = u * 0.5f
        val py = u * 0.45f
        pill.moveTo(x - px, y - py, x + tile.width + px, y + tile.height + py)
    }
}
