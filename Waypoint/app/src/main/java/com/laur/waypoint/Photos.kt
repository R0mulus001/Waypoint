package com.laur.waypoint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class PlaceInfo(val extract: String, val page: String, val photos: List<File>)

private class Busy(val waitMs: Long = 0) : RuntimeException("busy")

/**
 * Photos and Wikipedia text for each place. The first time a place is opened (or with Download all) they are
 * downloaded and saved in the app's own storage, so after that they show without internet.
 *
 * A place counts as "done" once its lookup finished without network errors, even if it has no photos online,
 * so Download all can tell "nothing to download" apart from "failed, try again".
 */
class Photos(private val ctx: Context, private val db: TripDb, private val city: City) {
    private val dir = File(ctx.filesDir, "photos/${city.key}").apply { mkdirs() }
    private var lastApi = 0L
    private val locks = HashMap<String, Mutex>()
    /** Text and photo addresses looked up in bulk by prefetch(), so each place doesn't need its own requests. */
    private class Pre(val text: Pair<String, String>?, val urls: List<String>)
    private val pre = ConcurrentHashMap<String, Pre>()
    // An eighth of the app's memory (capped at 48 MB), so small phones don't run out while swiping photos.
    private val mem = object : LruCache<String, Bitmap>(minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        db.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS checked(id TEXT PRIMARY KEY)")
        // Photo choice changed in version 3 (extra photos must be named after the place). Old picks are cleared once.
        if (db.kvGet("photoVer") != "3") {
            db.writableDatabase.execSQL("DELETE FROM photos")
            db.writableDatabase.execSQL("DELETE FROM checked")
            dir.listFiles()?.forEach { it.delete() }
            db.kvPut("photoVer", "3")
        }
    }

    private fun log(p: Place, what: String, e: Throwable?) =
        android.util.Log.w("Waypoint", "photos ${city.key}/${p.id} $what failed: ${e?.javaClass?.simpleName} ${e?.message}")

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun saved(id: String): PlaceInfo? {
        val files = db.query("SELECT file FROM photos WHERE id=? ORDER BY idx", arrayOf(id)) { File(dir, it.getString(0)) }.filter { it.exists() }
        val info = db.query("SELECT extract, page FROM info WHERE id=?", arrayOf(id)) { it.getString(0) to it.getString(1) }.firstOrNull()
        if (files.isEmpty() && info == null) return null
        return PlaceInfo(info?.first ?: "", info?.second ?: "", files)
    }

    fun cached(id: String): PlaceInfo? = saved(id)

    private fun markDone(id: String) {
        db.writableDatabase.execSQL("INSERT OR REPLACE INTO checked(id) VALUES(?)", arrayOf(id))
        db.writableDatabase.delete("kv", "k=?", arrayOf("try:$id"))
    }

    /** Busy server or no connection: try again later. Anything else (missing file, broken picture) won't get better. */
    private fun isNet(e: Throwable?) = e is Busy || e is java.io.IOException

    /** Places of this city whose lookup isn't finished yet. */
    fun pending(): List<Place> {
        val done = db.query("SELECT id FROM checked") { it.getString(0) }.toHashSet()
        return city.places.filter { it.id !in done }
    }

    /** "Download all" was pressed for this city once; from then on missing pieces are fetched on their own. */
    fun wantAll() = db.kvGet("dlAll") == "1"
    fun setWantAll() = db.kvPut("dlAll", "1")

    /**
     * Quiet check, never shown: every finished place must still have readable photo files on the phone.
     * A place with a missing or broken file loses its photos and goes back on the list, so it's downloaded again.
     */
    fun verify(): Int {
        var reopened = 0
        val done = db.query("SELECT id FROM checked") { it.getString(0) }.toHashSet()
        city.places.filter { it.id in done }.forEach { p ->
            val files = db.query("SELECT file FROM photos WHERE id=?", arrayOf(p.id)) { File(dir, it.getString(0)) }
            val broken = files.any { f ->
                !f.isFile || f.length() < 2000 ||
                    BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.outWidth <= 0
            }
            if (broken) {
                files.forEach { it.delete() }
                db.delete("photos", p.id)
                db.writableDatabase.delete("checked", "id=?", arrayOf(p.id))
                reopened++
            }
        }
        return reopened
    }
    fun isDone(id: String) = db.query("SELECT 1 FROM checked WHERE id=?", arrayOf(id)) { 1 }.isNotEmpty()

