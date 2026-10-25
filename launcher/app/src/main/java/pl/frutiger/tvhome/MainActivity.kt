package pl.frutiger.tvhome

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * RVN Home 0.3 - minimalistyczny ekran główny. Wszystkie rozmiary liczone od szerokości ekranu
 * (1 jednostka u = 1% szerokości). Animacje 220 ms ease-out, bez overshootu.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var greetingText: TextView
    private lateinit var bg: BackgroundController
    private lateinit var gpw: GpwController
    private lateinit var clock: ClockView
    private lateinit var gw: GpwWidget
    private lateinit var dockHost: PillHost
    private var gpwDetail: GpwDetailView? = null
    private var returnTo: View? = null
    private var overlay: View? = null
    private var sources: List<String> = emptyList()
    private var srcIndex = 0
    private var lastChange = 0L
    private var fadeAnim: ValueAnimator? = null
    private var u = 19.2f
    private val rotateMs = 180_000L
    private val tilePkg = HashMap<View, String>()
    private var lastDock: View? = null
    private var reorderItem: View? = null
    private var reorderArmed = false
    private var mode = BackgroundController.Mode.NATURE

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Liczba kafelków przed ulubionymi w doku (kafelek "TV"). */
    private val lead = 1
    private val dockTileW = 6.6f
    private val gridTileW = 11.2f

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
        root.setBackgroundColor(Pal.GRAPHITE)
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

        // tło: wideo > własne zdjęcia > natura
        mode = BackgroundController.detect(this)
        bg = BackgroundController(this, root, mode)
        bg.install()

        // własne zdjęcia/wideo: przyciemnienie ok. 25% (natura ma własne)
        if (mode != BackgroundController.Mode.NATURE) {
            val scrim = View(this)
            scrim.setBackgroundColor(hex(0x40000000L))
            root.addView(scrim, MATCH, MATCH)
        }

        // zegar + powitanie (lewy górny róg)
        clock = ClockView(this, u)
        greetingText = TextView(this)
        greetingText.text = greeting()
        greetingText.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.35f)
        greetingText.setTextColor(Pal.TEXT_DIM)
        greetingText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val glp = LinearLayout.LayoutParams(WRAP, WRAP)
        glp.topMargin = uu(0.5f)
        glp.leftMargin = uu(0.4f)
        clock.addView(greetingText, glp)
        val hlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        hlp.leftMargin = uu(6f)
        hlp.topMargin = uu(4.2f)
        root.addView(clock, hlp)

        // widżet GPW (prawy górny róg)
        gw = GpwWidget(this, u)
        gw.id = View.generateViewId()
        gw.onOpen = { openGpw() }
        val wlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        wlp.rightMargin = uu(6f)
        wlp.topMargin = uu(4.2f)
        root.addView(gw, wlp)
        gpw = GpwController(this, worker, ui, gw)

        // dok: wąska "wyspa" dopasowana do zawartości, wyśrodkowana, ciemne matowe szkło + obwódka 1 px
        val dock = FrostPanel(this, u * 2.2f, hex(0x9911151BL), hex(0x38FFFFFFL), 1f)
        rowScroll = HorizontalScrollView(this)
        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.overScrollMode = View.OVER_SCROLL_NEVER
        rowScroll.clipChildren = false
        rowScroll.clipToPadding = false
        rowScroll.setPadding(uu(0.8f), uu(0.6f), uu(0.8f), uu(0.4f))
        dockHost = PillHost(this, u)
        dockHost.setPadding(uu(1.0f), uu(1.0f), uu(1.0f), uu(0.8f))
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        dockHost.addView(row, WRAP, WRAP)
        rowScroll.addView(dockHost, WRAP, WRAP)
        dock.addView(rowScroll, FrameLayout.LayoutParams(WRAP, WRAP))
        val dlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dlp.leftMargin = uu(4f)
        dlp.rightMargin = uu(4f)
        dlp.bottomMargin = uu(2.6f)
        root.addView(dock, dlp)

        setContentView(root)
        loadSources()
        buildRow(true, true)
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
        gpwDetail?.start()
        clock.start()
    }

    override fun onPause() {
        ui.removeCallbacks(rotateTask)
        bg.stop()
        gpw.stop()
        gpwDetail?.stop()
        clock.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideOverlay(false)
        rowScroll.smoothScrollTo(0, 0)
        row.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) hideOverlay(true)
        // na ekranie głównym przycisk Wstecz nic nie robi
    }

    private fun greeting(): String {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hr < 5 -> "Dobrej nocy"
            hr < 12 -> "Dzień dobry"
            hr < 18 -> "Miłego dnia"
            else -> "Dobry wieczór"
        }
    }

    // ---------------------------------------------------------------- tapety własne (zdjęcia)

    private fun loadSources() {
        sources = if (mode == BackgroundController.Mode.IMAGES) Wallpapers.images(this).map { it.path } else emptyList()
    }

    private fun nextWallpaper() {
        if (sources.isEmpty()) return
        val src = sources[srcIndex % sources.size]
        srcIndex++
        worker.execute {
            val wall: Wall? = try {
                val full = decodeFile(File(src))
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

    /** Crossfade zdjęć 3 s. */
    private fun showWallpaper(wall: Wall) {
        fadeAnim?.cancel()
        Backdrop.prev = Backdrop.cur
        Backdrop.cur = wall
        Backdrop.fade = 0f
        Backdrop.commit()
        lastChange = SystemClock.uptimeMillis()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (Backdrop.prev == null) 900L else 3000L
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

    // ---------------------------------------------------------------- uruchamianie

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

    /** Start z płynnym przejściem (fade + lekki scale). Zwraca false, gdy się nie udało. */
    private fun tryStart(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.home_exit)
        startActivity(intent, opts.toBundle())
        true
    } catch (e: Exception) {
        false
    }

    private fun launch(intent: Intent, onFail: (() -> Unit)? = null) {
        if (tryStart(intent)) return
        if (onFail != null) onFail()
        else Toast.makeText(this, "Nie można uruchomić aplikacji", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- kafelki

    private fun iconView(d: Drawable): ImageView {
        val iv = ImageView(this)
        iv.setImageDrawable(d)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(uu(3.0f), uu(0.9f), uu(3.0f), uu(0.9f))
        return iv
    }

    private fun bannerView(d: Drawable): ImageView {
        val iv = ImageView(this)
        iv.setImageDrawable(d)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        return iv
    }

    /** Baner aplikacji (loadBanner), a gdy go brak - ikona na matowym kafelku tego samego rozmiaru. */
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
        label: String, content: View, w: Float,
        onClick: () -> Unit, onLong: (() -> Unit)?, dock: Boolean, host: PillHost? = null
    ): TileView {
        val tile = TileView(this, u, w, label, content)
        tile.onFocus = { v ->
            if (dock) {
                lastDock = v
                gw.nextFocusDownId = v.id
            }
            host?.focused(v)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            bg.setParallax((loc[0] + v.width / 2f) / Backdrop.screenW * 2f - 1f)
        }
        tile.onBlur = { host?.blurred() }
        if (dock) tile.nextFocusUpId = gw.id
        tile.setOnClickListener { onClick() }
        if (onLong != null) tile.setOnLongClickListener { onLong(); true }
        return tile
    }

    private fun buildRow(focusFirst: Boolean, animate: Boolean) {
        row.removeAllViews()
        tilePkg.clear()
        lastDock = null
        val pm = packageManager
        val gap = uu(1.2f)

        // pierwszy kafelek: TV (lista wejść)
        val tv = makeTile("TV", TvGlyphView(this), dockTileW, { openTv() }, null, true, dockHost)
        row.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = gap })

        for (pkg in favorites().take(9)) {
            val intent = launchIntent(pkg) ?: continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            val hold = arrayOfNulls<View>(1)
            val item = makeTile(label, visualFor(pkg, intent), dockTileW, { launch(intent) }, { hold[0]?.let { startReorder(it) } }, true, dockHost)
            hold[0] = item
            tilePkg[item] = pkg
            row.addView(item, LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = gap })
        }
        row.addView(makeTile("Aplikacje", GlyphView(this), dockTileW, { showAllApps() }, null, true, dockHost), LinearLayout.LayoutParams(WRAP, WRAP))
        gw.nextFocusDownId = row.getChildAt(0)?.id ?: View.NO_ID
        if (focusFirst) row.getChildAt(0)?.requestFocus()
        if (animate) playEntrance()
    }

    /** Kafelki pojawiają się płynnie jeden po drugim (fade + 1u w górę, 250 ms ease-out). */
    private fun playEntrance() {
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i)
            item.alpha = 0f
            item.translationY = u * 1.0f
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L + i * 40L)
                .setDuration(250)
                .setInterpolator(Motion.EASE)
                .start()
        }
    }

    // ---------------------------------------------------------------- widok szczegółowy GPW

    private fun openGpw() {
        if (overlay != null) return
        val holder = FrameLayout(this)
        val detail = GpwDetailView(this, u, worker, ui, YahooSource())
        val lp = FrameLayout.LayoutParams(MATCH, MATCH)
        val mh = uu(6f)
        val mv = (Backdrop.screenH * 0.06f).toInt()
        lp.setMargins(mh, mv, mh, mv)
        holder.addView(detail, lp)
        gpwDetail = detail
        showOverlay(holder, detail, hex(0xC00C1016L))
        detail.start()
    }

    // ---------------------------------------------------------------- wejścia TV

    private fun openTv() {
        val items = TvInputs.list(this)
        if (items.isEmpty()) {
            TvInputs.openSettings(this) { tryStart(it) }
            return
        }
        val (view, first) = buildInputPicker(this, u, items) { item ->
            hideOverlay(false)
            launch(TvInputs.intentFor(item)) { TvInputs.openSettings(this) { tryStart(it) } }
        }
        showOverlay(view, first, hex(0xB30C1016L))
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
        if (idx < lead || ni < lead || ni > row.childCount - 2) return
        val other = row.getChildAt(ni)
        val step = (item.width + uu(1.2f)).toFloat()
        val lp = item.layoutParams
        row.removeView(item)
        row.addView(item, ni, lp)
        item.translationX = -dir * step
        other.translationX = dir * step
        for (v in arrayOf(item, other)) {
            v.animate().translationX(0f).setDuration(Motion.MS).setInterpolator(Motion.EASE).start()
        }
        item.requestFocus()
        val list = ArrayList<String>()
        for (i in lead until row.childCount - 1) tilePkg[row.getChildAt(i)]?.let { list.add(it) }
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

    // ---------------------------------------------------------------- nakładki (aplikacje, wejścia)

    private fun showOverlay(sheet: View, focus: View?, scrimTint: Int) {
        if (overlay != null) return
        returnTo = currentFocus
        gw.isFocusable = false
        val wrap = FrostPanel(this, 0f, scrimTint, 0, 0f)
        wrap.isClickable = true
        wrap.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(wrap, MATCH, MATCH)
        overlay = wrap
        rowScroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        focus?.requestFocus()
        wrap.alpha = 0f
        wrap.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.EASE).start()
    }

    private fun hideOverlay(animate: Boolean) {
        val sheet = overlay ?: return
        overlay = null
        rowScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gw.isFocusable = true
        gpwDetail?.stop()
        gpwDetail = null
        val back = returnTo
        returnTo = null
        (back?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE } ?: lastDock ?: row.getChildAt(0))?.requestFocus()
        (sheet as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!animate) {
            root.removeView(sheet)
            return
        }
        sheet.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.EASE)
            .withEndAction { root.removeView(sheet) }
            .start()
    }

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

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        col.setPadding(uu(5.4f), uu(4f), uu(5.4f), 0)

        val title = TextView(this)
        title.text = "aplikacje"
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 3.4f)
        title.setTextColor(Pal.TEXT)
        title.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        title.includeFontPadding = false
        title.setPadding(uu(1.2f), 0, 0, 0)
        val hint = TextView(this)
        hint.text = "OK – uruchom   ·   przytrzymaj OK – dodaj / usuń z ulubionych ★   ·   Wstecz – zamknij"
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.1f)
        hint.setTextColor(Pal.TEXT_DIM)
        hint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        hint.setPadding(uu(1.2f), 0, 0, 0)
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = uu(0.8f)
        col.addView(title)
        col.addView(hint, hlp)

        val gridHost = PillHost(this, u)
        gridHost.setPadding(uu(1.0f), uu(1.0f), uu(1.0f), uu(1.0f))
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
            val holder = arrayOfNulls<TileView>(1)
            val item = makeTile(
                if (favs.contains(pkg)) "★ $label" else label, content, gridTileW,
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
                    holder[0]?.labelView?.text = if (nowFav) "★ $label" else label
                    buildRow(false, false)
                },
                false, gridHost
            )
            holder[0] = item
            val glp = GridLayout.LayoutParams()
            glp.setMargins(0, 0, uu(1.3f), uu(1.5f))
            grid.addView(item, glp)
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.setPadding(uu(1.2f), uu(2f), uu(1.2f), uu(4f))
        gridHost.addView(grid, WRAP, WRAP)
        scroll.addView(gridHost, WRAP, WRAP)
        val slp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        slp.topMargin = uu(1.2f)
        col.addView(scroll, slp)

        showOverlay(col, grid.getChildAt(0), hex(0xE60F1318L))
    }
}
