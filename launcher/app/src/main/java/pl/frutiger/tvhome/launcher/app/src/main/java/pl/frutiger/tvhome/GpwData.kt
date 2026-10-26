package pl.frutiger.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService

/** Tag logów całego pobierania i parsowania notowań. */
const val GPW_TAG = "GpwData"

/** Indeksy: klucz -> nazwa wyświetlana. */
val GPW_INDICES = listOf("WIG20" to "WIG20", "MWIG40" to "mWIG40", "SWIG80" to "sWIG80")

/** Spółki (Yahoo: TEN.WA = Ten Square Games, GPP.WA = Grupa Pracuj). */
val GPW_TICKERS = listOf("TEN", "GPP")

/** Karty widoku szczegółowego i pasek sceny bezczynności: indeksy, potem spółki (klucz -> nazwa). */
val GPW_CARDS: List<Pair<String, String>> = GPW_INDICES + GPW_TICKERS.map { it to it }

/** Odbiorca notowań (widżet, pasek sceny bezczynności). */
interface QuoteSink {
    fun bind(data: Map<String, Quote>, newest: Long, stale: Boolean, loc: java.util.Locale, tz: java.util.TimeZone)
}

/** 1,2 mln / 345 tys. */
fun fmtVolume(v: Double): String {
    val pl = Locale("pl", "PL")
    return when {
        v >= 1e6 -> String.format(pl, "%.1f mln", v / 1e6)
        v >= 1e3 -> String.format(pl, "%.0f tys.", v / 1e3)
        else -> String.format(pl, "%.0f", v)
    }
}

/** Obrót w mln zł. */
fun fmtTurnover(v: Double): String {
    val pl = Locale("pl", "PL")
    return String.format(pl, if (v < 1e7) "%.2f mln zł" else "%.1f mln zł", v / 1e6)
}

/** [series] i [ts] (ms, ta sama długość) to niepuste zamknięcia 5m z dnia; [ts] pusty = brak czasów (stary cache). */
class Quote(
    val key: String, val price: Double, val prev: Double, val time: Long,
    val series: FloatArray = FloatArray(0), val ts: LongArray = LongArray(0)
) {
    val changePct: Double get() = if (prev != 0.0) (price - prev) / prev * 100.0 else 0.0
}

enum class ChartRange { D1, D5 }

/** Wykres: czasy (ms) i wartości, bieżąca cena, poprzednie zamknięcie, otwarcie/max/min zakresu oraz oś czasu sesji. */
class ChartData(
    val key: String, val range: ChartRange,
    val t: LongArray, val v: FloatArray,
    val price: Double, val prev: Double,
    val open: Double, val high: Double, val low: Double,
    val axisStart: Long, val axisEnd: Long, val marketTime: Long,
    /** Wolumen w przedziale (ta sama długość co [t]; null z Yahoo = 0), wolumen i obrót z ostatniego dnia. */
    val vol: FloatArray, val volToday: Double, val turnover: Double
) {
    /** Sekcja wolumenu tylko, gdy są dane (pusty lub same zera = ukryta). */
    val hasVol: Boolean = vol.size == t.size && vol.any { it > 0f }
}

/** Sesja GPW wg zadanych reguł: pon-pt 9:00-17:35 Europe/Warsaw. */
object GpwClock {
    val zone: TimeZone = TimeZone.getTimeZone("Europe/Warsaw")

    fun inSession(): Boolean {
        val c = Calendar.getInstance(zone)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m in 540..1055
    }
}

class HttpFail(val code: Int) : IOException("HTTP $code")

interface QuoteSource {
    /** @return notowanie, null gdy symbol nieznany; rzuca IOException przy błędzie sieci / HTTP (np. 429). */
    fun fetch(key: String): Quote?

    /** Wykres dnia (D1: interval 1m, fallback 5m) lub 5 dni (D5: 15m). Rzuca IOException przy błędzie sieci / HTTP. */
    fun fetchChart(key: String, range: ChartRange): ChartData?
}

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun get(url: String): String {
        require(url.startsWith("https://")) { "tylko HTTPS" }
        Log.d(GPW_TAG, "GET $url")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) {
                Log.e(GPW_TAG, "HTTP $code <- $url")
                throw HttpFail(code)
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            Log.d(GPW_TAG, "HTTP $code len=${body.length} <- $url")
            return body
        } catch (e: HttpFail) {
            throw e
        } catch (e: IOException) {
            Log.e(GPW_TAG, "błąd sieci ${e.javaClass.simpleName}: ${e.message} <- $url")
            throw e
        } finally {
            c.disconnect()
        }
    }
}

/** Pusta / nieprawidłowa odpowiedź Yahoo (z krótkim powodem do pokazania w widoku). */
class EmptyData(msg: String) : IOException(msg)

