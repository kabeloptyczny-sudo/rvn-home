package pl.frutiger.tvhome

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

object Motion {
    const val MS = 220L
    val EASE: Interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
}
