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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private lateinit var bg: BackgroundController
    private lateinit var gpw: GpwController
    private lateinit var weather: WeatherController
    private lateinit var clock: GlassClockView
    private lateinit var recentBox: LinearLayout
    private lateinit var recentRow: LinearLayout
    private val tilePkg = HashMap<View, String>()
    private var reorderItem: View? = null
    private var reorderArmed = false
    private var lastFocusX = -1f
    private var mode = BackgroundController.Mode.NATURE

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
        mode = BackgroundController.detect(this)
        bg = BackgroundController(this, root, mode)
        bg.install()

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
        rowScroll.setPadding(uu(6f), uu(2.4f), uu(6f), uu(2.0f))
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        rowScroll.addView(row, WRAP, WRAP)
        rowScroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass(row) }
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.clipChildren = false
        bottom.addView(rowScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        buildRecentSection()
        bottom.addView(recentBox, LinearLayout.LayoutParams(MATCH, WRAP))
        val rlp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        rlp.bottomMargin = uu(1.4f)
        root.addView(bottom, rlp)

        // widżet GPW (lewa strona, pod zegarem)
        val gw = GpwWidget(this, u)
        val glp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        glp.leftMargin = uu(6f)
        glp.topMargin = uu(19.0f)
        root.addView(gw, glp)
        gpw = GpwController(this, worker, ui, gw)

        // skróty + pogoda (prawy górny róg)
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.clipChildren = false
        right.addView(ShortcutBar(this, u) { launch(it) }, LinearLayout.LayoutParams(WRAP, WRAP))
        val ww = WeatherWidget(this, u)
        val wlp = LinearLayout.LayoutParams(WRAP, WRAP)
        wlp.topMargin = uu(1.5f)
        right.addView(ww, wlp)
        weather = WeatherController(worker, ui, ww)
        val rt = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        rt.rightMargin = uu(6f)
        rt.topMargin = uu(4.2f)
        root.addView(right, rt)

        setContentView(root)
        loadSources()
        buildRow(true, true)
        buildRecent()
    }

    override fun onResume() {
        super.onResume()
        if (BackgroundController.detect(this) != mode) {
            recreate()
            return
        }
        loadSources()
        greetingText.text = greeting()
        ui.removeCallbacks(rotateTask)
        val wait = if (Backdrop.cur == null) 0L
        else Math.max(0L, rotateMs - (SystemClock.uptimeMillis() - lastChange))
        ui.postDelayed(rotateTask, wait)
        bg.start()
        gpw.start()
        weather.start()
        clock.start()
        buildRecent()
    }

    override fun onPause() {
        ui.removeCallbacks(rotateTask)
        bg.stop()
        gpw.stop()
        weather.stop()
        clock.stop()
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
        clock = GlassClockView(this, u)

        val pill = GlassView(this, u * 1.3f, hex(0x40FFFFFFL), hex(0x14FFFFFFL), true)
        greetingText = TextView(this)
        greetingText.text = greeting()
        greetingText.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.2f)
        greetingText.setTextColor(hex(0xF2FFFFFFL))
        greetingText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        greetingText.setPadding(uu(1.7f), uu(0.55f), uu(1.7f), uu(0.6f))
        pill.addView(greetingText, FrameLayout.LayoutParams(WRAP, WRAP))
        val plp = LinearLayout.LayoutParams(WRAP, WRAP)
        plp.leftMargin = uu(1.6f)
        clock.dateRow.addView(pill, plp)
        return clock
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
        // obrazy własne tylko w trybie IMAGES; wideo i natura obsługuje BackgroundController
        sources = if (mode == BackgroundController.Mode.IMAGES) Wallpapers.images(this).map { it.path } else emptyList()
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
        Backdrop.commit()
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
            (intent.component?.packageName ?: intent.getPackage())?.let { recordRecent(it) }
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
        onClick: () -> Unit, onLong: (() -> Unit)?,
        w: Float = cardW, h: Float = cardH, showLabel: Boolean = true
    ): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER_HORIZONTAL
        item.clipChildren = false
        item.isFocusable = true
        item.isClickable = true
        item.setDefaultFocusHighlightEnabled(false)

        val card = GlassView(this, cardRadius() * (w / cardW), hex(0x3DFFFFFFL), hex(0x12FFFFFFL), true)
        card.elevation = u * 0.5f
        card.zLift = u * 1.4f
        card.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        item.addView(card, LinearLayout.LayoutParams(uu(w), uu(h)))

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
        val tlp = LinearLayout.LayoutParams(uu(w), WRAP)
        if (!showLabel) text.visibility = View.GONE
        tlp.topMargin = uu(1.3f)
        item.addView(text, tlp)
        item.tag = text

        val dirH = FloatArray(1)
        card.onFocusProgress = { f ->
            val z = 1f + 0.05f * f
            content.scaleX = z
            content.scaleY = z
            content.translationX = dirH[0] * u * 0.55f * (1f - f.coerceIn(0f, 1f))
        }
        item.setOnFocusChangeListener { v, has ->
            var dir = 0f
            if (has) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (lastFocusX >= 0f && loc[0] != lastFocusX.toInt()) dir = if (loc[0] > lastFocusX) -1f else 1f
                lastFocusX = loc[0].toFloat()
                dirH[0] = dir
                bg.setParallax((loc[0] + v.width / 2f) / Backdrop.screenW * 2f - 1f)
            }
            card.setFocusLook(has, dir)
            text.animate().alpha(if (has) 1f else 0.7f).setDuration(180).start()
        }
        item.setOnClickListener { onClick() }
        if (onLong != null) item.setOnLongClickListener { onLong(); true }
        return item
    }

    private fun buildRow(focusFirst: Boolean, animate: Boolean) {
        row.removeAllViews()
        tilePkg.clear()
        val pm = packageManager
        for (pkg in favorites().take(9)) {
            val intent = launchIntent(pkg) ?: continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            val hold = arrayOfNulls<View>(1)
            val item = makeTile(label, visualFor(pkg, intent), { launch(intent) }, { hold[0]?.let { startReorder(it) } })
            hold[0] = item
            tilePkg[item] = pkg
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

    // ---------------------------------------------------------------- ostatnio użyte

    private fun recents(): MutableList<String> =
        (prefs.getString("recent", null) ?: "").split(",").filter { it.isNotBlank() }.toMutableList()

    private fun recordRecent(pkg: String) {
        if (pkg == packageName) return
        val r = recents()
        r.remove(pkg)
        r.add(0, pkg)
        prefs.edit().putString("recent", r.take(10).joinToString(",")).apply()
    }

    private fun buildRecentSection() {
        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        recentBox.clipChildren = false
        val title = TextView(this)
        title.text = "Ostatnio użyte"
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.3f)
        title.setTextColor(hex(0xCCFFFFFFL))
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setShadowLayer(u * 0.6f, 0f, u * 0.15f, hex(0x99000000L))
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.leftMargin = uu(6.4f)
        recentBox.addView(title, tlp)
        val sc = HorizontalScrollView(this)
        sc.isHorizontalScrollBarEnabled = false
        sc.overScrollMode = View.OVER_SCROLL_NEVER
        sc.clipChildren = false
        sc.clipToPadding = false
        sc.setPadding(uu(6f), uu(1.1f), uu(6f), uu(0.8f))
        recentRow = LinearLayout(this)
        recentRow.orientation = LinearLayout.HORIZONTAL
        recentRow.clipChildren = false
        sc.addView(recentRow, WRAP, WRAP)
        sc.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass(recentRow) }
        recentBox.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP))
        recentBox.visibility = View.GONE
    }

    private fun buildRecent() {
        recentRow.removeAllViews()
        val pm = packageManager
        var n = 0
        for (pkg in recents()) {
            if (n >= 8) break
            val intent = launchIntent(pkg) ?: continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            val item = makeTile(label, visualFor(pkg, intent), { launch(intent) }, null, 5.9f, 5.9f * 9f / 16f, false)
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = uu(1.1f)
            recentRow.addView(item, lp)
            n++
        }
        recentBox.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- zmiana kolejności ulubionych

    private fun startReorder(item: View) {
        reorderItem = item
        reorderArmed = false
        Toast.makeText(this, "← → przesuwają kafelek, OK kończy", Toast.LENGTH_SHORT).show()
    }

    private fun moveTile(item: View, dir: Int) {
        val idx = row.indexOfChild(item)
        val ni = idx + dir
        if (idx < 0 || ni < 0 || ni > row.childCount - 2) return
        val other = row.getChildAt(ni)
        val step = (item.width + uu(1.5f)).toFloat()
        val lp = item.layoutParams
        row.removeView(item)
        row.addView(item, ni, lp)
        item.translationX = -dir * step
        other.translationX = dir * step
        for (v in arrayOf(item, other)) {
            v.animate().translationX(0f).setDuration(280)
                .setInterpolator(OvershootInterpolator(1.1f))
                .setUpdateListener { Backdrop.invalidateAll() }
                .withEndAction { Backdrop.invalidateAll() }
                .start()
        }
        item.requestFocus()
        val list = ArrayList<String>()
        for (i in 0 until row.childCount - 1) tilePkg[row.getChildAt(i)]?.let { list.add(it) }
        prefs.edit().putString("fav", list.joinToString(",")).apply()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val item = reorderItem
        if (item != null) {
            val k = e.keyCode
            val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (e.action == KeyEvent.ACTION_DOWN) {
                when {
                    k == KeyEvent.KEYCODE_DPAD_LEFT -> moveTile(item, -1)
                    k == KeyEvent.KEYCODE_DPAD_RIGHT -> moveTile(item, 1)
                    ok -> if (reorderArmed && e.repeatCount == 0) reorderItem = null
                    k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) reorderItem = null
                }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP) {
                if (ok) reorderArmed = true
                return true
            }
        }
        return super.dispatchKeyEvent(e)
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
        sheet.lens = false

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
