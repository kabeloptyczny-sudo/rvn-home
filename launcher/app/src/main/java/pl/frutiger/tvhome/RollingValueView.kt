package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

class RollingValueView(ctx: Context, size: Float, font: String, rightAlign: Boolean) : TextView(ctx) {
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        setTypeface(Typeface.create(font, Typeface.NORMAL))
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        if (rightAlign) {
            gravity = android.view.Gravity.END
        }
    }
    
    fun set(text: String, flash: Int) {
        this.text = text
        if (flash != 0) {
            val color = if (flash > 0) Pal.UP else Pal.DOWN
            setTextColor(color)
            postDelayed({ setTextColor(Pal.TEXT) }, 3000L)
        } else {
            setTextColor(Pal.TEXT)
        }
    }
}
