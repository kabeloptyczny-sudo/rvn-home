package pl.frutiger.tvhome

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

object Motion {
    const val MS = 220L
    const val DUR_FAST = 150L
    const val DUR_NORMAL = 220L
    const val FADE_IN = 250L
    const val FADE_OUT = 250L
    val EASE: Interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
}