    /** Places with at least one photo saved. */
    // Counts only places in the guide now: after "Load places to eat" the old restaurants are still in the database.
    fun savedPlaces(): Int = db.query("SELECT DISTINCT id FROM photos") { it.getString(0) }.count { it in city.byId }
    /** Places whose lookup is finished (with or without photos). */
    fun donePlaces(): Int = db.query("SELECT id FROM checked") { it.getString(0) }.count { it in city.byId }

    suspend fun load(p: Place): PlaceInfo = withContext(Dispatchers.IO) {
        lock(p.id).withLock {
            val have = saved(p.id)
            // Finished before: show what was found, even if that's nothing (no new lookups every time it's opened).
            if (isDone(p.id)) return@withLock have ?: PlaceInfo("", "", emptyList())
            var textOk = true; var urlsOk = true; var netFail = false
            val got = pre[p.id]
            val text = if (p.isFood) null else if (got != null) got.text
                else runCatching { fetchText(p) }.onFailure { textOk = false; if (isNet(it)) netFail = true; log(p, "text", it) }.getOrNull()
            val urls = if (got != null && got.urls.isNotEmpty()) got.urls
                else runCatching { if (p.isFood) foodPhotoUrls(p) else sightPhotoUrls(p, got != null) }.onFailure { urlsOk = false; if (isNet(it)) netFail = true; log(p, "photo list", it) }.getOrDefault(emptyList())
            val already = have?.photos?.size ?: 0
            val files = ArrayList<File>(have?.photos ?: emptyList())
            if (already == 0) urls.forEachIndexed { k, u ->
                val f = File(dir, "${p.id}_$k.jpg")
                val r = runCatching { download(u, f) }
                if (r.isSuccess) {
                    db.put("photos", ContentValues().apply { put("id", p.id); put("idx", files.size); put("file", f.name) })
                    files.add(f)
                } else { if (isNet(r.exceptionOrNull())) netFail = true; log(p, "image", r.exceptionOrNull()) }
                Thread.sleep(150) // be gentle with Wikimedia's servers
            }
            if (text != null) db.put("info", ContentValues().apply { put("id", p.id); put("extract", text.first); put("page", text.second) })
            // Done once the lookups worked and we have photos (a single failed image doesn't hold the place back).
            val ok = textOk && urlsOk && (files.isNotEmpty() || urls.isEmpty())
            if (ok) markDone(p.id)
            else {
                // The bulk photo list didn't work for this place: next time look it up on its own, not the same links again.
                if (got != null && files.isEmpty()) pre.remove(p.id)
                val tries = (db.kvGet("try:${p.id}")?.toIntOrNull() ?: 0) + 1
                db.kvPut("try:${p.id}", tries.toString())
                // The connection was fine, the photos just can't be had (removed, not a real picture): stop after 3 tries.
                if (!netFail && tries >= 3) markDone(p.id)
            }
            saved(p.id) ?: PlaceInfo(text?.first ?: "", text?.second ?: "", files)
        }
    }

