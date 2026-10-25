package pl.frutiger.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService

/** Spółki pokazywane w widżecie (bez .WA). */
val GPW_TICKERS = listOf("PKO", "PKN", "CDR")

/** Indeksy: klucz -> nazwa wyświetlana. */
val GPW_INDICES = listOf("WIG20" to "WIG20", "MWIG40" to "mWIG40", "SWIG80" to "sWIG80")

class Quote(val key: String, val price: Double, val prev: Double, val time: Long) {
    val changePct: Double get() = if (prev != 0.0) (price - prev) / prev * 100.0 else 0.0
}

class HttpFail(val code: Int) : IOException("HTTP $code")

interface QuoteSource {
    /** @return notowanie, null gdy symbol nieznany; rzuca IOException przy błędzie sieci / HTTP (np. 429). */
    fun fetch(key: String): Quote?
}

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) throw HttpFail(code)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Yahoo Finance: /v8/finance/chart/<symbol>?range=1d&interval=1d, symbole z sufiksem .WA. */
class YahooSource : QuoteSource {

    private fun candidates(key: String): List<String> = when (key) {
        "WIG20", "MWIG40", "SWIG80" -> listOf("$key.WA", "^$key")
        else -> listOf("$key.WA")
    }

    override fun fetch(key: String): Quote? {
        for (sym in candidates(key)) {
            val body = try {
                Net.get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(sym, "UTF-8") +
                        "?range=1d&interval=1d")
            } catch (e: HttpFail) {
                if (e.code == 404) continue
                throw e
            }
            val meta = JSONObject(body).optJSONObject("chart")?.optJSONArray("result")
                ?.optJSONObject(0)?.optJSONObject("meta") ?: continue
            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            var prev = meta.optDouble("chartPreviousClose", Double.NaN)
            if (prev.isNaN()) prev = meta.optDouble("previousClose", Double.NaN)
            if (price.isNaN() || prev.isNaN() || prev == 0.0) continue
            return Quote(key, price, prev, meta.optLong("regularMarketTime", 0L) * 1000L)
        }
        return null
    }
}

/**
 * Odświeżanie notowań: co 2 min w sesji (pon-pt 9:00-17:35 Europe/Warsaw), poza nią co 30 min.
 * Jeden wspólny executor aplikacji. Błąd/429 -> ostatnie dane z SharedPreferences z etykietą "nieaktualne".
 */
class GpwController(
    ctx: Context,
    private val worker: ExecutorService,
    private val ui: Handler,
    private val widget: GpwWidget,
    private val source: QuoteSource = YahooSource()
) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("gpw", Context.MODE_PRIVATE)
    private val tz = TimeZone.getTimeZone("Europe/Warsaw")
    private val keys: List<String> = GPW_INDICES.map { it.first } + GPW_TICKERS
    private val data = HashMap<String, Quote>()
    private var running = false
    private var busy = false
    private var stale = false
    private var lastOk = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            ui.postDelayed(this, if (inSession()) 120_000L else 1_800_000L)
        }
    }

    init {
        load()
        render()
    }

    fun start() {
        running = true
        ui.removeCallbacks(loop)
        val age = System.currentTimeMillis() - lastOk
        ui.postDelayed(loop, if (age > (if (inSession()) 120_000L else 1_800_000L)) 300L else 5_000L)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(loop)
    }

    private fun inSession(): Boolean {
        val c = Calendar.getInstance(tz)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m in 540..1055
    }

    private fun refresh() {
        if (busy) return
        busy = true
        worker.execute {
            val got = HashMap<String, Quote>()
            var failed = false
            for (k in keys) {
                try {
                    val q = source.fetch(k)
                    if (q != null) got[k] = q
                } catch (e: Exception) {
                    failed = true   // brak sieci / 429 - nie męczymy dalej
                    break
                }
            }
            ui.post {
                busy = false
                if (got.isNotEmpty()) {
                    data.putAll(got)
                    if (!failed) {
                        lastOk = System.currentTimeMillis()
                        stale = false
                    } else stale = true
                    save()
                } else if (failed) {
                    stale = true
                }
                render()
            }
        }
    }

    private fun load() {
        val s = prefs.getString("q", null) ?: return
        try {
            val o = JSONObject(s)
            for (k in keys) {
                val q = o.optJSONObject(k) ?: continue
                data[k] = Quote(k, q.getDouble("p"), q.getDouble("c"), q.getLong("t"))
            }
            lastOk = prefs.getLong("ok", 0L)
            stale = true
        } catch (e: Exception) {
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, q) in data) o.put(k, JSONObject().put("p", q.price).put("c", q.prev).put("t", q.time))
        prefs.edit().putString("q", o.toString()).putLong("ok", lastOk).apply()
    }

    private fun render() {
        if (data.isEmpty()) {
            widget.visibility = android.view.View.GONE
            return
        }
        var newest = 0L
        for (q in data.values) if (q.time > newest) newest = q.time
        widget.bind(data, newest, stale, Locale("pl", "PL"), tz)
        widget.visibility = android.view.View.VISIBLE
    }
}
