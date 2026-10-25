package pl.frutiger.tvhome

import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

/** Paleta: grafit, głęboki granat, zgaszona zieleń, ciepły piasek. */
object Pal {
    val GRAPHITE = hex(0xFF1E2228L)
    val NAVY = hex(0xFF16213AL)
    val SAGE = hex(0xFF7E9C86L)
    val SAND = hex(0xFFE3D5BCL)
    val TILE = hex(0xFF262C34L)
    val TEXT = hex(0xFFEFE8DAL)
    val TEXT_DIM = hex(0xA6EFE8DAL)
    val RING = hex(0xF2F2EBDDL)
    val UP = hex(0xFF6FBF8EL)
    val DOWN = hex(0xFFD9776BL)
    val FLAT = hex(0xB3EFE8DAL)
}

/** Ruch: 220 ms, ease-out, bez overshootu. */
object Motion {
    const val MS = 220L
    val EASE: Interpolator = DecelerateInterpolator(1.6f)
}
