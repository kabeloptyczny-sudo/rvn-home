package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

object Pal {
    const val SAND = 0xFFEFE8DA.toInt()
    const val TEXT = 0xFFEFE8DA.toInt()
    const val TEXT_DIM = 0x80EFE8DA.toInt()
    const val TILE = 0x331B2027.toInt()
    const val GRAPHITE = 0xFF0C1016.toInt()
    const val UP = 0xFF6FBF8E.toInt()
    const val DOWN = 0xFFD9776B.toInt()
    const val FLAT = 0xFFB8B8B8.toInt()
}

fun brandTag(ctx: Context, u: Float): TextView {
    val t = TextView(ctx)
    t.text = "rvn"
    t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 0.95f)
    t.setTextColor(Pal.TEXT_DIM)
    t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.includeFontPadding = false
    return t
}
