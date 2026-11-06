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

/** Oldest year a photo may be from: the place should look like it does now. */
private val MIN_YEAR get() = java.time.LocalDate.now().year - 15

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
        // Photo choice changed in version 8 (local-language Wikipedia, Wikidata's own photo, no paintings, maps or close-ups). Old picks are cleared once.
        if (db.kvGet("photoVer") != "8") {
            db.writableDatabase.execSQL("DELETE FROM photos")
            db.writableDatabase.execSQL("DELETE FROM checked")
            dir.listFiles()?.forEach { it.delete() }
            db.kvPut("photoVer", "8")
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
            val wiki = if (got != null && got.urls.isNotEmpty()) got.urls
                else runCatching { if (p.isFood) foodPhotoUrls(p) else sightPhotoUrls(p, got != null) }.onFailure { urlsOk = false; if (isNet(it)) netFail = true; log(p, "photo list", it) }.getOrDefault(emptyList())
            // A mix: recent Wikimedia photos first, then up to 2 (3 for places to eat) recent street photos from Mapillary.
            // Mapillary is extra: if it fails, the place still counts as done with its Wikimedia photos.
            // Street photos only fill a gap: with 4 or more photos of the place itself they're left out, because a street
            // view from 50 m away mostly shows the street. Then at most 2 (3 for places to eat), always after the Wikimedia ones.
            val cap = if (p.isFood) 5 else 6
            val room = if (wiki.size >= 4) 0 else minOf(cap - wiki.size, if (p.isFood) 3 else 2)
            val street = if (Mapillary.on && room > 0) runCatching { Mapillary.near(p, room) }.onFailure { log(p, "mapillary", it) }.getOrDefault(emptyList()) else emptyList()
            val urls = wiki.take(cap - street.size) + street
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
            "&piprop=thumbnail%7Cname&pithumbsize=960&pilimit=20&inprop=url&ppprop=disambiguation%7Cwikibase_item")).optJSONObject("query")
        // The image lists of all 20 articles share one limit (500), so big articles came back with none: follow "continue".
        val b = JSONObject().apply { put("pages", JSONObject()) }
        var cont = ""
        for (round in 0 until 6) {
            val r = JSONObject(get(base + "&prop=images&imlimit=max" + cont))
            r.optJSONObject("query")?.optJSONObject("pages")?.let { ps ->
                ps.keys().forEach { k ->
                    val pg = ps.getJSONObject(k)
                    val have = b.getJSONObject("pages").optJSONObject(k)
                    if (have == null) b.getJSONObject("pages").put(k, pg)
                    else pg.optJSONArray("images")?.let { add -> val into = have.optJSONArray("images") ?: org.json.JSONArray().also { have.put("images", it) }
                        for (i in 0 until add.length()) into.put(add.get(i)) }
                }
            }
            r.optJSONObject("query")?.let { q -> q.optJSONArray("normalized")?.let { b.put("normalized", it) }; q.optJSONArray("redirects")?.let { b.put("redirects", it) } }
            val c = r.optJSONObject("continue")?.optString("imcontinue")?.takeIf { it.isNotBlank() } ?: break
            cont = "&imcontinue=" + enc(c)
        }
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
                .filter { Regex("\\.(jpe?g|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(it) && !junk.containsMatchIn(it) }.take(30)
        }
        // Lead pictures are checked the same way (type, size and the date they were taken), in the same bulk requests.
        val leads = asked.mapNotNull { (_, t) -> pa[names[t]]?.optString("pageimage")?.takeIf { it.isNotBlank() }?.let { "File:" + it.replace('_', ' ') } }
        val qids = asked.mapNotNull { (id, t) -> pa[names[t]]?.optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap()
        val its = runCatching { items(qids.values.toList()) }.getOrDefault(emptyMap())
        val info = fileInfo(cand.values.flatten() + leads + its.values.mapNotNull { it.photo?.let { f -> "File:" + f.replace('_', ' ') } })
        // The same places' articles in the country's own language: usually more, newer photos (all in a few requests).
        val l = lang
        val la = if (l == null) emptyMap() else runCatching { articles(l, its.values.mapNotNull { it.local }) }.getOrDefault(emptyMap())
        val linfo = if (l == null) emptyMap() else runCatching { fileInfo(la.values.flatMap { listOfNotNull(it.first) + it.second }, l) }.getOrDefault(emptyMap())
        asked.forEach { (id, t) ->
            val pg = pa[names[t]] ?: return@forEach
            if (pg.has("missing")) return@forEach
            val ex = pg.optString("extract")
            val text = if (ex.isNotBlank() && pg.optJSONObject("pageprops")?.has("disambiguation") != true) ex to pg.optString("fullurl") else null
            val picked = LinkedHashMap<String, String>()
            fun add(u: String) { val n = fileName(u); if (n.isNotBlank() && !picked.containsKey(n)) picked[n] = u }
            val leadFile = pg.optString("pageimage").takeIf { it.isNotBlank() }?.let { "File:" + it.replace('_', ' ') }
            val enLead = pg.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() && leadOk(it) }
                ?.let { u -> leadFile?.let { info[it] }?.let { Pic(u, it.good) } }
            val item = qids[id]?.let { its[it] }
            val cat = item?.cat
            val toks = placeTokens(chunk.first { it.id == id }, t) + itemTokens(item)
            val local = item?.local?.let { la[it] }
            choose(enLead, cand[id].orEmpty().mapNotNull { f -> info[f]?.let { f to it } }, item?.photo?.let { info["File:" + it.replace('_', ' ')] },
                local?.first?.let { linfo[it] }, local?.second.orEmpty().mapNotNull { f -> linfo[f]?.let { f to it } }, toks).forEach(::add)
            // Few or none from the article: the place's own Commons category, photos named after it first.
            if (picked.size < 4 && cat != null) runCatching { categoryPhotos(cat, toks) }.getOrDefault(emptyList()).forEach(::add)
            pre[id] = Pre(text, picked.values.take(6).toList())
        }
    }

    // ---------- choosing photos ----------

    private data class Img(val title: String, val thumb: String, val good: Boolean = false)

    /** Drawings, symbols and documents rather than photos of the place. */
    // Whole words only: the old substring match also threw away "Chartres", "Iconostasis", "Photograph of…",
    // "Covered market" and every "Wiki Loves Monuments" photo, which are often the best pictures.
    private val junk = Regex("(?<![a-z])(maps?|mapa|plans?|karte|logo|coat[ _]of[ _]arms|wappen|herb|diagram|menu|receipt|flags?|icon|symbol|" +
        "signature|locator|seal|emblem|stamps?|banknotes?|coins?|chart|graph|poster|book[ _]cover|award|medal|scheme|location|button|arrow|" +
        "sketch|drawings?|engraving|lithograph)(?![a-z])", RegexOption.IGNORE_CASE)

    /** Maps and plans named in one word, as German and other languages do ("Stadtplan", "Übersichtskarte", "Lageplan"). */
    private val mapWords = Regex("(stadt|lage|grund|uber|ubersichts|land|welt|orts|bezirks|strassen|stra(ss|ß)en)(plan|karte|riss)|grundriss|" +
        "(?<![a-z])atlas(?![a-z])|cartograph|location[ _-]?map|relief[ _-]?map|blank[ _-]?map|outline[ _-]?map", RegexOption.IGNORE_CASE)

    private fun usable(title: String, ii: JSONObject): Boolean {
        val w = ii.optInt("width"); val h = ii.optInt("height")
        return Regex("jpeg|webp").containsMatchIn(ii.optString("mime")) && w >= 600 && h >= 400 && w.toFloat() / h in 0.6f..2.6f &&
            !junk.containsMatchIn(title) && !mapWords.containsMatchIn(plain(title)) && recent(ii) && !notAPhoto.containsMatchIn(cats(ii))
    }

    /** The file's Commons categories, in one lowercase line ("quality images|st. stephen's cathedral, vienna|..."). */
    private fun cats(ii: JSONObject) = plain(ii.optJSONObject("extmetadata")?.optJSONObject("Categories")?.optString("value").orEmpty()).replace('_', ' ')

    /**
     * Commons sorts every file into categories, and those say what a picture really is. Paintings, engravings and old
     * scans (often uploaded recently, so the date alone let them through), maps saved as JPEG, and close-ups of details
     * (the cathedral's bells, a relief, a plaque) don't show the place as you'll see it.
     */
    private val notAPhoto = Regex("(?<![a-z])(paintings?|gemalde|dipinti|obrazy|engravings?|etchings?|woodcuts?|lithographs?|prints|drawings?|" +
        "illustrations?|manuscripts?|portraits?|artworks?|frescos?|frescoes|mosaics?|stained glass|tapestr[a-z]*|miniatures|in art|" +
        "historical images|historical photographs|old photographs|postcards|scans?|maps?|plans|floor plans|cartograph[a-z]*|territor[a-z]*|" +
        "bells?|glocken?|campane|dzwony|reliefs?|details?|inscriptions?|plaques?|coins?|stamps?|documents?|signatures?|coats? of arms|" +
        "heraldry|banners?|flags?|tombs?|epitaphs?|graves?|people|politicians|ceremon[a-z]*|press conferences?|logos?|diagrams?|charts?|" +
        "reconstructions?|models?|3d renders?)(?![a-z])")

    /** Pictures Commons reviewers marked as good: sharp, well framed, usually the whole building. They go first. */
    private fun good(ii: JSONObject) = Regex("quality images|featured pictures|valued images").containsMatchIn(cats(ii))

    /**
     * Only photos taken in the last 15 years, so the place looks like it does today (no old postcards or scans).
     * The year comes from the date the photo was taken when the file has one, otherwise from the upload date
     * (old scans uploaded recently nearly always carry their real date, so they're still caught).
     */
    private fun recent(ii: JSONObject): Boolean {
        val taken = ii.optJSONObject("extmetadata")?.optJSONObject("DateTimeOriginal")?.optString("value").orEmpty()
        val year = Regex("(?<!\\d)(1[89]\\d\\d|20\\d\\d)(?!\\d)").find(taken)?.value?.toInt()
            ?: ii.optString("timestamp").take(4).toIntOrNull() ?: return false
        return year >= MIN_YEAR
    }

    /** The photo at the top of the place's Wikipedia article: almost always the landmark itself. */
    private fun leadImage(title: String): Pic? {
        val pg = firstPage(JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages&piprop=thumbnail%7Cname&pithumbsize=960&titles=" +
            enc(title.replace('_', ' '))))) ?: return null
        val thumb = pg.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() && leadOk(it) } ?: return null
        val name = pg.optString("pageimage").takeIf { it.isNotBlank() } ?: return null
        return fileInfo(listOf("File:" + name.replace('_', ' '))).values.firstOrNull()?.let { Pic(thumb, it.good) }
    }

    /** The Wikidata item of a Wikipedia article ("Q…"). */
    private fun qidOf(title: String): String? {
        val pg = firstPage(JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageprops&ppprop=wikibase_item&titles=" +
            enc(title.replace('_', ' '))))) ?: return null
        return pg.optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { it.isNotBlank() }
    }

    /** What Wikidata knows about a place: Commons category and gallery, its chosen photo (P18), its article and name in the local language. */
    private data class Item(val cat: String?, val gallery: String, val photo: String?, val local: String?, val label: String)

    /** The language of each country's own Wikipedia (Belgium: Dutch; Switzerland: German; Ireland, UK and Malta stay on English). */
    private val lang: String? get() = mapOf("AL" to "sq", "AD" to "ca", "AT" to "de", "BY" to "be", "BE" to "nl", "BA" to "bs", "BG" to "bg",
        "HR" to "hr", "CY" to "el", "CZ" to "cs", "DK" to "da", "EE" to "et", "FI" to "fi", "FR" to "fr", "DE" to "de", "GR" to "el", "HU" to "hu",
        "IS" to "is", "IT" to "it", "XK" to "sq", "LV" to "lv", "LI" to "de", "LT" to "lt", "LU" to "fr", "MD" to "ro", "MC" to "fr", "ME" to "sr",
        "NL" to "nl", "MK" to "mk", "NO" to "no", "PL" to "pl", "PT" to "pt", "RO" to "ro", "RU" to "ru", "SM" to "it", "RS" to "sr", "SK" to "sk",
        "SI" to "sl", "ES" to "es", "SE" to "sv", "CH" to "de", "TR" to "tr", "UA" to "uk", "VA" to "it")[city.countryCode]

    /**
     * The Commons category comes from the item's "Commons category" property (P373); only if that's missing, from its
     * Commons link when the link is a category. Big landmarks link to a Commons gallery page instead.
     */
    private fun items(qids: List<String>): Map<String, Item> {
        val out = HashMap<String, Item>()
        val l = lang
        qids.distinct().chunked(20).forEach { ids ->
            val r = JSONObject(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims%7Csitelinks%7Clabels" +
                "&sitefilter=commonswiki" + (if (l != null) "%7C${l}wiki&languages=$l" else "&languages=en") + "&ids=" + enc(ids.joinToString("|")))).optJSONObject("entities") ?: return@forEach
            ids.forEach { q ->
                val e = r.optJSONObject(q) ?: return@forEach
                fun claim(p: String) = e.optJSONObject("claims")?.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("mainsnak")
                    ?.optJSONObject("datavalue")?.optString("value").orEmpty().trim()
                val p373 = claim("P373")
                val sl = e.optJSONObject("sitelinks")
                val link = sl?.optJSONObject("commonswiki")?.optString("title").orEmpty()
                val cat = when {
                    p373.isNotBlank() -> p373.removePrefix("Category:")
                    link.startsWith("Category:") -> link.removePrefix("Category:")
                    else -> null
                }
                out[q] = Item(cat, link.takeIf { it.isNotBlank() && !it.startsWith("Category:") }.orEmpty(), claim("P18").ifBlank { null },
                    l?.let { sl?.optJSONObject("${it}wiki")?.optString("title")?.ifBlank { null } },
                    l?.let { e.optJSONObject("labels")?.optJSONObject(it)?.optString("value") }.orEmpty())
            }
        }
        return out
    }

    /** Words that recognise a place's photos, in English and in the local language (the gallery name is often the local one: "Stephansdom"). */
    private fun itemTokens(it: Item?) = if (it == null) emptySet() else
        tokens(it.cat.orEmpty()) + tokens(it.gallery) + tokens(it.label, true) + tokens(it.local.orEmpty(), true)

    /**
     * The order photos are shown in: the English article's lead photo, Wikidata's chosen photo, the local article's lead
     * photo; then the other photos of both articles (named after the place), the ones Commons reviewers marked as good first.
     */
    private fun ordered(leads: List<Pic?>, rest: List<Pic>): List<String> =
        leads.filterNotNull().map { it.url } + rest.withIndex().sortedWith(compareByDescending<IndexedValue<Pic>> { it.value.good }.thenBy { it.index }).map { it.value.url }

    /**
     * A place's photos from its two articles and Wikidata. Articles also show other buildings (comparisons, "see also",
     * boxes at the bottom), so their other pictures must be named after this place, in English or the local language.
     */
    private fun choose(enLead: Pic?, enFiles: List<Pair<String, Pic>>, photo: Pic?, localLead: Pic?, localFiles: List<Pair<String, Pic>>, toks: Set<String>): List<String> =
        ordered(listOf(enLead, photo, localLead), (enFiles + localFiles).filter { matches(it.first, toks) }.map { it.second })

    /**
     * Photos straight from a place's Commons category, with the usual checks (age, size, no maps). Pictures named after
     * the place come first (the building itself), then the rest, newest first. A category also holds close-ups of
     * details (a bell, a statue), so only big pictures count: at least 1200 px on the long side.
     */
    private fun categoryPhotos(cat: String, toks: Set<String> = emptySet(), max: Int = 6): List<String> {
        val r = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers&gcmtype=file" +
            "&gcmsort=timestamp&gcmdir=desc&gcmlimit=80&gcmtitle=" + enc("Category:$cat") +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata&iiextmetadatafilter=DateTimeOriginal%7CCategories&iiurlwidth=960"))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().map { pages.getJSONObject(it) }.mapNotNull { pg ->
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val t = pg.optString("title")
            if (!usable(t, ii) || maxOf(ii.optInt("width"), ii.optInt("height")) < 1200) return@mapNotNull null
            Triple((if (matches(t, toks)) 1 else 0) + (if (good(ii)) 2 else 0), ii.optString("timestamp"), ii.optString("thumburl").ifBlank { ii.optString("url") })
        }.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }.thenByDescending { it.second })
            .map { it.third }.take(max).toList()
    }

    private data class Pic(val url: String, val good: Boolean)

    /** Size, type and date of some files; only the ones fit to show, as file title -> picture address. [wiki]: language of the Wikipedia asked. */
    private fun fileInfo(files: List<String>, wiki: String = "en"): Map<String, Pic> {
        val out = HashMap<String, Pic>()
        files.distinct().chunked(50).forEach { chunk ->
            val r = JSONObject(get("https://$wiki.wikipedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata&iiextmetadatafilter=DateTimeOriginal%7CCategories&iiurlwidth=960&titles=" +
                enc(chunk.joinToString("|")))).optJSONObject("query")
            val fm = titleMap(r, chunk)
            val ps = r?.optJSONObject("pages") ?: return@forEach
            val byTitle = ps.keys().asSequence().map { ps.getJSONObject(it) }.associateBy { it.optString("title") }
            chunk.forEach { f ->
                val ii = byTitle[fm[f]]?.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
                if (usable(f, ii)) out[f] = Pic(ii.optString("thumburl").ifBlank { ii.optString("url") }, good(ii))
            }
        }
        return out
    }

    /**
     * Lead picture and photo files of several articles on one Wikipedia, in one go (following "continue", since
     * all articles share one limit). Title asked -> (lead file, other files). Used for the local-language articles.
     */
    private fun articles(wiki: String, titles: List<String>): Map<String, Pair<String?, List<String>>> {
        if (titles.isEmpty()) return emptyMap()
        val base = "https://$wiki.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages%7Cimages&piprop=name&imlimit=max&titles=" +
            enc(titles.distinct().joinToString("|"))
        val lead = HashMap<String, String>(); val imgs = HashMap<String, MutableList<String>>()
        var q0: JSONObject? = null; var cont = ""
        for (round in 0 until 6) {
            val r = JSONObject(get(base + cont))
            val q = r.optJSONObject("query") ?: break
            if (q0 == null) q0 = q
            q.optJSONObject("pages")?.let { ps -> ps.keys().forEach { k ->
                val pg = ps.getJSONObject(k); val t = pg.optString("title")
                pg.optString("pageimage").takeIf { it.isNotBlank() }?.let { lead[t] = it }
                pg.optJSONArray("images")?.let { a -> val l = imgs.getOrPut(t) { ArrayList() }; for (i in 0 until a.length()) l.add(a.getJSONObject(i).optString("title")) }
            } }
            val c = r.optJSONObject("continue") ?: break
            cont = c.keys().asSequence().joinToString("") { k -> "&$k=" + enc(c.optString(k)) }
        }
        val names = titleMap(q0, titles)
        return titles.associateWith { t ->
            val n = names[t] ?: t
            val files = imgs[n].orEmpty().filter { Regex("\\.(jpe?g|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(it) && !junk.containsMatchIn(it) }.take(30)
            lead[n]?.let { "File:" + it.replace('_', ' ') } to files
        }
    }

    /**
     * The lead picture of an article is not always a photo: for history topics, districts or lesser places it is
     * often a map, a plan or a drawing (a map at the top of "Council of …", say). Only real photos count:
     * a JPEG or WebP file (maps and drawings are SVG or PNG) whose name doesn't say it's a map, plan, logo and so on.
     */
    private fun leadOk(url: String): Boolean {
        val f = fileName(url)
        return Regex("\\.(jpe?g|webp)$").containsMatchIn(f) && !junk.containsMatchIn(f) && !mapWords.containsMatchIn(f)
    }

    /** Photos used inside the place's own Wikipedia article: chosen by editors, so they show the right place. */
    private fun articleImages(title: String): List<Img> {
        val r = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&generator=images&gimlimit=60" +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata&iiextmetadatafilter=DateTimeOriginal%7CCategories&iiurlwidth=960&titles=" + enc(title.replace('_', ' '))))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<Img>()
        pages.keys().forEach { k ->
            val pg = pages.getJSONObject(k)
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
            val t = pg.optString("title")
            if (usable(t, ii))
                out.add(Img(t, ii.optString("thumburl").ifBlank { ii.optString("url") }, good(ii)))
        }
        return out
    }

    private fun commons(term: String, limit: Int = 20): List<Img> {
        val r = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=$limit&gsrsearch=" +
            enc("filetype:bitmap $term") + "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata&iiextmetadatafilter=DateTimeOriginal%7CCategories&iiurlwidth=960"))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 999) }.mapNotNull { pg ->
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val t = pg.optString("title")
            if (usable(t, ii)) Img(t, ii.optString("thumburl").ifBlank { ii.optString("url") }, good(ii)) else null
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
    private fun tokens(name: String, local: Boolean = false) = plain(name).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in generic && !(local && it in genericLocal) }.toSet()

    /** The same building words in the local languages ("Kirche", "Palazzo", "Biserica"): never a place's name on their own. */
    private val genericLocal = setOf("kirche", "dom", "schloss", "platz", "palais", "strasse", "garten", "brucke", "turm", "haus", "kapelle", "kloster",
        "stift", "oper", "markt", "brunnen", "chiesa", "duomo", "palazzo", "piazza", "castello", "museo", "torre", "ponte", "giardino", "teatro",
        "galleria", "fontana", "santa", "santo", "maria", "biserica", "palatul", "piata", "muzeul", "parcul", "gradina", "teatrul", "catedrala",
        "manastirea", "kosciol", "katedra", "zamek", "rynek", "muzeum", "ulica", "brama", "wieza", "ogrod", "teatr", "bazylika", "kaplica",
        "sfantul", "sfanta", "iglesia", "catedral", "palacio", "plaza", "museu", "igreja", "eglise", "chateau", "place", "musee", "pont", "jardin",
        "kerk", "paleis", "plein", "kostel", "zamek", "namesti", "templom", "palota", "kirke", "slott", "kyrka", "torget", "crkva", "trg",
        "national", "nationale", "nazionale", "narodowe", "narodni", "nacional")

    // German spellings without umlauts count too: "Schoenbrunn" in a file name matches Schönbrunn.
    private fun matches(title: String, toks: Set<String>): Boolean {
        if (toks.isEmpty()) return false
        val t = plain(title).let { it + " " + it.replace("oe", "o").replace("ue", "u").replace("ae", "a") }
        return toks.any { t.contains(it) }
    }

    /** Distinctive words from the place's name and its Wikipedia title (e.g. "azizyie" for Azizyie Mosque). */
    private fun placeTokens(p: Place, title: String?) = tokens(p.name) + tokens(title?.replace('_', ' ').orEmpty())

    /**
     * Up to 6 photos of a sight: the article's own lead photo, other photos from that article, then the place's own
     * Commons category (every file in it shows this place), then strict search results.
     */
    private fun sightPhotoUrls(p: Place, articleChecked: Boolean = false): List<String> {
        val title = city.wiki[p.id]
        val picked = LinkedHashMap<String, String>() // file name -> url
        fun add(u: String) { val n = fileName(u); if (n.isNotBlank() && !picked.containsKey(n)) picked[n] = u }
        val qid = title?.let { t -> runCatching { qidOf(t) }.getOrNull() }
        val item = qid?.let { q -> runCatching { items(listOf(q))[q] }.getOrNull() }
        val cat = item?.cat
        val toks = placeTokens(p, title) + itemTokens(item)
        val l = lang
        val local = if (l != null && item?.local != null) runCatching { articles(l, listOf(item.local))[item.local] }.getOrNull() else null
        val linfo = if (l != null && local != null) runCatching { fileInfo(listOfNotNull(local.first) + local.second, l) }.getOrDefault(emptyMap()) else emptyMap()
        val photo = item?.photo?.let { f -> runCatching { fileInfo(listOf("File:" + f.replace('_', ' '))).values.firstOrNull() }.getOrNull() }
        var enLead: Pic? = null
        var enFiles = emptyList<Pair<String, Pic>>()
        if (title != null && !articleChecked) {
            enLead = leadImage(title)
            enFiles = articleImages(title).map { it.title to Pic(it.thumb, it.good) }
        }
        choose(enLead, enFiles, photo, local?.first?.let { linfo[it] }, local?.second.orEmpty().mapNotNull { f -> linfo[f]?.let { f to it } }, toks).forEach(::add)
        if (picked.size < 4 && cat != null) categoryPhotos(cat, toks).forEach(::add)
        if (picked.size < 3) {
            val term = city.commons[p.id] ?: ((item?.label?.ifBlank { null } ?: p.name.substringBefore(" (")) + " " + city.cityLocal)
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

/**
 * Street-level photos from Mapillary (free, CC BY-SA, may be saved on the phone), taken in the last 15 years
 * within 50 m of the place (30 m for places to eat), by a camera pointing at it. Panoramas are skipped. Needs an access token,
 * built into the app from the MAPILLARY_TOKEN secret; without one, places just get their Wikimedia photos.
 */
object Mapillary {
    private val token = BuildConfig.MAPILLARY_TOKEN.trim()
    val on get() = token.isNotEmpty()

    private class Shot(val url: String, val lat: Double, val lng: Double, val taken: Long, val dist: Double)

    fun near(p: Place, max: Int): List<String> {
        val dLat = 0.00065
        val dLng = dLat / Math.cos(Math.toRadians(p.lat))
        val since = java.time.LocalDate.now().minusYears(15).toString() + "T00:00:00Z"
        val url = "https://graph.mapillary.com/images?access_token=$token&limit=60" +
            "&fields=id,thumb_1024_url,captured_at,compass_angle,computed_compass_angle,computed_geometry,geometry,is_pano" +
            "&start_captured_at=$since&bbox=" + String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", p.lng - dLng, p.lat - dLat, p.lng + dLng, p.lat + dLat)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Waypoint/1.1 (personal Android city guide)")
        val body = try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val shots = (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            if (o.optBoolean("is_pano")) return@mapNotNull null
            val u = o.optString("thumb_1024_url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val g = (o.optJSONObject("computed_geometry") ?: o.optJSONObject("geometry"))?.optJSONArray("coordinates") ?: return@mapNotNull null
            val lng = g.getDouble(0); val lat = g.getDouble(1)
            val d = metres(lat, lng, p.lat, p.lng)
            // Close enough that the place fills the picture (places to eat: right in front of the door).
            if (d < 6 || d > (if (p.isFood) 30 else 50)) return@mapNotNull null
            // The camera must look straight at the place: its heading within 25° of the direction to it.
            val heading = if (o.has("computed_compass_angle")) o.optDouble("computed_compass_angle") else o.optDouble("compass_angle", Double.NaN)
            if (heading.isNaN()) return@mapNotNull null
            val off = Math.abs(((bearing(lat, lng, p.lat, p.lng) - heading) % 360 + 540) % 360 - 180)
            if (off > 25) return@mapNotNull null
            Shot(u, lat, lng, o.optLong("captured_at"), d)
        }
        // Newest first among the close ones, and not two from almost the same spot.
        val picked = ArrayList<Shot>()
        shots.sortedWith(compareBy<Shot> { (it.dist / 25).toInt() }.thenByDescending { it.taken }).forEach { s ->
            if (picked.size < max && picked.none { metres(it.lat, it.lng, s.lat, s.lng) < 12 }) picked.add(s)
        }
        return picked.map { it.url }
    }

    private fun metres(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(c - a); val dLng = Math.toRadians(d - b)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(c)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun bearing(a: Double, b: Double, c: Double, d: Double): Double {
        val f1 = Math.toRadians(a); val f2 = Math.toRadians(c); val dl = Math.toRadians(d - b)
        val y = Math.sin(dl) * Math.cos(f2)
        val x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
}
