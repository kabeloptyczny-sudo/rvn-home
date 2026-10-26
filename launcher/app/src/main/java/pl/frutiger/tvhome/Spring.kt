package pl.frutiger.tvhome

import android.view.Choreographer

interface Tick {
    /** @return true = wołaj dalej w następnej klatce */
    fun onTick(dt: Float): Boolean
}

/** Jeden wspólny Choreographer dla wszystkich animacji sprężynowych (wątek UI). */
object Ticker : Choreographer.FrameCallback {
    private val list = ArrayList<Tick>()
    private var last = 0L
    private var posted = false
    private var paused = false

    /** Pauza wszystkich animacji (onPause): zdejmuje callback Choreographera. */
    fun pause() {
        paused = true
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    fun resume() {
        paused = false
        if (list.isNotEmpty() && !posted) {
            posted = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun add(t: Tick) {
        if (!list.contains(t)) list.add(t)
        if (!posted && !paused) {
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
        if (list.isEmpty() || paused) posted = false
        else Choreographer.getInstance().postFrameCallback(this)
    }
}
