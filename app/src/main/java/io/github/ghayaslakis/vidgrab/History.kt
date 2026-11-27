package io.github.ghayaslakis.vidgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every finished download (title, link, saved files, date), kept in app storage so a link can be found,
 * opened, shared or downloaded again later, and so a link downloaded before is recognised.
 * Nothing is recorded while Settings > More options > Incognito is on.
 */
internal object History {
    class Entry(val url: String, val title: String, val meta: String, val thumb: String,
                val files: List<String>, val date: Long)

    private const val MAX = 1000
    private val lock = Any()
    @Volatile private var cache: MutableList<Entry>? = null

    private fun file(c: Context) = File(c.filesDir, "history.json")

    fun all(c: Context): List<Entry> = synchronized(lock) { load(c).toList() }

    /** The latest download of this link, if it was downloaded before. */
    fun find(c: Context, url: String): Entry? = synchronized(lock) { load(c).firstOrNull { it.url == url } }

    fun add(c: Context, e: Entry) {
        if (Opts.prefs(c).getBoolean("incognito", false)) return
        synchronized(lock) {
            val l = load(c)
            l.add(0, e)
            while (l.size > MAX) l.removeAt(l.size - 1)
            save(c, l)
        }
    }

    fun remove(c: Context, e: Entry) = synchronized(lock) {
        val l = load(c)
        l.removeAll { it.url == e.url && it.date == e.date }
        save(c, l)
    }

    fun clear(c: Context) = synchronized(lock) { save(c, mutableListOf()) }

    /** Adds entries from a backup; an entry already there (same link and date) is skipped. Returns how many. */
    fun merge(c: Context, arr: JSONArray): Int = synchronized(lock) {
        val l = load(c)
        var n = 0
        for (e in parse(arr)) if (l.none { it.url == e.url && it.date == e.date }) { l.add(e); n++ }
        l.sortByDescending { it.date }
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(c, l)
        n
    }

    fun toJson(c: Context): JSONArray = synchronized(lock) { toJson(load(c)) }

    private fun toJson(l: List<Entry>) = JSONArray().apply {
        for (e in l) put(JSONObject().apply {
            put("url", e.url); put("title", e.title); put("meta", e.meta); put("thumb", e.thumb)
            put("files", JSONArray(e.files)); put("date", e.date)
        })
    }

    private fun parse(arr: JSONArray): List<Entry> {
        val out = mutableListOf<Entry>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val f = o.optJSONArray("files") ?: JSONArray()
            out.add(Entry(o.optString("url"), o.optString("title"), o.optString("meta"), o.optString("thumb"),
                List(f.length()) { f.optString(it) }, o.optLong("date")))
        }
        return out
    }

    private fun load(c: Context): MutableList<Entry> {
        cache?.let { return it }
        val l = try { parse(JSONArray(file(c).readText())).toMutableList() } catch (_: Exception) { mutableListOf() }
        cache = l
        return l
    }

    private fun save(c: Context, l: MutableList<Entry>) {
        cache = l
        try {
            val tmp = File(c.filesDir, "history.json.tmp")
            tmp.writeText(toJson(l).toString())
            if (!tmp.renameTo(file(c))) { file(c).delete(); tmp.renameTo(file(c)) }
        } catch (_: Exception) {}
    }
}

/**
 * A backup is one JSON file: the settings, the list and the download history. Logins (cookies) and the
 * chosen folder (a permission that belongs to this phone) are left out on purpose.
 */
internal object Backup {
    private val SKIP = setOf("tree", "items", "batches", "engineChecked")

    fun export(c: Context): String {
        val p = Opts.prefs(c)
        val settings = JSONObject()
        for ((k, v) in p.all) {
            if (k in SKIP || v == null) continue
            val t = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue
            }
            settings.put(k, JSONObject().put("t", t).put("v", v))
        }
        return JSONObject().apply {
            put("app", "VidGrab")
            put("backup", 1)
            put("created", System.currentTimeMillis())
            put("settings", settings)
            put("items", JSONArray(p.getString("items", null) ?: "[]"))
            put("history", History.toJson(c))
        }.toString(2)
    }

    class Restored(val settings: Int, val items: JSONArray, val history: Int)

    /** Applies a backup's settings and history; returns its list for the screen to add. Throws on a bad file. */
    fun import(c: Context, text: String): Restored {
        val o = JSONObject(text)
        require(o.optString("app") == "VidGrab") { "This is not a VidGrab backup" }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val e = Opts.prefs(c).edit()
        var n = 0
        for (k in s.keys()) {
            if (k in SKIP) continue
            val x = s.optJSONObject(k) ?: continue
            when (x.optString("t")) {
                "b" -> e.putBoolean(k, x.optBoolean("v"))
                "i" -> e.putInt(k, x.optInt("v"))
                "l" -> e.putLong(k, x.optLong("v"))
                "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                "s" -> e.putString(k, x.optString("v"))
                else -> continue
            }
            n++
        }
        e.apply()
        val h = History.merge(c, o.optJSONArray("history") ?: JSONArray())
        return Restored(n, o.optJSONArray("items") ?: JSONArray(), h)
    }
}
