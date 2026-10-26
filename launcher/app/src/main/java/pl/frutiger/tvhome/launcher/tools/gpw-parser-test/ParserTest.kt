// Test parsera Yahoo na JVM (bez Androida). Użycie: patrz run.sh
import pl.frutiger.tvhome.ChartData
import pl.frutiger.tvhome.ChartRange
import pl.frutiger.tvhome.EmptyData
import pl.frutiger.tvhome.GpwClock
import pl.frutiger.tvhome.YahooSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = GpwClock.zone }

private fun describe(name: String, key: String, rg: String, iv: String, range: ChartRange, body: String) {
    println("=== $name ($key $rg/$iv, ${body.length} B)")
    val cd: ChartData = try {
        YahooSource().parseChart(body, key, rg, iv, range)
    } catch (e: Exception) {
        println("BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    val n = cd.t.size
    val nz = cd.vol.count { it > 0f }
    println("punkty=$n  czas=" + (if (n > 0) fmt.format(Date(cd.t[0])) + " .. " + fmt.format(Date(cd.t[n - 1])) else "-"))
    println("ostatnia cena(meta)=${cd.price}  ostatnie close=" + (if (n > 0) cd.v[n - 1] else Float.NaN) + "  poprzednie zamknięcie=${cd.prev}")
    println("otwarcie=${cd.open} max=${cd.high} min=${cd.low}  wolumeny niezerowe=$nz  hasVol=${cd.hasVol}  wolumen dnia=${cd.volToday}")
    if (n >= 2) {
        // ta sama formuła osi X co w ChartView.setData
        val span = (cd.axisEnd - cd.axisStart).toFloat()
        var bad = 0
        var xmin = 9f
        var xmax = -9f
        for (i in 0 until n) {
            val x = if (range == ChartRange.D1 && span > 0f) ((cd.t[i] - cd.axisStart) / span) else i / (n - 1f)
            if (x < 0f || x > 1f) bad++
            if (x < xmin) xmin = x
            if (x > xmax) xmax = x
        }
        var mono = true
        for (i in 1 until n) if (cd.t[i] <= cd.t[i - 1]) mono = false
        println("oś: ${fmt.format(Date(cd.axisStart))} .. ${fmt.format(Date(cd.axisEnd))}  x∈[$xmin, $xmax]  poza[0,1]=$bad  czasy rosnące=$mono")
    } else println("UWAGA: seria < 2 punktów (widok pokaże 'brak danych')")
}

private var fails = 0
private fun expect(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

private fun edge() {
    println("=== przypadki brzegowe")
    val ys = YahooSource()
    fun parse(b: String) = ys.parseChart(b, "TST", "1d", "1m", ChartRange.D1)
    val meta = """"meta":{"regularMarketPrice":101.5,"chartPreviousClose":100.0,"regularMarketTime":1760000000}"""
    // brak indicators
    parse("""{"chart":{"result":[{$meta,"timestamp":[1760000000,1760000060]}],"error":null}}""").also {
        expect("brak indicators -> dane z meta, 0 punktów", it.t.isEmpty() && it.price == 101.5 && it.prev == 100.0)
    }
    // brak timestamp
    parse("""{"chart":{"result":[{$meta,"indicators":{"quote":[{"close":[1,2]}]}}],"error":null}}""").also {
        expect("brak timestamp -> 0 punktów, bez wyjątku", it.t.isEmpty())
    }
    // null w timestamp / close / volume
    parse("""{"chart":{"result":[{$meta,"timestamp":[1760000000,null,1760000120,1760000180],
        "indicators":{"quote":[{"close":[100.5,101.0,null,102.0],"volume":[null,5,0,7],"open":[null,1,2,3],"high":[1,2,3,4],"low":[1,2,3,4]}]}}],"error":null}}""").also {
        expect("null w ts/close/volume -> 2 punkty (reszta pominięta)", it.t.size == 2 && it.v[0] == 100.5f && it.v[1] == 102.0f)
        expect("volume null/0 -> sekcja ukryta tylko gdy same zera", it.hasVol)
    }
    // same zera w wolumenie
    parse("""{"chart":{"result":[{$meta,"timestamp":[1760000000,1760000060],
        "indicators":{"quote":[{"close":[100.5,101.0],"volume":[0,0]}]}}],"error":null}}""").also {
        expect("wolumen same zera -> hasVol=false", !it.hasVol && it.t.size == 2)
    }
    // błąd Yahoo
    try { parse("""{"chart":{"result":null,"error":{"code":"Not Found","description":"x"}}}"""); expect("błąd Yahoo -> EmptyData", false) }
    catch (e: EmptyData) { expect("błąd Yahoo -> EmptyData: " + e.message, true) }
    // pusty result
    try { parse("""{"chart":{"result":[],"error":null}}"""); expect("pusty result -> EmptyData", false) }
    catch (e: EmptyData) { expect("pusty result -> EmptyData: " + e.message, true) }
    // śmieci
    try { parse("<html>429</html>"); expect("nie-JSON -> EmptyData", false) }
    catch (e: EmptyData) { expect("nie-JSON -> EmptyData: " + e.message, true) }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        // argumenty: plik [klucz zakres interwał D1|D5]; domyślnie rozpoznanie po nazwie y-<klucz>-<interwał>.json
        for (path in args) {
            val f = File(path)
            val parts = f.nameWithoutExtension.split('-')
            val key = parts.getOrElse(1) { "X" }.uppercase()
            val iv = parts.getOrElse(2) { "5m" }
            val five = iv == "5d"
            val (rg, interval, range) = if (five) Triple("5d", "15m", ChartRange.D5) else Triple("1d", iv, ChartRange.D1)
            describe(f.name, key, rg, interval, range, f.readText())
        }
    }
    edge()
    println(if (fails == 0) "WSZYSTKIE PRZYPADKI BRZEGOWE OK" else "BŁĘDÓW: $fails")
    if (fails > 0) System.exit(1)
}
