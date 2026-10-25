package pl.frutiger.tvhome

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/** Sprężyna drugiego rzędu (lekki overshoot przy zeta < 1). Bez alokacji w kroku. */
class Spring(private val k: Float, zeta: Float, var x: Float = 0f) {
    var target = x
    var v = 0f
    private val c = 2f * zeta * sqrt(k)

    /** @return true, dopóki sprężyna się rusza */
    fun step(dt: Float): Boolean {
        var t = dt
        while (t > 0f) {
            val h = if (t > 1f / 240f) 1f / 240f else t
            v += (k * (target - x) - c * v) * h
            x += v * h
            t -= h
        }
        val moving = abs(target - x) > 0.0006f || abs(v) > 0.004f
        if (!moving) {
            x = target
            v = 0f
        }
        return moving
    }
}

interface Tick {
    /** @return true = wołaj dalej w następnej klatce */
    fun onTick(dt: Float): Boolean
}

/** Jeden wspólny Choreographer dla wszystkich animacji sprężynowych (wątek UI). */
object Ticker : Choreographer.FrameCallback {
    private val list = ArrayList<Tick>()
    private var last = 0L
    private var posted = false

    fun add(t: Tick) {
        if (!list.contains(t)) list.add(t)
        if (!posted) {
            posted = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun remove(t: Tick) {
        list.remove(t)
    }

    override fun doFrame(frameTimeNanos: Long) {
        var dt = if (last == 0L) 1f / 60f else (frameTimeNanos - last) / 1e9f
        if (dt > 0.05f) dt = 0.05f
        last = frameTimeNanos
        var i = 0
        while (i < list.size) {
            val t = list[i]
            if (t.onTick(dt)) i++ else list.remove(t)
        }
        if (list.isEmpty()) posted = false
        else Choreographer.getInstance().postFrameCallback(this)
    }
}
