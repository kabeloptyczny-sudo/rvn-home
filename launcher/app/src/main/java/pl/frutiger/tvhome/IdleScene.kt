package pl.frutiger.tvhome

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class IdleScene(ctx: Context, private val u: Float) : FrameLayout(ctx), QuoteSink {
    
    private val pl = java.util.Locale("pl", "PL")
    private val nf = java.text.NumberFormat.getNumberInstance(pl)
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", pl)
    
    private val gpwRow = LinearLayout(ctx)
    private val items = LinkedHashMap<String, GpwItem>()
    
    init {
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        
        clipChildren = false
        
        gpwRow.orientation = LinearLayout.HORIZONTAL
        gpwRow.gravity = Gravity.CENTER
        gpwRow.setPadding((u * 2f).toInt(), (u * 1.5f).toInt(), (u * 2f).toInt(), (u * 1.5f).toInt())
        
        for ((k, n) in GPW_INDICES) {
            val item = GpwItem(ctx, u, n)
            gpwRow.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            items[k] = item
        }
        
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.bottomMargin = (Backdrop.screenH * 0.08f).toInt()
        addView(gpwRow, lp)
    }
    
    fun refreshInfo() {
    }
    
    fun start() {
    }
    
    fun stop() {
    }
    
    override fun bind(data: Map<String, Quote>, newest: Long, stale: Boolean, loc: java.util.Locale, tz: java.util.TimeZone) {
        clockFmt.timeZone = tz
        for ((k, item) in items) {
            val q = data[k]
            if (q == null) {
                item.visibility = android.view.View.GONE
                continue
            }
            item.visibility = android.view.View.VISIBLE
            item.setValue(nf.format(q.price), q.changePct)
        }
    }
    
    class GpwItem(ctx: Context, u: Float, label: String) : LinearLayout(ctx) {
        private val nameLabel = TextView(ctx)
        private val valueLabel = RollingValueView(ctx, u * 1.5f, "sans-serif-light", true)
        private val changeLabel = TextView(ctx)
        
        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            
            nameLabel.text = label
            nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.0f)
            nameLabel.setTextColor(Pal.TEXT_DIM)
            nameLabel.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            nameLabel.includeFontPadding = false
            
            changeLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 0.9f)
            changeLabel.setTextColor(Pal.FLAT)
            changeLabel.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            changeLabel.includeFontPadding = false
            
            addView(nameLabel)
            addView(valueLabel)
            addView(changeLabel)
        }
        
        fun setValue(price: String, pct: Double) {
            valueLabel.set(price, 0)
            val up = pct > 0.004
            val down = pct < -0.004
            changeLabel.text = if (up) "▲" else if (down) "▼" else "–"
            changeLabel.setTextColor(if (up) Pal.UP else if (down) Pal.DOWN else Pal.FLAT)
        }
    }
}