    suspend fun bitmap(f: File, maxW: Int): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(f.path)?.let { return@withContext it }
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxW) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()?.also { mem.put(f.path, it) }
    }

    // ---------- network, with retries when the server is busy ----------

    private fun <T> retry(block: () -> T): T {
        var last: Exception? = null
        var wait = 0L
        repeat(5) { attempt ->
            if (attempt > 0) Thread.sleep(maxOf(wait, longArrayOf(0, 3000, 8000, 18000, 30000)[attempt]))
            wait = 0
            try { return block() } catch (e: Busy) { last = e; wait = e.waitMs; android.util.Log.w("Waypoint", "busy, retry ${attempt + 1}") }
            catch (e: SocketTimeoutException) { last = e; android.util.Log.w("Waypoint", "timeout, retry ${attempt + 1}") }
            // No signal for a moment, Wi-Fi <-> mobile data switch, connection reset: worth another try too.
            catch (e: java.io.IOException) { last = e; android.util.Log.w("Waypoint", "network ${e.javaClass.simpleName}, retry ${attempt + 1}") }
        }
        throw last ?: RuntimeException("failed")
    }

    private fun open(url: String, readTimeout: Int): HttpURLConnection {
        if (url.contains("/api.php") || url.contains("/rest_v1/")) pace()
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Waypoint/1.1 (personal Android city guide; https://github.com/R0mulus001/Vienna-Trip)")
        c.connectTimeout = 12000; c.readTimeout = readTimeout
        val code = c.responseCode
        if (code == 429 || code >= 500) {
            val after = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1, 60)?.times(1000) ?: 0
            c.disconnect(); android.util.Log.w("Waypoint", "HTTP $code ${url.take(90)}"); throw Busy(after)
        }
        if (code !in 200..299) { c.disconnect(); throw RuntimeException("HTTP $code") }
        return c
    }

    private fun get(url: String): String = retry {
        val c = open(url, 15000)
        try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun download(url: String, to: File) = retry {
        val c = open(url, 25000)
        val tmp = File(to.path + ".part")
        try {
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() < 2000 || !tmp.renameTo(to)) throw RuntimeException("bad image")
        } finally { c.disconnect(); if (tmp.exists()) tmp.delete() } // no half-written files left behind
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun firstPage(r: JSONObject): JSONObject? {
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val it = pages.keys(); return if (it.hasNext()) pages.optJSONObject(it.next()) else null
    }

    private fun fetchText(p: Place): Pair<String, String>? {
        city.wiki[p.id]?.let { t ->
            val j = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" + enc(t)))
            val ex = j.optString("extract")
            if (ex.isNotBlank() && j.optString("type") != "disambiguation") {
                val page = j.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: ""
                return ex to page
            }
        }
        val pg = firstPage(JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=1&gsrsearch=" +
            enc(p.name + " " + city.cityLocal) + "&prop=extracts|info&exintro=1&explaintext=1&exsentences=3&inprop=url"))) ?: return null
        return pg.optString("extract") to pg.optString("fullurl")
    }

    /** Wikimedia asks apps not to fire requests in parallel; keep API calls at least 400 ms apart across all threads. */
    private fun pace() = synchronized(Photos::class.java) {
        val gap = lastApi + 400 - System.currentTimeMillis()
        if (gap > 0) Thread.sleep(gap)
        lastApi = System.currentTimeMillis()
    }

    /**
     * Looks up text, lead photo and article photos for many sights in a handful of requests
     * (20 articles per request) instead of 3 or 4 requests per place. Used by Download all.
     */
    fun prefetch(places: List<Place>) {
        val want = places.filter { !it.isFood && city.wiki[it.id] != null && pre[it.id] == null }
        want.chunked(20).forEach { chunk -> runCatching { prefetchChunk(chunk) }.onFailure {
            android.util.Log.w("Waypoint", "prefetch failed: ${it.javaClass.simpleName} ${it.message}") } }
    }

    private fun titleMap(q: JSONObject?, asked: List<String>): Map<String, String> {
        val m = HashMap<String, String>()
        fun follow(arr: String, t: String): String {
            val a = q?.optJSONArray(arr) ?: return t
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("from") == t) return o.optString("to") }
            return t
        }
        asked.forEach { t -> m[t] = follow("redirects", follow("normalized", t)) }
        return m
    }

    private fun prefetchChunk(chunk: List<Place>) {
        val asked = chunk.associate { it.id to city.wiki[it.id]!!.replace('_', ' ') }
        val titles = asked.values.distinct().joinToString("|") { it }
        val base = "https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&titles=" + enc(titles)
        val a = JSONObject(get(base + "&prop=extracts%7Cpageimages%7Cinfo%7Cpageprops&exintro=1&explaintext=1&exsentences=3&exlimit=20" +
            "&piprop=thumbnail&pithumbsize=960&pilimit=20&inprop=url&ppprop=disambiguation")).optJSONObject("query")
        val b = JSONObject(get(base + "&prop=images&imlimit=max")).optJSONObject("query")
        val names = titleMap(a, asked.values.toList())
        fun pages(q: JSONObject?): Map<String, JSONObject> {
            val ps = q?.optJSONObject("pages") ?: return emptyMap()
            return ps.keys().asSequence().map { ps.getJSONObject(it) }.associateBy { it.optString("title") }
        }
        val pa = pages(a); val pb = pages(b)
        // Candidate pictures per place, by file name only (photo formats, no maps or logos), then one bulk size check.
        val cand = HashMap<String, List<String>>()
        asked.forEach { (id, t) ->
            val imgs = pb[names[t]]?.optJSONArray("images") ?: return@forEach
            cand[id] = (0 until imgs.length()).map { imgs.getJSONObject(it).optString("title") }
                .filter { Regex("\\.(jpe?g|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(it) && !junk.containsMatchIn(it) }.take(10)
        }
        val info = HashMap<String, String>()
        cand.values.flatten().distinct().chunked(50).forEach { files ->
            val r = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=960&titles=" +
                enc(files.joinToString("|")))).optJSONObject("query")
            val fm = titleMap(r, files)
            val ps = pages(r)
            files.forEach { f ->
                val ii = ps[fm[f]]?.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
                if (usable(f, ii.optString("mime"), ii.optInt("width"), ii.optInt("height")))
                    info[f] = ii.optString("thumburl").ifBlank { ii.optString("url") }
            }
        }
        asked.forEach { (id, t) ->
            val pg = pa[names[t]] ?: return@forEach
            if (pg.has("missing")) return@forEach
            val ex = pg.optString("extract")
            val text = if (ex.isNotBlank() && pg.optJSONObject("pageprops")?.has("disambiguation") != true) ex to pg.optString("fullurl") else null
            val picked = LinkedHashMap<String, String>()
            fun add(u: String) { val n = fileName(u); if (n.isNotBlank() && !picked.containsKey(n)) picked[n] = u }
            pg.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }?.let(::add)
            // Articles also show other buildings (comparisons, "see also", boxes at the bottom): only pictures named after this place.
            val toks = placeTokens(chunk.first { it.id == id }, t)
            cand[id]?.filter { matches(it, toks) }?.forEach { f -> info[f]?.let(::add) }
            pre[id] = Pre(text, picked.values.take(6).toList())
        }
    }

    // ---------- choosing photos ----------

    private data class Img(val title: String, val thumb: String)

    /** Drawings, symbols and documents rather than photos of the place. */
    // Whole words only: the old substring match also threw away "Chartres", "Iconostasis", "Photograph of…",
    // "Covered market" and every "Wiki Loves Monuments" photo, which are often the best pictures.
    private val junk = Regex("(?<![a-z])(maps?|mapa|plans?|karte|logo|coat[ _]of[ _]arms|wappen|herb|diagram|menu|receipt|flags?|icon|symbol|" +
        "signature|locator|seal|emblem|stamps?|banknotes?|coins?|chart|graph|poster|book[ _]cover|award|medal|scheme|location|button|arrow|" +
        "sketch|drawings?|engraving|lithograph)(?![a-z])", RegexOption.IGNORE_CASE)

    private fun usable(title: String, mime: String, w: Int, h: Int): Boolean =
        Regex("jpeg|webp").containsMatchIn(mime) && w >= 600 && h >= 400 && w.toFloat() / h in 0.6f..2.6f && !junk.containsMatchIn(title)

    /** The photo at the top of the place's Wikipedia article: almost always the landmark itself. */
    private fun leadImage(title: String): String? {
        val pg = firstPage(JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages&piprop=thumbnail&pithumbsize=960&titles=" +
            enc(title.replace('_', ' '))))) ?: return null
        return pg.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }
    }

    /** Photos used inside the place's own Wikipedia article: chosen by editors, so they show the right place. */
    private fun articleImages(title: String): List<Img> {
        val r = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&generator=images&gimlimit=60" +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=960&titles=" + enc(title.replace('_', ' '))))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<Img>()
        pages.keys().forEach { k ->
            val pg = pages.getJSONObject(k)
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
            val t = pg.optString("title")
            if (usable(t, ii.optString("mime"), ii.optInt("width"), ii.optInt("height")))
                out.add(Img(t, ii.optString("thumburl").ifBlank { ii.optString("url") }))
        }
        return out
    }

    private fun commons(term: String, limit: Int = 20): List<Img> {
        val r = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=$limit&gsrsearch=" +
            enc("filetype:bitmap $term") + "&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=960"))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 999) }.mapNotNull { pg ->
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val t = pg.optString("title")
            if (usable(t, ii.optString("mime"), ii.optInt("width"), ii.optInt("height"))) Img(t, ii.optString("thumburl").ifBlank { ii.optString("url") }) else null
        }.toList()
    }

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("ł", "l").replace("Ł", "L").lowercase()
    private fun fileName(url: String) = plain(url.substringAfterLast("/").substringAfter("px-"))

    private val generic = setOf("cafe", "caffe", "the", "and", "pod", "bar", "mleczny", "books", "lounge", "chocolate", "plac", "nowy", "cloth", "hall",
        "pierogarnia", "krakow", "wodka", "restaurant", "restaurante", "gasthaus", "zum", "zur", "der", "die", "das", "wien", "vienna", "stalls", "food",
        "ristorante", "trattoria", "pizzeria", "pasticceria", "milano", "milan", "antica", "terrazza", "della", "dei", "del", "schwarzen", "weissen",
        "church", "palace", "square", "museum", "park", "street", "bridge", "tower", "gate", "house", "garden", "gardens", "castle", "basilica",
        "cathedral", "national", "city", "old", "new", "saint", "royal", "main", "great", "grand",
        // building types: a picture named "...Mosque..." can be any mosque, so these never count as the place's name
        "mosque", "synagogue", "chapel", "abbey", "monastery", "temple", "theatre", "theater", "opera", "library", "fortress", "fort",
        "memorial", "monument", "statue", "fountain", "building", "centre", "center", "market", "station", "lake", "river", "island",
        "hill", "tomb", "cemetery", "gallery", "history", "historical", "natural", "science", "modern", "contemporary", "folk",
        "ethnographic", "archaeological", "orthodox", "catholic", "evangelical", "lutheran", "reformed", "parish", "holy", "trinity",
        "assumption", "virgin", "mary", "nicholas", "george", "michael", "peter", "paul", "john", "stephen", "francis", "anne", "barbara")

    /** Distinctive words of a name ("Cloth Hall (Sukiennice)" -> sukiennice). Pictures from search must contain one. */
    private fun tokens(name: String) = plain(name).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in generic }.toSet()

    private fun matches(title: String, toks: Set<String>) = toks.isNotEmpty() && toks.any { plain(title).contains(it) }

    /** Distinctive words from the place's name and its Wikipedia title (e.g. "azizyie" for Azizyie Mosque). */
    private fun placeTokens(p: Place, title: String?) = tokens(p.name) + tokens(title?.replace('_', ' ').orEmpty())

    /** Up to 6 photos of a sight: the article's own lead photo, then other photos from that article, then strict search results. */
    private fun sightPhotoUrls(p: Place, articleChecked: Boolean = false): List<String> {
        val title = city.wiki[p.id]
        val picked = LinkedHashMap<String, String>() // file name -> url
        fun add(u: String) { val n = fileName(u); if (n.isNotBlank() && !picked.containsKey(n)) picked[n] = u }
        val toks = placeTokens(p, title)
        if (title != null && !articleChecked) {
            leadImage(title)?.let(::add)
            // Same rule as in bulk: other pictures in the article must be named after this place.
            articleImages(title).filter { matches(it.title, toks) }.forEach { add(it.thumb) }
        }
        if (picked.size < 3) {
            val term = city.commons[p.id] ?: (p.name.substringBefore(" (") + " " + city.cityLocal)
            commons(term, 14).filter { matches(it.title, toks) || city.commons.containsKey(p.id) && matches(it.title, tokens(term)) }.forEach { add(it.thumb) }
        }
        return picked.values.take(6)
    }

    private val inside = Regex("interior|inside|innen|innenraum|interno|wnetrz|sala|saal|dining|counter|theke|bancone|bar room", RegexOption.IGNORE_CASE)

    /** Up to 5 photos of a restaurant, inside shots first. Only pictures whose file name contains the place's own name. */
    private fun foodPhotoUrls(p: Place): List<String> {
        val nm = p.name.substringBefore(",")
        val toks = tokens(nm)
        if (toks.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val hits = commons("$nm ${city.cityLocal}", 30)
            .filter { matches(it.title, toks) && seen.add(it.title) }
        return hits.sortedByDescending { inside.containsMatchIn(plain(it.title)) }.map { it.thumb }.take(5)
    }
}

