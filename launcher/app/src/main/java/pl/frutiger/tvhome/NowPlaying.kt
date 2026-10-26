package pl.frutiger.tvhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

/**
 * Pusty listener powiadomień: samo posiadanie dostępu do powiadomień pozwala czytać aktywne sesje multimedialne
 * (MediaSessionManager.getActiveSessions). Dostęp nadajemy komendą adb (patrz CO-SPRAWDZIC-NA-TV.txt).
 */
class MediaListener : NotificationListenerService()

/** Zaokrąglona okładka (BitmapShader, bez alokacji w onDraw); bez okładki - ciemny kafelek z nutą. */
class CoverView(ctx: Context, private val u: Float) : View(ctx) {
    private val rect = RectF()
    private val m = Matrix()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bmp: Bitmap? = null
    private val r = u * 0.7f

    init {
        ph.color = Pal.TILE
        note.color = Pal.TEXT_DIM
        note.textAlign = Paint.Align.CENTER
        note.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    fun set(b: Bitmap?) {
        bmp = b
        p.shader = if (b != null) BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) else null
        updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        note.textSize = h * 0.55f
        updateMatrix()
    }

    private fun updateMatrix() {
        val b = bmp ?: return
        if (width <= 0) return
        m.setScale(width.toFloat() / b.width, height.toFloat() / b.height)
        p.shader?.setLocalMatrix(m)
    }

    override fun onDraw(c: Canvas) {
        if (bmp != null) {
            c.drawRoundRect(rect, r, r, p)
        } else {
            c.drawRoundRect(rect, r, r, ph)
            c.drawText("♪", width / 2f, height * 0.68f, note)
        }
    }
}

/**
 * Szklany panel "teraz odtwarzane" pod widżetem GPW: okładka + tytuł + wykonawca, dopasowany do zawartości
 * (max 35% x 35% ekranu, teksty z wielokropkiem). Fokusowalny (OK = otwórz aplikację), zaznaczenie jak reszta UI.
 */
class NowPlayingPanel(ctx: Context, private val u: Float) :
    FrostPanel(ctx, u * 1.5f, hex(0x4D1B2027L), hex(0x2EFFFFFFL), Math.max(1f, u * 0.055f)) {

    private val sfx = SelFx(this, u)
    private val dimLayer = FrameLayout(ctx)
    private val inner = LinearLayout(ctx)
    private val cover = CoverView(ctx, u)
    private val title = TextView(ctx)
    private val artist = TextView(ctx)
    var pkg: String? = null
        private set
    var onOpen: ((String) -> Unit)? = null
    var onShown: (() -> Unit)? = null
    private var shown = false

    init {
        fx = sfx
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        setOnClickListener { pkg?.let { onOpen?.invoke(it) } }
        maxWFrac = 0.35f
        maxHFrac = 0.35f
        visibility = View.GONE
        alpha = 0f

        inner.orientation = LinearLayout.HORIZONTAL
        inner.gravity = Gravity.CENTER_VERTICAL
        inner.setPadding((u * 1.2f).toInt(), (u * 1.1f).toInt(), (u * 1.6f).toInt(), (u * 1.1f).toInt())
        val cs = (u * 4.4f).toInt()
        inner.addView(cover, LinearLayout.LayoutParams(cs, cs))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.45f)
        title.setTextColor(Pal.TEXT)
        title.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        artist.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.15f)
        artist.setTextColor(Pal.TEXT_DIM)
        artist.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        for (t in arrayOf(title, artist)) {
            t.maxLines = 1
            t.isSingleLine = true
            t.ellipsize = TextUtils.TruncateAt.END
            t.includeFontPadding = false
            t.maxWidth = (u * 17f).toInt()
        }
        col.addView(title, LinearLayout.LayoutParams(-2, -2))
        val alp = LinearLayout.LayoutParams(-2, -2)
        alp.topMargin = (u * 0.5f).toInt()
        col.addView(artist, alp)
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = (u * 1.3f).toInt()
        inner.addView(col, clp)

        dimLayer.alpha = Sel.DIM
        dimLayer.addView(inner, FrameLayout.LayoutParams(-2, -2))
        addView(dimLayer, FrameLayout.LayoutParams(-2, -2))

        sfx.onStep = SelStep { s ->
            val z = 1f + (Sel.SCALE - 1f) * s
            scaleX = z
            scaleY = z
            dimLayer.alpha = Sel.DIM + (1f - Sel.DIM) * s
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pivotX = w.toFloat()
        pivotY = 0f
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        sfx.set(gainFocus)
    }

    override fun onDetachedFromWindow() {
        sfx.cancel()
        super.onDetachedFromWindow()
    }

    private fun setContent(t: String, a: String, art: Bitmap?, p: String) {
        title.text = t
        artist.text = a
        artist.visibility = if (a.isBlank()) View.GONE else View.VISIBLE
        cover.set(art)
        pkg = p
    }

    /** Nowy utwór: płynna zamiana zawartości (fade), a gdy panel był ukryty - pojawienie się panelu. */
    fun show(t: String, a: String, art: Bitmap?, p: String) {
        if (!shown) {
            shown = true
            setContent(t, a, art, p)
            animate().cancel()
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(Sel.FADE_MS).setInterpolator(Motion.EASE).start()
            onShown?.invoke()
            return
        }
        inner.animate().cancel()
        inner.animate().alpha(0f).setDuration(Sel.LABEL_MS).setInterpolator(Motion.EASE).withEndAction {
            setContent(t, a, art, p)
            inner.animate().alpha(1f).setDuration(Sel.FADE_MS).setInterpolator(Motion.EASE).start()
        }.start()
    }

    /** Brak odtwarzania: panel znika (po wygaszeniu ukryty całkiem, więc nie zajmuje miejsca ani fokusu). */
    fun hide() {
        if (!shown) return
        shown = false
        inner.animate().cancel()
        inner.alpha = 1f
        animate().cancel()
        animate().alpha(0f).setDuration(Sel.FADE_MS).setInterpolator(Motion.EASE).withEndAction {
            if (!shown) {
                visibility = View.GONE
                pkg = null
                onShown?.invoke()
            }
        }.start()
    }

    val active: Boolean get() = shown
}

