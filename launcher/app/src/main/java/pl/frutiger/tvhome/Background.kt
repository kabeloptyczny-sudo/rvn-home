package pl.frutiger.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

object Wallpapers {
    private fun dir(ctx: Context): File? = ctx.getExternalFilesDir("wallpapers")?.also { it.mkdirs() }

    fun videos(ctx: Context): List<File> =
        dir(ctx)?.listFiles { f -> f.isFile && f.extension.lowercase() == "mp4" }?.sortedBy { it.name } ?: emptyList()

    fun images(ctx: Context): List<File> {
        val exts = setOf("jpg", "jpeg", "png", "webp")
        return dir(ctx)?.listFiles { f -> f.isFile && f.extension.lowercase() in exts }?.sortedBy { it.name } ?: emptyList()
    }
}

/**
 * Wybiera i prowadzi tło: wideo (jeśli są .mp4) > własne obrazy (jeśli są) > natura rysowana kodem.
 * Dla wideo i natury co kilkaset ms robi małą klatkę 128x72, rozmywa ją i podaje szkłu (Backdrop).
 * Dwie bitmapy na przemian - bez alokacji w pętli (poza małymi shaderami szkła przy zamianie).
 */
class BackgroundController(
    private val ctx: Context,
    private val root: FrameLayout,
    val mode: Mode
) {
    enum class Mode { VIDEO, IMAGES, NATURE }

    companion object {
        fun detect(ctx: Context): Mode = when {
            Wallpapers.videos(ctx).isNotEmpty() -> Mode.VIDEO
            Wallpapers.images(ctx).isNotEmpty() -> Mode.IMAGES
            else -> Mode.NATURE
        }
    }

    private var nature: NatureView? = null
    private var video: VideoBg? = null
    private val ui = Handler(Looper.getMainLooper())
    private val bmps = arrayOf(
        Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
    )
    private val cvs = arrayOf(Canvas(bmps[0]), Canvas(bmps[1]))
    private val walls = arrayOf(Wall(bmps[0], bmps[0]), Wall(bmps[1], bmps[1]))
    private val px = IntArray(128 * 72)
    private val tmp = IntArray(128 * 72)
    private var back = 0
    private var running = false

    private val task = object : Runnable {
        override fun run() {
            if (!running) return
            snapshot()
            ui.postDelayed(this, if (mode == Mode.VIDEO) 150L else 500L)
        }
    }

    fun install() {
        val lp = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        when (mode) {
            Mode.VIDEO -> {
                video = VideoBg(ctx, Wallpapers.videos(ctx)).also { root.addView(it, 0, lp()) }
            }
            Mode.IMAGES -> root.addView(WallpaperView(ctx), 0, lp())
            Mode.NATURE -> {
                nature = NatureView(ctx).also { root.addView(it, 0, lp()) }
            }
        }
    }

    fun start() {
        nature?.start()
        video?.start()
        if (mode != Mode.IMAGES) {
            running = true
            ui.removeCallbacks(task)
            ui.postDelayed(task, 120)
        }
    }

    fun stop() {
        running = false
        ui.removeCallbacks(task)
        nature?.stop()
        video?.pause()
    }

    fun setParallax(p: Float) {
        nature?.parallaxTarget = p.coerceIn(-1f, 1f)
    }

    private fun snapshot() {
        val b = back
        var ok = true
        val n = nature
        val v = video
        if (n != null) n.snapshotTo(bmps[b], cvs[b])
        else if (v != null) ok = v.snapshotTo(bmps[b], cvs[b])
        if (!ok) return
        bmps[b].getPixels(px, 0, 128, 0, 0, 128, 72)
        Blur.boxBlur(px, 128, 72, 3, 2, tmp)
        bmps[b].setPixels(px, 0, 128, 0, 0, 128, 72)
        Backdrop.prev = null
        Backdrop.cur = walls[b]
        Backdrop.fade = 1f
        back = 1 - b
        Backdrop.commit()
    }
}