/**
 * Download all for one city. Lives as long as the app, so it keeps going when you leave the city screen.
 * After each pass it quietly checks what's on the phone and, if anything is still missing, keeps going
 * (waiting longer between passes when the server is busy or there's no signal) until every place is finished.
 * Once "Download all" was pressed, this also resumes on its own the next time the city is opened.
 */
class Downloader(private val photos: Photos, private val city: City, private val scope: CoroutineScope) {
    var running by mutableStateOf(false); private set
    var done by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var withPhotos by mutableIntStateOf(photos.savedPlaces()); private set
    var finished by mutableIntStateOf(photos.donePlaces()); private set
    private var job: kotlinx.coroutines.Job? = null

    val places get() = city.places.size
    /** Places whose lookup failed (busy server, no signal) and should be tried again. */
    val failed get() = (places - finished).coerceAtLeast(0)

    init { if (photos.wantAll()) start() }

    fun refresh() {
        withPhotos = photos.savedPlaces(); finished = photos.donePlaces()
        if (running) done = (total - failed).coerceIn(0, total)
    }

    fun stop() { job?.cancel(); job = null; running = false }

    fun start() {
        if (job?.isActive == true) return
        photos.setWantAll()
        job = scope.launch {
            try {
                var pass = 0
                while (true) {
                    // Hidden check first: anything saved but broken goes back on the list.
                    withContext(Dispatchers.IO) { photos.verify() }
                    val todo = withContext(Dispatchers.IO) { photos.pending() }
                    if (todo.isEmpty()) break
                    if (!running) { total = todo.size; running = true }
                    refresh()
                    if (pass > 0) kotlinx.coroutines.delay(longArrayOf(15_000, 30_000, 60_000, 120_000)[minOf(pass - 1, 3)])
                    pass++
                    // Text and photo lists for all sights in a few bulk requests, then the images themselves, two places at a time.
                    withContext(Dispatchers.IO) { photos.prefetch(todo) }
                    val gate = Semaphore(2)
                    kotlinx.coroutines.coroutineScope {
                        todo.forEach { p -> launch { gate.withPermit { runCatching { photos.load(p) } }; refresh() } }
                    }
                }
            } finally {
                refresh()
                running = false
            }
        }
    }
}