/** Krótki powód porażki pobrania do napisu "brak danych: ...". */
fun gpwReason(e: Throwable): String = when (e) {
    is HttpFail -> if (e.code == 429) "HTTP 429 (limit zapytań)" else "HTTP " + e.code
    is java.net.SocketTimeoutException -> "timeout"
    is java.net.UnknownHostException, is java.net.ConnectException -> "brak sieci"
    is EmptyData -> e.message ?: "pusty wynik"
    is org.json.JSONException -> "błąd danych"
    else -> e.javaClass.simpleName
}

/**
 * Yahoo Finance: /v8/finance/chart/<symbol>?range=1d&interval=5m, symbole z sufiksem .WA
 * (WIG20.WA, MWIG40.WA, SWIG80.WA). Zmiana % z poprzedniego zamknięcia: meta.previousClose,
 * a gdy brak - meta.chartPreviousClose. Sparkline: indicators.quote[0].close (null-e pomijane).
 */
class YahooSource : QuoteSource {

    private val hosts = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")

    /** Pobranie wykresu Yahoo; przy 429/5xx/błędzie sieci próba na drugim hoście. 400/404 od razu wyżej. */
    private fun yahooGet(sym: String, rg: String, iv: String): String {
        var last: Exception? = null
        for (h in hosts) {
            try {
                return Net.get("https://" + h + "/v8/finance/chart/" + java.net.URLEncoder.encode(sym, "UTF-8") +
                        "?range=" + rg + "&interval=" + iv)
            } catch (e: HttpFail) {
                if (e.code == 400 || e.code == 404) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: EmptyData("brak odpowiedzi")
    }

    override fun fetch(key: String): Quote? {
        val sym = "$key.WA"
        val body = try {
            yahooGet(sym, "1d", "5m")
        } catch (e: HttpFail) {
            if (e.code == 404) {
                Log.e(GPW_TAG, "quote $sym: 404, symbol nieznany")
                return null
            }
            throw e
        }
        val res = try {
            JSONObject(body).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
        } catch (e: Exception) {
            Log.e(GPW_TAG, "quote $sym: JSON ${e.message}")
            null
        }
        if (res == null) {
            Log.e(GPW_TAG, "quote $sym: brak result")
            return null
        }
        val meta = res.optJSONObject("meta")
        if (meta == null) {
            Log.e(GPW_TAG, "quote $sym: brak meta")
            return null
        }
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        var prev = meta.optDouble("previousClose", Double.NaN)
        if (prev.isNaN() || prev == 0.0) prev = meta.optDouble("chartPreviousClose", Double.NaN)
        if (price.isNaN() || prev.isNaN() || prev == 0.0) {
            Log.e(GPW_TAG, "quote $sym: brak ceny/zamknięcia price=$price prev=$prev")
            return null
        }

        val tsA = res.optJSONArray("timestamp")
        val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val n = if (closes != null && tsA != null) Math.min(closes.length(), tsA.length()) else 0
        val tv = FloatArray(n)
        val tt = LongArray(n)
        var m = 0
        for (i in 0 until n) {
            if (closes!!.isNull(i) || tsA!!.isNull(i)) continue          // null pomijamy, nie zerujemy
            val v = closes.optDouble(i, Double.NaN)
            if (v.isNaN()) continue
            tv[m] = v.toFloat()
            tt[m] = tsA.optLong(i) * 1000L
            m++
        }
        Log.d(GPW_TAG, "quote $sym: price=$price prev=$prev punkty=$m/$n")
        val step = Math.max(1, (m + 59) / 60)
        val cnt = (m + step - 1) / step
        val extra = if (m > 0 && (m - 1) % step != 0) 1 else 0
        val ov = FloatArray(cnt + extra)
        val ot = LongArray(cnt + extra)
        var k = 0
        var j = 0
        while (j < m) { ov[k] = tv[j]; ot[k] = tt[j]; k++; j += step }
        if (extra == 1) { ov[k] = tv[m - 1]; ot[k] = tt[m - 1] }
        return Quote(key, price, prev, meta.optLong("regularMarketTime", 0L) * 1000L, ov, ot)
    }

    override fun fetchChart(key: String, range: ChartRange): ChartData? {
        val tries = if (range == ChartRange.D1) listOf("1d" to "1m", "1d" to "5m") else listOf("5d" to "15m")
        var best: ChartData? = null
        var fail: Exception? = null
        for ((rg, iv) in tries) {
            try {
                val cd = chart(key, rg, iv, range)
                if (cd.t.size >= 2) return cd
                if (best == null) best = cd
            } catch (e: Exception) {
                fail = e
                Log.e(GPW_TAG, "chart $key $rg/$iv: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (best != null) return best
        throw fail ?: EmptyData("pusty wynik")
    }

    private fun chart(key: String, rg: String, iv: String, range: ChartRange): ChartData =
        parseChart(yahooGet("$key.WA", rg, iv), key, rg, iv, range)

    /**
     * Czysty parser odpowiedzi Yahoo (bez sieci, testowalny na JVM): odporny na brak indicators/timestamp
     * i na null w timestamp/close/volume. Rzuca [EmptyData] z krótkim powodem.
     */
    fun parseChart(body: String, key: String, rg: String, iv: String, range: ChartRange): ChartData {
        val tag = "chart $key $rg/$iv"
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(GPW_TAG, "$tag: JSON ${e.message}")
            throw EmptyData("błąd parsowania")
        }
        val chartObj = root.optJSONObject("chart")
        val err = chartObj?.optJSONObject("error")
        if (err != null) {
            Log.e(GPW_TAG, "$tag: błąd Yahoo $err")
            throw EmptyData("Yahoo: " + err.optString("code", "błąd"))
        }
        val res = chartObj?.optJSONArray("result")?.optJSONObject(0)
        if (res == null) {
            Log.e(GPW_TAG, "$tag: brak result")
            throw EmptyData("pusty wynik")
        }
        val meta = res.optJSONObject("meta")
        val ts = res.optJSONArray("timestamp")
        val q = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val cl = q?.optJSONArray("close")
        val op = q?.optJSONArray("open")
        val hi = q?.optJSONArray("high")
        val lo = q?.optJSONArray("low")
        val vo = q?.optJSONArray("volume")
        Log.d(GPW_TAG, "$tag: meta=${meta != null} timestamp=${ts?.length() ?: -1} close=${cl?.length() ?: -1} volume=${vo?.length() ?: -1}")
        val bucket = if (iv == "30s") 30L else if (iv == "1m") 60L else if (range == ChartRange.D1) 300L else 900L
        val tt = ArrayList<Long>()
        val vv = ArrayList<Float>()
        val bv = ArrayList<Float>()
        var curDay = Long.MIN_VALUE
        var dayVol = 0.0
        var dayTurn = 0.0
        var openV = Double.NaN
        var hiV = Double.NEGATIVE_INFINITY
        var loV = Double.POSITIVE_INFINITY
        var lastB = Long.MIN_VALUE
        var skipped = 0
        val total = if (ts != null && cl != null) ts.length() else 0
        for (i in 0 until total) {
            if (ts!!.isNull(i) || cl!!.isNull(i)) { skipped++; continue }
            val c = cl.optDouble(i, Double.NaN)
            if (c.isNaN()) { skipped++; continue }
            val sec = ts.optLong(i)
            if (openV.isNaN() && op != null && !op.isNull(i)) openV = op.optDouble(i, Double.NaN)
            if (hi != null && !hi.isNull(i)) hiV = Math.max(hiV, hi.optDouble(i, Double.NEGATIVE_INFINITY))
            if (lo != null && !lo.isNull(i)) loV = Math.min(loV, lo.optDouble(i, Double.POSITIVE_INFINITY))
            var rv = if (vo != null && !vo.isNull(i)) vo.optDouble(i, 0.0) else 0.0
            if (rv.isNaN() || rv < 0.0) rv = 0.0
            val dayKey = (sec * 1000L + GpwClock.zone.getOffset(sec * 1000L)) / 86_400_000L
            if (dayKey != curDay) { curDay = dayKey; dayVol = 0.0; dayTurn = 0.0 }
            dayVol += rv
            dayTurn += rv * c
            val b = sec / bucket
            if (b == lastB && tt.isNotEmpty()) {
                tt[tt.size - 1] = sec * 1000L
                vv[vv.size - 1] = c.toFloat()
                bv[bv.size - 1] = bv[bv.size - 1] + rv.toFloat()
            } else {
                tt.add(sec * 1000L)
                vv.add(c.toFloat())
                bv.add(rv.toFloat())
                lastB = b
            }
        }
        Log.d(GPW_TAG, "$tag: punkty=${tt.size} pominięte(null)=$skipped")
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (x in vv) { if (x < mn) mn = x; if (x > mx) mx = x }
        val last = if (vv.isNotEmpty()) vv[vv.size - 1].toDouble() else Double.NaN
        var price = meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN
        if (price.isNaN()) price = last
        var prev = if (range == ChartRange.D1) (meta?.optDouble("previousClose", Double.NaN) ?: Double.NaN) else Double.NaN
        if (prev.isNaN() || prev == 0.0) prev = meta?.optDouble("chartPreviousClose", Double.NaN) ?: Double.NaN
        if ((prev.isNaN() || prev == 0.0) && vv.isNotEmpty()) prev = vv[0].toDouble()
        if (price.isNaN() || prev.isNaN() || prev == 0.0) {
            Log.e(GPW_TAG, "$tag: brak ceny/zamknięcia price=$price prev=$prev")
            throw EmptyData("pusty wynik")
        }
        val mt = (meta?.optLong("regularMarketTime", 0L) ?: 0L) * 1000L
        if (tt.size < 2) {
            // brak serii: pokazujemy samą cenę i statystyki z meta, wykres dostaje komunikat
            val dh = meta?.optDouble("regularMarketDayHigh", Double.NaN) ?: Double.NaN
            val dl = meta?.optDouble("regularMarketDayLow", Double.NaN) ?: Double.NaN
            return ChartData(key, range, LongArray(0), FloatArray(0), price, prev, price,
                if (dh.isNaN()) price else dh, if (dl.isNaN()) price else dl, 0L, 0L, mt, FloatArray(0), 0.0, 0.0)
        }
        if (openV.isNaN()) openV = vv[0].toDouble()
        if (hiV == Double.NEGATIVE_INFINITY) hiV = mx.toDouble()
        if (loV == Double.POSITIVE_INFINITY) loV = mn.toDouble()
        var a0 = tt[0]
        var a1 = tt[tt.size - 1]
        if (range == ChartRange.D1) {
            val cal = Calendar.getInstance(GpwClock.zone)
            cal.timeInMillis = tt[tt.size - 1]
            cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            a0 = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 17); cal.set(Calendar.MINUTE, 35)
            a1 = cal.timeInMillis
        }
        return ChartData(key, range, tt.toLongArray(), vv.toFloatArray(), price, prev, openV, hiV, loV, a0, a1, mt,
            bv.toFloatArray(), dayVol, dayTurn)
    }
}

/**
 * Odświeżanie notowań (indeksy + TEN, GPP): aktywny 15s (sesja 9:00-17:35), idle 60s, poza sesją 1x + "ostatnia sesja".
 * Jeden wspólny executor aplikacji. Błąd/429 -> ostatnie dane z SharedPreferences z etykietą "nieaktualne".
 */
class GpwController(
    ctx: Context,
    private val worker: ExecutorService,
    private val ui: Handler,
    private val sinks: List<QuoteSink>,
    private val source: QuoteSource = YahooSource()
) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("gpw", Context.MODE_PRIVATE)
    private val tz = TimeZone.getTimeZone("Europe/Warsaw")
    private val keys: List<String> = GPW_CARDS.map { it.first }
    private val activePeriod = 15_000L
    private val idlePeriod = 60_000L
    private val data = HashMap<String, Quote>()
    private var running = false
    private var busy = false
    private var stale = false
    private var lastOk = 0L
    private var refreshedOnce = false

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            val inSession = GpwClock.inSession()
            val nextPeriod = if (inSession) activePeriod else idlePeriod
            ui.postDelayed(this, nextPeriod)
        }
    }

