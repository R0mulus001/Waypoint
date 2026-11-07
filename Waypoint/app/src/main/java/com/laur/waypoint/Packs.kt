package com.laur.waypoint

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ready-made city data, built on GitHub ("Build city data" workflow) and published in the repo's "data" release:
 *   index.json        which countries and cities are ready
 *   <cc>.json         every ready city of a country: places, texts, photo lists (downloaded when you pick the country)
 *   <cc>-<city>.zip   the photos of one city (downloaded for a trip, then they work offline)
 * Cities that aren't ready yet are built on the phone as before.
 */
object Packs {
    private const val BASE = "https://github.com/R0mulus001/Waypoint/releases/download/data/"
    private const val UA = "Waypoint/1.2 (personal Android city guide)"
    private lateinit var dir: File
    private var index: JSONObject? = null
    private var indexAt = 0L

    fun init(ctx: Context) { dir = File(ctx.filesDir, "packs").apply { mkdirs() } }

    fun url(file: String) = BASE + file

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        c.connectTimeout = 12000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        return c
    }

    private fun text(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** What's ready, checked at most every 6 hours (the saved copy is used when offline). */
    private fun index(): JSONObject? = synchronized(this) {
        val f = File(dir, "index.json")
        if (index == null && f.exists()) index = runCatching { JSONObject(f.readText()) }.getOrNull()
        if (System.currentTimeMillis() - indexAt > 6 * 3600_000L) {
            runCatching { text(url("index.json")) }.getOrNull()?.let { t ->
                runCatching { JSONObject(t) }.getOrNull()?.let { index = it; f.writeText(t) }
            }
            indexAt = System.currentTimeMillis()
        }
        index
    }

    /** The country's data, downloaded again only when a newer version is out. */
    private fun country(code: String): JSONObject? {
        val entry = index()?.optJSONObject("countries")?.optJSONObject(code.uppercase()) ?: return null
        val f = File(dir, code.lowercase() + ".json")
        val have = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null
        if (have != null && have.optString("version") >= entry.optString("version")) return have
        val t = runCatching { text(url(entry.getString("file"))) }.getOrNull() ?: return have
        return runCatching { JSONObject(t) }.getOrNull()?.also { f.writeText(t) } ?: have
    }

    /** The ready-made city ([key] like "dk-copenhagen") and its country's holidays, or null if it isn't ready. */
    suspend fun city(code: String, key: String): Pair<JSONObject, JSONObject>? = withContext(Dispatchers.IO) {
        val co = country(code) ?: return@withContext null
        val c = co.optJSONObject("cities")?.optJSONObject(key) ?: return@withContext null
        c to (co.optJSONObject("holidays") ?: JSONObject())
    }

    /** Is a newer ready-made version of this city out than [version] (null: the guide wasn't made from one)? */
    suspend fun newer(code: String, key: String, version: String?): Boolean = withContext(Dispatchers.IO) {
        val c = index()?.optJSONObject("countries")?.optJSONObject(code.uppercase())?.optJSONObject("cities")?.optJSONObject(key) ?: return@withContext false
        val v = country(code)?.optJSONObject("cities")?.optJSONObject(key)?.optString("built") ?: return@withContext false
        c.length() > 0 && (version == null || v > version)
    }

    /**
     * Downloads [file] to [to], continuing a half-finished download where it stopped.
     * [progress] gets (bytes so far, total bytes).
     */
    fun download(file: String, to: File, progress: (Long, Long) -> Unit) {
        val part = File(to.path + ".part")
        var from = if (part.exists()) part.length() else 0L
        val c = open(url(file), from)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            if (code == 200 && from > 0) { part.delete(); from = 0 } // the server sent it all again
            val total = from + c.contentLengthLong.coerceAtLeast(0)
            var done = from
            var last = 0L
            c.inputStream.use { i ->
                java.io.FileOutputStream(part, from > 0).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (done - last > 256 * 1024) { progress(done, total); last = done }
                    }
                }
            }
            progress(done, total)
            if (total > 0 && done < total) throw java.io.IOException("cut off")
            if (!part.renameTo(to)) throw java.io.IOException("can't save")
        } finally { c.disconnect() }
    }
}
