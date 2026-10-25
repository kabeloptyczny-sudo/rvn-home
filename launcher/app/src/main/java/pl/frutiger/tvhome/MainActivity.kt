package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * RVN Home 0.2 - ekran główny w stylu Apple TV z szkłem à la iOS 26 (Liquid Glass).
 * Wszystko rysowane w kodzie, bez zewnętrznych bibliotek.
 *
 * Wszystkie rozmiary liczone są względem szerokości ekranu (1 jednostka = 1% szerokości),
 * więc wygląda tak samo na każdym telewizorze, niezależnie od gęstości ekranu.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var greetingText: TextView
    private var overlay: View? = null
    private var sources: List<String> = emptyList()
    private var srcIndex = 0
    private var lastChange = 0L
    private var fadeAnim: ValueAnimator? = null
    private var u = 19.2f
    private val rotateMs = 180_000L

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // kafelek 16:9, jak banery aplikacji na Android TV
    private val cardW = 12.4f
    private val cardH = 12.4f * 9f / 16f

    private val rotateTask = object : Runnable {
        override fun run() {
            nextWallpaper()
            ui.postDelayed(this, rotateMs)
        }
    }

    private val defaultFavs = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.spotify.tv.android",
        "com.android.tv.settings"
    )

    private fun uu(v: Float): Int = (v * u).roundToInt()

    // ---------------------------------------------------------------- cykl życia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", MODE_PRIVATE)

        val dm = resources.displayMetrics
        u = dm.widthPixels / 100f
        Backdrop.screenW = dm.widthPixels.toFloat()
        Backdrop.screenH = dm.heightPixels.toFloat()

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        root = FrameLayout(this)
        root.setBackgroundColor(hex(0xFF05070FL))
        root.clipChildren = false
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val nw = (r - l).toFloat()
            val nh = (b - t).toFloat()
            if (nw > 0f && nh > 0f && (nw != Backdrop.screenW || nh != Backdrop.screenH)) {
                Backdrop.screenW = nw
                Backdrop.screenH = nh
                Backdrop.invalidateAll()
            }
        }

        // tapeta
        root.addView(WallpaperView(this), MATCH, MATCH)

        // delikatne przyciemnienie góry i dołu (czytelność zegara i etykiet)
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(hex(0x59000000L), 0x00000000, 0x00000000, hex(0x99000000L))
        )
        root.addView(scrim, MATCH, MATCH)

        // zegar i powitanie
        val header = buildHeader()
        val hlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        hlp.leftMargin = uu(6f)
        hlp.topMargin = uu(4.2f)
        root.addView(header, hlp)

        // rząd kafelków
        rowScroll = HorizontalScrollView(this)
        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.overScrollMode = View.OVER_SCROLL_NEVER
        rowScroll.clipChildren = false
        rowScroll.clipToPadding = false
        rowScroll.setPadding(uu(6f), uu(3.5f), uu(6f), uu(3.5f))
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        rowScroll.addView(row, WRAP, WRAP)
        rowScroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass(row) }
        val rlp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        rlp.bottomMargin = uu(3f)
        root.addView(rowScroll, rlp)

        setContentView(root)
        loadSources()
        buildRow(true, true)
    }

    override fun onResume() {
        super.onResume()
        loadSources()
        greetingText.text = greeting()
        ui.removeCallbacks(rotateTask)
        val wait = if (Backdrop.cur == null) 0L
        else Math.max(0L, rotateMs - (SystemClock.uptimeMillis() - lastChange))
        ui.postDelayed(rotateTask, wait)
    }

    override fun onPause() {
        ui.removeCallbacks(rotateTask)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideAllApps()
        rowScroll.smoothScrollTo(0, 0)
        row.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) hideAllApps()
        // na ekranie głównym przycisk Wstecz nic nie robi
    }

    // ---------------------------------------------------------------- nagłówek

    private fun greeting(): String {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hr < 5 -> "Dobrej nocy"
            hr < 12 -> "Dzień dobry"
            hr < 18 -> "Miłego dnia"
            else -> "Dobry wieczór"
        }
    }

    private fun buildHeader(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.clipChildren = false

        val time = TextClock(this)
        time.format24Hour = "HH:mm"
        time.format12Hour = "HH:mm"
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 8.6f)
        time.setTextColor(hex(0xF2FFFFFFL))
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        time.includeFontPadding = false
        time.letterSpacing = -0.02f
        time.setShadowLayer(u * 1.4f, 0f, u * 0.3f, hex(0x55000000L))
        box.addView(time)

        val date = TextClock(this)
        date.format24Hour = "EEEE, d MMMM"
        date.format12Hour = "EEEE, d MMMM"
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.8f)
        date.setTextColor(hex(0xD9FFFFFFL))
        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        date.setShadowLayer(u * 0.8f, 0f, u * 0.2f, hex(0x66000000L))
        val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
        dlp.topMargin = uu(0.5f)
        box.addView(date, dlp)

        val pill = GlassView(this, u * 1.3f, hex(0x40FFFFFFL), hex(0x14FFFFFFL), true)
        greetingText = TextView(this)
        greetingText.text = greeting()
        greetingText.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.2f)
        greetingText.setTextColor(hex(0xF2FFFFFFL))
        greetingText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        greetingText.setPadding(uu(1.7f), uu(0.55f), uu(1.7f), uu(0.6f))
        pill.addView(greetingText, FrameLayout.LayoutParams(WRAP, WRAP))
        val plp = LinearLayout.LayoutParams(WRAP, WRAP)
        plp.topMargin = uu(1.6f)
        box.addView(pill, plp)
        return box
    }

    /** Odświeża tło szkła w dzieciach (po przewinięciu rzędu / siatki). */
    private fun invalidateGlass(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c is GlassView) c.invalidate()
            if (c is ViewGroup) invalidateGlass(c)
        }
    }

    // ---------------------------------------------------------------- tapety

    private fun loadSources() {
        val scenes = (0 until Scenes.COUNT).map { "scene:$it" }
        val dir = getExternalFilesDir("wallpapers")
        dir?.mkdirs()
        val exts = setOf("jpg", "jpeg", "png", "webp")
        val files = dir?.listFiles { f -> f.isFile && f.extension.lowercase() in exts }
            ?.sortedBy { it.name } ?: emptyList()
        sources = if (files.isNotEmpty()) files.map { it.path } + scenes else scenes
    }

    private fun nextWallpaper() {
        if (sources.isEmpty()) return
        val src = sources[srcIndex % sources.size]
        srcIndex++
        worker.execute {
            val wall: Wall? = try {
                val full: Bitmap? =
                    if (src.startsWith("scene:")) Scenes.render(src.removePrefix("scene:").toInt(), 1280, 720)
                    else decodeFile(File(src))
                if (full != null) Blur.makeWall(full) else null
            } catch (e: Throwable) {
                null
            }
            if (wall != null) ui.post { showWallpaper(wall) }
        }
    }

    private fun decodeFile(f: File): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun showWallpaper(wall: Wall) {
        fadeAnim?.cancel()
        Backdrop.prev = Backdrop.cur
        Backdrop.cur = wall
        Backdrop.fade = 0f
        lastChange = SystemClock.uptimeMillis()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (Backdrop.prev == null) 900L else 1600L
        a.addUpdateListener {
            Backdrop.fade = it.animatedValue as Float
            Backdrop.invalidateAll()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (Backdrop.fade >= 1f) {
                    Backdrop.prev = null
                    Backdrop.invalidateAll()
                }
            }
        })
        fadeAnim = a
        a.start()
    }

    // ---------------------------------------------------------------- ulubione

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("fav", null)
        if (saved != null) return saved.split(",").filter { it.isNotBlank() }.toMutableList()
        val defaults = defaultFavs.filter { launchIntent(it) != null }
        prefs.edit().putString("fav", defaults.joinToString(",")).apply()
        return defaults.toMutableList()
    }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun launch(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nie można uruchomić aplikacji", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- kafelki

    private fun cardRadius(): Float = u * 1.7f

    private fun bannerView(d: Drawable): ImageView {
        val iv = ImageView(this)
        iv.setImageDrawable(d)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val rad = cardRadius()
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, rad)
            }
        }
        iv.clipToOutline = true
        return iv
    }

    private fun iconView(d: Drawable): ImageView {
        val iv = ImageView(this)
        iv.setImageDrawable(d)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(uu(1.7f), uu(1.5f), uu(1.7f), uu(1.5f))
        return iv
    }

    /** Baner aplikacji (jak w Apple TV), a gdy go brak - ikona na szkle. */
    private fun visualFor(pkg: String, intent: Intent): View {
        val pm = packageManager
        val banner: Drawable? = try {
            pm.getActivityBanner(intent) ?: pm.getApplicationBanner(pkg)
        } catch (e: Exception) {
            null
        }
        if (banner != null) return bannerView(banner)
        val icon: Drawable? = try {
            pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
        return iconView(icon ?: pm.defaultActivityIcon)
    }

    private fun makeTile(
        label: String, content: View,
        onClick: () -> Unit, onLong: (() -> Unit)?
    ): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER_HORIZONTAL
        item.clipChildren = false
        item.isFocusable = true
        item.isClickable = true
        item.setDefaultFocusHighlightEnabled(false)

        val card = GlassView(this, cardRadius(), hex(0x3DFFFFFFL), hex(0x12FFFFFFL), true)
        card.elevation = u * 0.5f
        card.zLift = u * 1.4f
        card.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        item.addView(card, LinearLayout.LayoutParams(uu(cardW), uu(cardH)))

        val text = TextView(this)
        text.text = label
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.3f)
        text.setTextColor(Color.WHITE)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.maxLines = 1
        text.ellipsize = android.text.TextUtils.TruncateAt.END
        text.gravity = Gravity.CENTER
        text.alpha = 0.7f
        text.setShadowLayer(u * 0.6f, 0f, u * 0.15f, hex(0x99000000L))
        val tlp = LinearLayout.LayoutParams(uu(cardW), WRAP)
        tlp.topMargin = uu(1.3f)
        item.addView(text, tlp)
        item.tag = text

        item.setOnFocusChangeListener { _, has ->
            card.setFocusLook(has)
            text.animate().alpha(if (has) 1f else 0.7f).setDuration(180).start()
        }
        item.setOnClickListener { onClick() }
        if (onLong != null) item.setOnLongClickListener { onLong(); true }
        return item
    }

    private fun buildRow(focusFirst: Boolean, animate: Boolean) {
        row.removeAllViews()
        val pm = packageManager
        for (pkg in favorites().take(9)) {
            val intent = launchIntent(pkg) ?: continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            val item = makeTile(label, visualFor(pkg, intent), { launch(intent) }, null)
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = uu(1.5f)
            row.addView(item, lp)
        }
        val apps = makeTile("Aplikacje", GlyphView(this), { showAllApps() }, null)
        row.addView(apps, LinearLayout.LayoutParams(WRAP, WRAP))
        if (focusFirst) row.getChildAt(0)?.requestFocus()
        if (animate) playEntrance()
    }

    /** Kafelki wjeżdżają płynnie jeden po drugim. */
    private fun playEntrance() {
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i)
            item.alpha = 0f
            item.translationY = u * 2.2f
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L + i * 70L)
                .setDuration(480)
                .setInterpolator(DecelerateInterpolator(2f))
                .setUpdateListener { Backdrop.invalidateAll() }
                .withEndAction { Backdrop.invalidateAll() }
                .start()
        }
    }

    // ---------------------------------------------------------------- wszystkie aplikacje

    private fun showAllApps() {
        if (overlay != null) return
        val pm = packageManager
        val map = linkedMapOf<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) map.putIfAbsent(ri.activityInfo.packageName, ri)
        }
        map.remove(packageName)
        val apps = map.values.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val favs = favorites()

        val sheet = GlassView(this, 0f, hex(0x8C070A14L), hex(0xB8070A14L), false)
        sheet.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        col.setPadding(uu(4.8f), uu(4f), uu(4.8f), 0)

        val title = TextView(this)
        title.text = "Aplikacje"
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 3.8f)
        title.setTextColor(hex(0xF2FFFFFFL))
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        title.includeFontPadding = false
        title.setPadding(uu(1.2f), 0, 0, 0)
        val hint = TextView(this)
        hint.text = "OK – uruchom   •   przytrzymaj OK – dodaj / usuń z ulubionych ★   •   Wstecz – zamknij"
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        hint.setTextColor(hex(0xB3FFFFFFL))
        hint.setPadding(uu(1.2f), 0, 0, 0)
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = uu(0.8f)
        col.addView(title)
        col.addView(hint, hlp)

        val grid = GridLayout(this)
        grid.columnCount = 6
        grid.clipChildren = false
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            val banner: Drawable? = try {
                ri.activityInfo.loadBanner(pm)
            } catch (e: Exception) {
                null
            }
            val content: View = if (banner != null) bannerView(banner) else iconView(ri.loadIcon(pm))
            val holder = arrayOfNulls<LinearLayout>(1)
            val item = makeTile(
                if (favs.contains(pkg)) "★ $label" else label, content,
                { launchIntent(pkg)?.let { launch(it) } },
                {
                    val f = favorites()
                    val nowFav: Boolean
                    if (f.contains(pkg)) {
                        f.remove(pkg)
                        nowFav = false
                    } else {
                        f.add(pkg)
                        nowFav = true
                    }
                    prefs.edit().putString("fav", f.joinToString(",")).apply()
                    (holder[0]?.tag as? TextView)?.text = if (nowFav) "★ $label" else label
                    buildRow(false, false)
                }
            )
            holder[0] = item
            val glp = GridLayout.LayoutParams()
            glp.setMargins(0, 0, uu(1.4f), uu(1.4f))
            grid.addView(item, glp)
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.setPadding(uu(1.2f), uu(2f), uu(1.2f), uu(4f))
        scroll.addView(grid, WRAP, WRAP)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass(grid) }
        val slp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        slp.topMargin = uu(1.2f)
        col.addView(scroll, slp)

        sheet.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(sheet, MATCH, MATCH)
        overlay = sheet

        // rząd pod spodem nie może przejmować fokusu
        rowScroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        grid.getChildAt(0)?.requestFocus()

        sheet.alpha = 0f
        col.translationY = u * 2.5f
        sheet.animate().alpha(1f).setDuration(220)
            .setUpdateListener { Backdrop.invalidateAll() }
            .withEndAction { Backdrop.invalidateAll() }
            .start()
        col.animate().translationY(0f).setDuration(340)
            .setInterpolator(DecelerateInterpolator(2f))
            .setUpdateListener { Backdrop.invalidateAll() }
            .withEndAction { Backdrop.invalidateAll() }
            .start()
    }

    private fun hideAllApps() {
        val sheet = overlay ?: return
        overlay = null
        rowScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        row.getChildAt(row.childCount - 1)?.requestFocus()
        (sheet as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        sheet.animate().alpha(0f).setDuration(160)
            .setUpdateListener { Backdrop.invalidateAll() }
            .withEndAction { root.removeView(sheet) }
            .start()
    }
}