    init {
        load()
        render()
    }

    fun start() {
        running = true
        ui.removeCallbacks(loop)
        refreshedOnce = false
        val inSession = GpwClock.inSession()
        val period = if (inSession) activePeriod else idlePeriod
        val age = System.currentTimeMillis() - lastOk
        ui.postDelayed(loop, Math.max(300L, period - age))
    }

    fun stop() {
        running = false
        ui.removeCallbacks(loop)
    }

    private fun refresh() {
        if (busy) return
        val inSession = GpwClock.inSession()
        if (!inSession && refreshedOnce) return
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
                        refreshedOnce = true
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
                val sa = q.optJSONArray("s")
                val ser = FloatArray(sa?.length() ?: 0) { j -> sa!!.optDouble(j).toFloat() }
                val xa = q.optJSONArray("x")
                val tsl = if (xa != null && xa.length() == ser.size) LongArray(ser.size) { j -> xa.optLong(j) * 1000L } else LongArray(0)
                data[k] = Quote(k, q.getDouble("p"), q.getDouble("c"), q.getLong("t"), ser, tsl)
            }
            lastOk = prefs.getLong("ok", 0L)
            stale = true
        } catch (e: Exception) {
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, q) in data) {
            val sa = org.json.JSONArray()
            for (v in q.series) sa.put(Math.round(v * 100f) / 100.0)
            val xa = org.json.JSONArray()
            if (q.ts.size == q.series.size) for (t in q.ts) xa.put(t / 1000L)
            o.put(k, JSONObject().put("p", q.price).put("c", q.prev).put("t", q.time).put("s", sa).put("x", xa))
        }
        prefs.edit().putString("q", o.toString()).putLong("ok", lastOk).apply()
    }

    private fun render() {
        var newest = 0L
        for (q in data.values) if (q.time > newest) newest = q.time
        val loc = Locale("pl", "PL")
        for (i in sinks.indices) sinks[i].bind(data, newest, stale, loc, tz)
    }
}
