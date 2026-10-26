package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

/**
 * Tło wideo: pętla plików .mp4 w losowej kolejności, bez dźwięku, z crossfadem
 * (dwa TextureView + dwa MediaPlayer). Klatki do rozmycia szkła: snapshotTo().
 */
class VideoBg(context: Context, private val files: List<File>) : FrameLayout(context) {

    private class Slot(val tv: TextureView) {
        var mp: MediaPlayer? = null
        var surface: Surface? = null
        var ready = false
        var vw = 0
        var vh = 0
        var pending: File? = null
    }

    private val slots = arrayOf(Slot(TextureView(context)), Slot(TextureView(context)))
    private var active = 0
    private var switching = false
    private var started = false
    private var paused = false
    private var failures = 0
    private val order = ArrayList<File>()
    private var idx = 0
    private val h = Handler(Looper.getMainLooper())
    private val tmp = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstR = android.graphics.Rect(0, 0, 128, 72)
    private var fade: ValueAnimator? = null

    private val monitor = object : Runnable {
        override fun run() {
            val s = slots[active]
            val mp = s.mp
            if (!switching && mp != null && s.ready && !paused) {
                try {
                    val d = mp.duration
                    if (d > 0 && d - mp.currentPosition < 3400) startNext()
                } catch (e: Exception) {
                }
            }
            h.postDelayed(this, 200)
        }
    }

    init {
        for (s in slots) {
            s.tv.alpha = 0f
            s.tv.isOpaque = true
            addView(s.tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            s.tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, hh: Int) {
                    s.surface = Surface(st)
                    s.pending?.let { f -> s.pending = null; prepare(s, f) }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, hh: Int) {
                    crop(s)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    s.mp?.setSurface(null)
                    s.surface?.release()
                    s.surface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun nextFile(): File {
        if (order.isEmpty() || idx >= order.size) {
            order.clear()
            order.addAll(files.shuffled())
            idx = 0
        }
        return order[idx++]
    }

    private fun crop(s: Slot) {
        val w = s.tv.width.toFloat()
        val hh = s.tv.height.toFloat()
        if (w <= 0f || hh <= 0f || s.vw <= 0 || s.vh <= 0) return
        val va = s.vw / s.vh.toFloat()
        val ta = w / hh
        val m = Matrix()
        if (va > ta) m.setScale(va / ta, 1f, w / 2f, hh / 2f) else m.setScale(1f, ta / va, w / 2f, hh / 2f)
        s.tv.setTransform(m)
    }

    private fun prepare(s: Slot, f: File) {
        val sf = s.surface
        if (sf == null) {
            s.pending = f
            return
        }
        s.ready = false
        s.mp?.release()
        val mp = MediaPlayer()
        s.mp = mp
        try {
            mp.setSurface(sf)
            mp.setDataSource(f.path)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, hh ->
                s.vw = w
                s.vh = hh
                crop(s)
            }
            mp.setOnErrorListener { _, _, _ ->
                failures++
                s.ready = false
                if (failures < 6 && files.isNotEmpty()) prepare(s, nextFile())
                true
            }
            mp.setOnPreparedListener {
                failures = 0
                s.ready = true
                if (!paused) it.start()
                onReady(s)
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            failures++
            if (failures < 6) h.post { prepare(s, nextFile()) }
        }
    }

    private fun onReady(s: Slot) {
        val from = slots[active]
        if (s === from) {
            // pierwsze wejście: płynne pojawienie się
            fade?.cancel()
            val a = ValueAnimator.ofFloat(s.tv.alpha, 1f)
            a.duration = 900
            a.addUpdateListener { s.tv.alpha = it.animatedValue as Float }
            fade = a
            a.start()
            return
        }
        // crossfade z aktywnego na nowy
        fade?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 3000
        a.addUpdateListener {
            val f = it.animatedValue as Float
            s.tv.alpha = f
            from.tv.alpha = 1f - f
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                from.tv.alpha = 0f
                s.tv.alpha = 1f
                try { from.mp?.stop() } catch (e: Exception) { }
                from.mp?.release()
                from.mp = null
                from.ready = false
                active = if (s === slots[0]) 0 else 1
                switching = false
            }
        })
        fade = a
        a.start()
    }

    private fun startNext() {
        switching = true
        val ni = 1 - active
        prepare(slots[ni], nextFile())
    }

    fun start() {
        paused = false
        if (!started) {
            started = true
            h.removeCallbacks(monitor)
            h.postDelayed(monitor, 400)
            prepare(slots[0], nextFile())
        } else {
            for (s in slots) if (s.ready) try { s.mp?.start() } catch (e: Exception) { }
            h.removeCallbacks(monitor)
            h.postDelayed(monitor, 400)
        }
    }

    fun pause() {
        paused = true
        h.removeCallbacks(monitor)
        for (s in slots) try { s.mp?.pause() } catch (e: Exception) { }
    }

    fun release() {
        h.removeCallbacks(monitor)
        fade?.cancel()
        for (s in slots) {
            try { s.mp?.release() } catch (e: Exception) { }
            s.mp = null
        }
    }

    /** Miesza aktualne klatki (z uwzględnieniem crossfade) do małej bitmapy 128x72. */
    fun snapshotTo(bmp: Bitmap, cv: Canvas): Boolean {
        var any = false
        cv.drawColor(0xFF000000.toInt())
        for (s in slots) {
            if (!s.ready || s.tv.alpha <= 0.01f || !s.tv.isAvailable) continue
            s.tv.getBitmap(tmp)
            paint.alpha = (s.tv.alpha * 255f).toInt()
            cv.drawBitmap(tmp, null, dstR, paint)
            any = true
        }
        return any
    }
}