/**
 * Wybiera sesję w stanie PLAYING (preferuje Spotify) z MediaSessionManager i podaje panelowi tytuł, wykonawcę
 * i okładkę. Brak dostępu do powiadomień = brak panelu (ponowna próba co 20 s, żeby złapać nadanie dostępu przez adb).
 * Okładka jest skalowana raz, na wspólnym executorze.
 */
class NowPlayingController(
    private val ctx: Context,
    private val ui: Handler,
    private val worker: ExecutorService,
    private val panel: NowPlayingPanel,
    private val coverPx: Int
) {
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, MediaListener::class.java)
    private var running = false
    private var registered = false
    private var ctrls: List<MediaController> = emptyList()
    private var shownKey: String? = null
    private var token = 0

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onSessionDestroyed() = evaluate()
    }

    private val hideTask = Runnable {
        shownKey = null
        token++
        panel.hide()
    }

    private val retryTask = object : Runnable {
        override fun run() {
            if (running) connect()
        }
    }

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(hideTask)
        ui.removeCallbacks(retryTask)
        token++
        if (registered) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
            }
            registered = false
        }
        detach()
    }

    private fun connect() {
        ui.removeCallbacks(retryTask)
        try {
            if (!registered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, comp, ui)
                registered = true
            }
            attach(msm.getActiveSessions(comp))
        } catch (e: SecurityException) {
            // brak dostępu do powiadomień: panel ukryty, próbujemy ponownie
            registered = false
            attach(null)
            ui.postDelayed(retryTask, 20_000L)
        } catch (e: Exception) {
            attach(null)
            ui.postDelayed(retryTask, 20_000L)
        }
    }

    private fun detach() {
        for (c in ctrls) {
            try {
                c.unregisterCallback(cb)
            } catch (e: Exception) {
            }
        }
        ctrls = emptyList()
    }

    private fun attach(list: List<MediaController>?) {
        detach()
        val l = ArrayList<MediaController>(list ?: emptyList())
        for (c in l) {
            try {
                c.registerCallback(cb, ui)
            } catch (e: Exception) {
            }
        }
        ctrls = l
        evaluate()
    }

    private fun evaluate() {
        if (!running) return
        var best: MediaController? = null
        for (c in ctrls) {
            if (c.playbackState?.state != PlaybackState.STATE_PLAYING) continue
            if (c.packageName.startsWith("com.spotify")) {
                best = c
                break
            }
            if (best == null) best = c
        }
        val c = best
        val meta = c?.metadata
        val t = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || t.isNullOrBlank()) {
            // z lekkim opóźnieniem: przy zmianie utworu sesja bywa na chwilę nieaktywna
            ui.removeCallbacks(hideTask)
            ui.postDelayed(hideTask, 1500L)
            return
        }
        ui.removeCallbacks(hideTask)
        if (meta == null) return
        val a = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // gdy brak bitmapy (np. Spotify TV): okładka z URI (https lub content), pobierana poza wątkiem UI
        val artUri: String? = if (art != null) null
        else meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val pkg = c.packageName
        val key = pkg + "\u0001" + t + "\u0001" + a + "\u0001" + (if (art != null) System.identityHashCode(art) else 0) + "\u0001" + (artUri ?: "")
        if (key == shownKey && panel.active) return
        shownKey = key
        val my = ++token
        worker.execute {
            val cov = if (art != null) square(art, coverPx) else if (artUri != null) fromUri(artUri) else null
            ui.post {
                if (my == token && running) panel.show(t, a, cov, pkg)
            }
        }
    }

    /** Okładka z URI: tylko https lub content://, limit 3 MB, dekodowanie z próbkowaniem (raz, na executorze). */
    private fun fromUri(uri: String): Bitmap? = try {
        val bytes: ByteArray? = when {
            uri.startsWith("https://") -> {
                val con = URL(uri).openConnection() as HttpURLConnection
                try {
                    con.connectTimeout = 6000
                    con.readTimeout = 6000
                    con.setRequestProperty("User-Agent", Net.UA)
                    if (con.responseCode != 200) null else con.inputStream.use { readCapped(it) }
                } finally {
                    con.disconnect()
                }
            }
            uri.startsWith("content://") -> ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { readCapped(it) }
            else -> null
        }
        if (bytes == null) null else {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= coverPx && o.outHeight / (sample * 2) >= coverPx) sample *= 2
            val d = BitmapFactory.Options()
            d.inSampleSize = sample
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)
            if (raw == null) null else square(raw, coverPx)
        }
    } catch (e: Throwable) {
        null
    }

    private fun readCapped(i: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            total += n
            if (total > 3_000_000) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Kadr do kwadratu i zmniejszenie do rozmiaru panelu (raz, poza wątkiem UI). */
    private fun square(src: Bitmap, px: Int): Bitmap? = try {
        val s = Math.min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val out = Bitmap.createScaledBitmap(sq, px, px, true)
        if (sq !== src && sq !== out) sq.recycle()
        out
    } catch (e: Throwable) {
        null
    }
}
