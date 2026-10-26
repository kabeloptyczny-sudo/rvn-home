// Tylko do testów na JVM (poza aplikacją): minimalny org.json i android.util.Log, żeby uruchomić parser bez Androida.
package org.json

class JSONException(msg: String) : Exception(msg)

class JSONObject {
    companion object {
        @JvmField val NULL: Any = object { override fun toString() = "null" }
    }

    internal val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(src: String) {
        val v = JsonParser(src).parseTop()
        if (v !is JSONObject) throw JSONException("to nie obiekt JSON")
        map.putAll(v.map)
    }

    private fun raw(n: String): Any? = map[n]
    fun isNull(n: String): Boolean { val v = raw(n); return v == null || v === NULL }
    fun optJSONObject(n: String): JSONObject? = raw(n) as? JSONObject
    fun optJSONArray(n: String): JSONArray? = raw(n) as? JSONArray
    fun optDouble(n: String, d: Double): Double = num(raw(n), d)
    fun optDouble(n: String): Double = num(raw(n), Double.NaN)
    fun optLong(n: String, d: Long): Long { val v = raw(n); return if (v is Number) v.toLong() else d }
    fun optLong(n: String): Long = optLong(n, 0L)
    fun optString(n: String, d: String): String { val v = raw(n); return if (v == null || v === NULL) d else v.toString() }
    fun put(n: String, v: Any?): JSONObject { map[n] = v; return this }
    override fun toString(): String = map.entries.joinToString(",", "{", "}") { "\"" + it.key + "\":" + it.value }
}

class JSONArray {
    internal val list = ArrayList<Any?>()
    fun length(): Int = list.size
    private fun raw(i: Int): Any? = if (i in 0 until list.size) list[i] else null
    fun isNull(i: Int): Boolean { val v = raw(i); return v == null || v === JSONObject.NULL }
    fun optJSONObject(i: Int): JSONObject? = raw(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = raw(i) as? JSONArray
    fun optDouble(i: Int, d: Double): Double = num(raw(i), d)
    fun optDouble(i: Int): Double = num(raw(i), Double.NaN)
    fun optLong(i: Int, d: Long): Long { val v = raw(i); return if (v is Number) v.toLong() else d }
    fun optLong(i: Int): Long = optLong(i, 0L)
    fun put(v: Any?): JSONArray { list.add(v); return this }
}

private fun num(v: Any?, d: Double): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: d
    else -> d
}

internal class JsonParser(private val s: String) {
    private var i = 0
    fun parseTop(): Any? { ws(); val v = value(); ws(); if (i != s.length) throw JSONException("śmieci po JSON @$i"); return v }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        ws()
        if (i >= s.length) throw JSONException("koniec danych")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { lit("true"); true }
            'f' -> { lit("false"); false }
            'n' -> { lit("null"); JSONObject.NULL }
            else -> if (c == '-' || c.isDigit()) number() else throw JSONException("znak '$c' @$i")
        }
    }
    private fun lit(w: String) { if (!s.startsWith(w, i)) throw JSONException("oczekiwano $w @$i"); i += w.length }
    private fun number(): Number {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(st, i)
        return t.toLongOrNull() ?: t.toDouble()
    }
    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(s[i])
            i++
        }
        i++
        return sb.toString()
    }
    private fun obj(): JSONObject {
        val o = JSONObject()
        i++; ws()
        if (s[i] == '}') { i++; return o }
        while (true) {
            ws(); val k = str(); ws()
            if (s[i] != ':') throw JSONException("oczekiwano : @$i")
            i++
            o.map[k] = value(); ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; return o }
            throw JSONException("oczekiwano , lub } @$i")
        }
    }
    private fun arr(): JSONArray {
        val a = JSONArray()
        i++; ws()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.list.add(value()); ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; return a }
            throw JSONException("oczekiwano , lub ] @$i")
        }
    }
}
