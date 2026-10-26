package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.TextView

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

/** Ruch: 220 ms, ease-out, bez overshootu (jedyna deklaracja Motion w projekcie). */
object Motion {
    const val MS = 220L
    const val LABEL_MS = 150L
    const val FADE_MS = 250L
    val EASE: Interpolator = DecelerateInterpolator(1.6f)
}

/** Napis firmowy (jedna stała, dokładnie ten tekst). */
const val RAEVEN_TAG = "powered by RAEVEN_networking z GK RAEVEN"

/** Bardzo mały, półprzezroczysty, nieruchomy napis do rogu ekranu. */
fun brandTag(ctx: Context, u: Float): TextView {
    val t = TextView(ctx)
    t.text = RAEVEN_TAG
    t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 0.78f)
    t.setTextColor(Pal.TEXT)
    t.alpha = 0.34f
    t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.includeFontPadding = false
    t.isFocusable = false
    t.isClickable = false
    return t
}
