package pl.frutiger.tvhome

import android.os.Handler
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ExecutorService

/** Wspólny stan "informacji o domu": pogoda (null = brak sieci/danych, linia ukryta) i życzenia wg pory dnia. */
object HomeInfo {
    var weather: String? = null
        private set
    var onChange: (() -> Unit)? = null

    fun setWeather(w: String?) {
        if (w == weather) return
        weather = w
        onChange?.invoke()
    }

    private fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** Życzenie pod zegarem: do 11 "dzień dobry", 11-17 "miłego dnia", 17-22 "dobry wieczór", 22-5 "dobranoc". */
    fun wish(): String {
        val h = hour()
        return when {
            h < 5 -> "dobranoc"
            h < 11 -> "dzień dobry"
            h < 17 -> "miłego dnia"
            h < 22 -> "dobry wieczór"
            else -> "dobranoc"
        }
    }

    /** Życzenie w scenie bezczynności: rano "czas na kawę", po południu "smacznej kawy", wieczorem "spokojnego wieczoru". */
    fun idleWish(): String {
        val h = hour()
        return when {
            h < 5 -> "dobranoc"
            h < 11 -> "dzień dobry, czas na kawę"
            h < 17 -> "smacznej kawy"
            h < 22 -> "spokojnego wieczoru"
            else -> "dobranoc"
        }
    }
}

/**
 * Pogoda z Open-Meteo (HTTPS), stała lokalizacja Racibórz 50.09 N 18.22 E. Odświeżanie co 30 min,
 * błąd/brak sieci = brak linii (HomeInfo.weather = null). Sieć na wspólnym executorze aplikacji.
 */
class WeatherController(private val worker: ExecutorService, private val ui: Handler) {

    private val period = 30 * 60_000L
    private var running = false
    private var busy = false
    private var lastOk = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            ui.postDelayed(this, period)
        }
    }

    fun start() {
        running = true
        ui.removeCallbacks(loop)
        val age = System.currentTimeMillis() - lastOk
        ui.postDelayed(loop, if (lastOk == 0L) 400L else Math.max(400L, period - age))
    }

    fun stop() {
        running = false
        ui.removeCallbacks(loop)
    }

    private fun refresh() {
        if (busy) return
        busy = true
        worker.execute {
            val text = fetch()
            ui.post {
                busy = false
                if (text != null) lastOk = System.currentTimeMillis()
                HomeInfo.setWeather(text)
            }
        }
    }

    private fun fetch(): String? {
        return try {
            val body = Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=50.09&longitude=18.22" +
                        "&current=temperature_2m,weather_code&timezone=Europe%2FWarsaw"
            )
            val cur = JSONObject(body).optJSONObject("current") ?: return null
            val t = cur.optDouble("temperature_2m", Double.NaN)
            if (t.isNaN()) return null
            val r = Math.round(t).toInt()
            val temp = if (r < 0) "−" + (-r) else r.toString()
            val desc = describe(cur.optInt("weather_code", -1))
            if (desc != null) "$temp°, $desc" else "$temp°"
        } catch (e: Exception) {
            null
        }
    }

    /** Kody WMO -> krótki opis po polsku. */
    private fun describe(code: Int): String? = when (code) {
        0 -> "bezchmurnie"
        1 -> "przeważnie słonecznie"
        2 -> "częściowe zachmurzenie"
        3 -> "pochmurno"
        45, 48 -> "mgła"
        51, 53, 55, 56, 57 -> "mżawka"
        61, 63, 65 -> "deszcz"
        66, 67 -> "marznący deszcz"
        71, 73, 75, 77 -> "śnieg"
        80, 81, 82 -> "przelotny deszcz"
        85, 86 -> "przelotny śnieg"
        95 -> "burza"
        96, 99 -> "burza z gradem"
        else -> null
    }
}
