package pl.frutiger.tvhome

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Wygaszacz ekranu (DreamService): ta sama natura + zegar, który powoli wędruje (ochrona przed wypaleniem). */
class Saver : DreamService() {

    private var nature: NatureView? = null
    private var clock: GlassClockView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var u = 19.2f
    private var step = 0

    private val drift = object : Runnable {
        override fun run() {
            val c = clock ?: return
            step++
            val dx = (if (step % 2 == 0) 0f else 1f) * u * 3f
            val dy = ((step / 2) % 3) * u * 1.2f
            c.animate().translationX(dx).translationY(-dy).setDuration(4000).start()
            ui.postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        u = resources.displayMetrics.widthPixels / 100f
        val root = FrameLayout(this)
        val n = NatureView(this)
        nature = n
        root.addView(n, FrameLayout.LayoutParams(-1, -1))
        val c = GlassClockView(this, u, 1.15f)
        clock = c
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = (u * 6f).toInt()
        lp.bottomMargin = (u * 5f).toInt()
        root.addView(c, lp)
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        nature?.start()
        clock?.start()
        ui.postDelayed(drift, 60_000L)
    }

    override fun onDreamingStopped() {
        ui.removeCallbacks(drift)
        nature?.stop()
        clock?.stop()
        super.onDreamingStopped()
    }
}
