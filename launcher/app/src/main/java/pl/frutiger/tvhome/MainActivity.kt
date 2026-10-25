package pl.frutiger.tvhome

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Frutiger Home 0.1 alpha - minimalistyczny ekran główny: tapeta, zegar, dok ulubionych aplikacji.
 * Wszystko budowane w kodzie (bez zewnętrznych bibliotek), żeby było lekkie i szybkie.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var wallA: ImageView
    private lateinit var wallB: ImageView
    private lateinit var dock: LinearLayout
    private var aOnTop = false
    private var overlay: FrameLayout? = null
    private var sources: List<String> = emptyList()
    private var srcIndex = 0
    private val rotateMs = 180_000L

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- cykl życia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", MODE_PRIVATE)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B3A5B"))
        root.clipChildren = false

        val wallLayer = FrameLayout(this)
        wallA = ImageView(this)
        wallB = ImageView(this)
        wallA.scaleType = ImageView.ScaleType.CENTER_CROP
        wallB.scaleType = ImageView.ScaleType.CENTER_CROP
        wallB.alpha = 0f
        wallLayer.addView(wallA, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wallLayer.addView(wallB, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(wallLayer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        root.addView(buildClock())

        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.clipChildren = false
        dock.clipToPadding = false
        dock.setPadding(dp(20), dp(14), dp(20), dp(10))
        dock.background = glass(Color.parseColor("#66FFFFFF"), Color.parseColor("#22FFFFFF"), 34)
        val dockLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        dockLp.bottomMargin = dp(36)
        root.addView(dock, dockLp)

        setContentView(root)
        loadSources()
        buildDock(true)
    }

    override fun onResume() {
        super.onResume()
        loadSources()
        ui.removeCallbacks(rotateTask)
        ui.post(rotateTask)
    }

    override fun onPause() {
        ui.removeCallbacks(rotateTask)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideAllApps()
        buildDock(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) hideAllApps()
        // na ekranie głównym przycisk Wstecz nic nie robi
    }

    // ---------------------------------------------------------------- zegar

    private fun buildClock(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val time = TextClock(this)
        time.format24Hour = "HH:mm"
        time.format12Hour = "HH:mm"
        time.textSize = 56f
        time.setTextColor(Color.WHITE)
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        time.setShadowLayer(10f, 0f, 3f, Color.parseColor("#66000000"))

        val date = TextClock(this)
        date.format24Hour = "EEEE, d MMMM"
        date.format12Hour = "EEEE, d MMMM"
        date.textSize = 18f
        date.setTextColor(Color.parseColor("#EEFFFFFF"))
        date.setShadowLayer(8f, 0f, 2f, Color.parseColor("#66000000"))

        box.addView(time)
        box.addView(date)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        lp.leftMargin = dp(56)
        lp.topMargin = dp(32)
        box.layoutParams = lp
        return box
    }

    // ---------------------------------------------------------------- tapety

    private fun loadSources() {
        val scenes = listOf("scene:0", "scene:1", "scene:2")
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
            val bmp: Bitmap? = try {
                if (src.startsWith("scene:")) Scenes.render(src.removePrefix("scene:").toInt(), 1280, 720)
                else decodeFile(File(src))
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) ui.post { showWallpaper(bmp) }
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

    private fun showWallpaper(bmp: Bitmap) {
        val incoming = if (aOnTop) wallB else wallA
        val outgoing = if (aOnTop) wallA else wallB
        aOnTop = !aOnTop
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.bringToFront()
        incoming.animate().alpha(1f).setDuration(1500).withEndAction {
            outgoing.setImageDrawable(null)
        }.start()
    }

    // ---------------------------------------------------------------- dok

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

    private fun buildDock(focusFirst: Boolean) {
        dock.removeAllViews()
        for (pkg in favorites().take(8)) {
            val intent = launchIntent(pkg) ?: continue
            val info = try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (e: Exception) {
                continue
            }
            val icon = ImageView(this)
            icon.setImageDrawable(packageManager.getApplicationIcon(info))
            val label = packageManager.getApplicationLabel(info).toString()
            dock.addView(makeItem(label, icon, { launch(intent) }, null))
        }
        dock.addView(makeItem("Aplikacje", gridIcon(), { showAllApps() }, null))
        if (focusFirst) dock.getChildAt(0)?.requestFocus()
    }

    private fun gridIcon(): View {
        val box = FrameLayout(this)
        val grid = GridLayout(this)
        grid.columnCount = 3
        grid.rowCount = 3
        repeat(9) {
            val dot = View(this)
            dot.background = glass(Color.parseColor("#FFFFFFFF"), Color.parseColor("#CCBFEFFF"), 4, 0)
            val lp = GridLayout.LayoutParams()
            lp.width = dp(14)
            lp.height = dp(14)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            grid.addView(dot, lp)
        }
        box.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        return box
    }

    private fun makeItem(label: String, icon: View, onClick: () -> Unit, onLong: (() -> Unit)?): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER_HORIZONTAL
        item.isFocusable = true
        item.isClickable = true
        item.setPadding(dp(10), dp(8), dp(10), dp(6))

        val tile = FrameLayout(this)
        tile.background = glass(Color.parseColor("#99FFFFFF"), Color.parseColor("#33BFEFFF"), 20)
        tile.addView(icon, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        item.addView(tile, LinearLayout.LayoutParams(dp(80), dp(80)))

        val text = TextView(this)
        text.text = label
        text.textSize = 12f
        text.setTextColor(Color.WHITE)
        text.maxLines = 1
        text.ellipsize = android.text.TextUtils.TruncateAt.END
        text.gravity = Gravity.CENTER
        text.setShadowLayer(6f, 0f, 2f, Color.parseColor("#99000000"))
        val tlp = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(6)
        item.addView(text, tlp)
        item.tag = text

        item.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.15f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            v.background = if (has) glass(Color.parseColor("#66FFFFFF"), Color.parseColor("#22FFFFFF"), 26) else null
        }
        item.setOnClickListener { onClick() }
        if (onLong != null) item.setOnLongClickListener { onLong(); true }
        return item
    }

    private fun glass(top: Int, bottom: Int, radiusDp: Int, strokeDp: Int = 2): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) d.setStroke(dp(strokeDp), Color.parseColor("#99FFFFFF"))
        return d
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

        val ov = FrameLayout(this)
        ov.setBackgroundColor(Color.parseColor("#E6082B40"))
        ov.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(48), dp(32), dp(48), dp(16))

        val title = TextView(this)
        title.text = "Wszystkie aplikacje"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        val hint = TextView(this)
        hint.text = "OK = uruchom  •  przytrzymaj OK = dodaj / usuń z doku (★)  •  Wstecz = zamknij"
        hint.textSize = 13f
        hint.setTextColor(Color.parseColor("#CCFFFFFF"))
        col.addView(title)
        col.addView(hint)

        val grid = GridLayout(this)
        grid.columnCount = 6
        grid.clipChildren = false
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val icon = ImageView(this)
            icon.setImageDrawable(ri.loadIcon(pm))
            val label = ri.loadLabel(pm).toString()
            val holder = arrayOfNulls<LinearLayout>(1)
            val item = makeItem(
                if (favs.contains(pkg)) "★ $label" else label, icon,
                { launchIntent(pkg)?.let { launch(it) } },
                {
                    val f = favorites()
                    val nowFav: Boolean
                    if (f.contains(pkg)) { f.remove(pkg); nowFav = false } else { f.add(pkg); nowFav = true }
                    prefs.edit().putString("fav", f.joinToString(",")).apply()
                    (holder[0]?.tag as? TextView)?.text = if (nowFav) "★ $label" else label
                    buildDock(false)
                }
            )
            holder[0] = item
            grid.addView(item)
        }
        val scroll = ScrollView(this)
        scroll.addView(grid)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = dp(16)
        col.addView(scroll, lp)

        ov.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(ov, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay = ov
        grid.getChildAt(0)?.requestFocus()
    }

    private fun hideAllApps() {
        val ov = overlay ?: return
        root.removeView(ov)
        overlay = null
        dock.getChildAt(dock.childCount - 1)?.requestFocus()
    }
}
