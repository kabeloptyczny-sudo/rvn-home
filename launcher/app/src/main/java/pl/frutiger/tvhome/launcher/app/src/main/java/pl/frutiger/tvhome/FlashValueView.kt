package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Wartość w widżecie GPW: po zmianie krótko zielona/czerwona (ok. 3 s), potem wraca do koloru tekstu. Jeden Runnable na widok. */
class FlashValueView(ctx: Context, size: Float, font: String, rightAlign: Boolean) : TextView(ctx) {
    private val reset = Runnable { setTextColor(Pal.TEXT) }

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        typeface = Typeface.create(font, Typeface.NORMAL)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        setTextColor(Pal.TEXT)
        if (rightAlign) gravity = Gravity.END
    }

    fun set(text: String, flash: Int) {
        this.text = text
        removeCallbacks(reset)
        if (flash != 0) {
            setTextColor(if (flash > 0) Pal.UP else Pal.DOWN)
            postDelayed(reset, 3000L)
        } else {
            setTextColor(Pal.TEXT)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(reset)
        super.onDetachedFromWindow()
    }
}
