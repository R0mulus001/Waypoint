package com.laur.waypoint

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ---------- countries and their big cities (assets/europe.json) ----------

data class CityRef(val name: String, val wiki: String, val guide: String?)

class Country(j: JSONObject) {
    val code = j.getString("code")
    val name = j.getString("name")
    val cur = j.getString("cur")
    val curAfter = j.getBoolean("curAfter")
    val tz = j.getString("tz")
    val accent = j.getString("accent")
    val greet = j.getString("greet")
    val price: Map<String, Pair<Int, Int>> = j.getJSONObject("price").let { o -> o.keys().asSequence().associateWith { o.getJSONArray(it).let { a -> a.getInt(0) to a.getInt(1) } } }
    val cities = j.getJSONArray("cities").let { a ->
        List(a.length()) { a.getJSONObject(it).let { c -> CityRef(c.getString("name"), c.getString("wiki"), c.optString("guide").ifBlank { null }) } }
    }
    val emoji = flagEmoji(code)
}

fun flagEmoji(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

object Europe {
    lateinit var countries: List<Country>; private set
    fun load(ctx: Context) {
        val a = JSONArray(ctx.assets.open("europe.json").bufferedReader().use { it.readText() })
        countries = List(a.length()) { Country(a.getJSONObject(it)) }.sortedBy { it.name }
    }
    fun byCode(c: String) = countries.firstOrNull { it.code == c }
    /** The country a city guide belongs to (by its code, or by name for guides without one). */
    fun forCity(c: City): Country? = byCode(c.countryCode) ?: countries.firstOrNull { it.name == c.country }
}

fun slug(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("ł", "l").lowercase()
    .replace(Regex("[^a-z0-9]+"), "-").trim('-')

// ---------- network ----------

private const val UA = "Waypoint/1.0 (personal Android city guide)"

class BusyException(msg: String) : RuntimeException(msg)

/** GET with retries: busy servers (HTTP 429/5xx) and timeouts are tried again up to 3 times with growing waits. */
private fun httpGet(url: String): String {
    var last: Exception? = null
    repeat(4) { attempt ->
        if (attempt > 0) Thread.sleep(1500L * attempt * attempt)
        try { return httpGetOnce(url) } catch (e: BusyException) { last = e } catch (e: java.net.SocketTimeoutException) { last = e }
    }
    throw last ?: RuntimeException("request failed")
}

private fun httpGetOnce(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15000; c.readTimeout = 30000
    try {
        val code = c.responseCode
        if (code == 429 || code >= 500) throw BusyException("busy $code")
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}

private fun httpPost(url: String, body: String, readTimeout: Int = 60000, onOpen: (HttpURLConnection) -> Unit = {}): String {
    val c = URL(url).openConnection() as HttpURLConnection
    onOpen(c)
    c.requestMethod = "POST"; c.doOutput = true
    c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    c.connectTimeout = 10000; c.readTimeout = readTimeout
    try {
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code == 429 || code >= 500) throw BusyException("busy $code")
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

data class SearchHit(val name: String, val detail: String, val lat: Double, val lng: Double)

/** Online search for any city or town in a country (OpenStreetMap). */
suspend fun searchCities(country: Country, q: String): List<SearchHit> = withContext(Dispatchers.IO) {
    val a = JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=10&featuretype=settlement&accept-language=en&countrycodes=" +
        country.code.lowercase() + "&q=" + enc(q)))
    List(a.length()) { a.getJSONObject(it) }.map { o ->
        val dn = o.optString("display_name")
        SearchHit(o.optString("name").ifBlank { dn.substringBefore(",") }, dn.substringAfter(", ", "").take(80), o.getDouble("lat"), o.getDouble("lon"))
    }.distinctBy { Triple(it.name, it.lat, it.lng) } // same name and spot twice would also crash the result list
}

/** Writes a file in one go (temp file, then rename), so a killed app never leaves half a guide behind. */
private fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { writeText(text); tmp.delete() }
}

// ---------- sunset (NOAA approximation) ----------

fun sunsetLocal(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): String {
    fun rad(d: Double): Double = Math.toRadians(d)
    fun deg(r: Double): Double = Math.toDegrees(r)
    fun norm(x: Double, m: Double): Double {
        val r = x % m
        return if (r < 0) r + m else r
    }
    val n = date.dayOfYear
    val lngHour = lng / 15
    val t = n + (18 - lngHour) / 24
    val mA = 0.9856 * t - 3.289
    val l = norm(mA + 1.916 * sin(rad(mA)) + 0.020 * sin(rad(2 * mA)) + 282.634, 360.0)
    var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
    ra = (ra + floor(l / 90) * 90 - floor(ra / 90) * 90) / 15
    val sinDec = 0.39782 * sin(rad(l))
    val cosDec = cos(asin(sinDec))
    val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
    if (cosH < -1) return "22:30" // midnight sun
    if (cosH > 1) return "14:00"  // polar night
    val h = deg(acos(cosH)) / 15
    val ut = norm(h + ra - 0.06571 * t - 6.622 - lngHour, 24.0)
    val utc = LocalTime.of(ut.toInt() % 24, ((ut - ut.toInt()) * 60).toInt())
    val local = date.atTime(utc).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone)
    return String.format(Locale.US, "%02d:%02d", local.hour, local.minute)
}

// ---------- guide builder ----------

/** A built guide. [foodLater]: places to eat were still loading, so the guide was saved without them for now. */
data class Built(val file: File, val foodLater: Boolean)

class GuideBuilder(private val ctx: Context) {
    val dir = File(ctx.filesDir, "guides").apply { mkdirs() }
    /** Places-to-eat lookups still running for guides that were opened without them (see [refreshFood]). */
    private val lateFood = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<Eat>>>()

    private data class Sight(val pageId: Long, val title: String, val lat: Double, val lng: Double, val desc: String, val views: Long, val cat: String, val out: Boolean)
    private data class Eat(val id: String, val name: String, val lat: Double, val lng: Double, val kind: String, val desc: String, val score: Int)

    // Things that aren't sights, wherever they appear in the description...
    private val exclude = Regex(
        "station|railway|metro|subway|airport|school|gymnasium|university|college|academy|hospital|clinic|company|corporation|\\bbank\\b|hotel|" +
            "embassy|consulate|ministry|\\bcourt\\b|prison|stadium|football|sports|\\bclub\\b|skyscraper|office building|apartment|residential|" +
            "shopping cent|\\bmall\\b|cinema|radio|television|newspaper|publisher|institute|agency|organi[sz]ation|association|political party|" +
            "police|power station|factory|motorway|highway|list of|demolished|\\bband\\b|album|\\bfilm\\b|novel|electoral|constituency|diocese|" +
            // history, events and ideas have map pins too, but you can't visit them
            "regime|government|dictatorship|\\bwar\\b|battle|revolution|earthquake|history of|historical period|\\bera\\b|empire|dynasty|treaty|" +
            "festival|election|protest|disaster|siege|uprising|movement|\\bevent\\b|culture of|cuisine|language|dialect|ethnic|tradition|military|" +
            "basketball|handball|volleyball|rugby|competition|tournament|championship|stock exchange|murder|assassination|killing|" +
            "regicide|\\battack\\b|bombing|massacre|incident|\\bcrash\\b|\\bfire of\\b|riot|scandal|monarchy|kingdom|republic|principality|" +
            "duchy|sovereign state|historical state|former country|country in|union of",
        RegexOption.IGNORE_CASE)
    // ...and places that are areas rather than sights, when the description starts with them.
    private val area = Regex("^(the )?(capital|city|town|village|municipality|commune|district|borough|neighbou?rhood|quarter|county|region|province|" +
        "suburb|parish|street|road|avenue|area|human settlement|locality|administrative)", RegexOption.IGNORE_CASE)
    // Closed or delivery-only places, in the languages people tag them in.
    private val closedRe = Regex("delivery|take ?away|\\(closed|fechad|geschlossen|chiuso|ferm[ée]|cerrad|zamkni|uzavřen|stängt|lukket|suljettu",
        RegexOption.IGNORE_CASE)
    private val defunct = Regex("\\b1\\d{3}\\s*[–-]\\s*(1\\d{3}|20[01]\\d|202[0-5])\\b\\)?\\s*$|\\(\\s*1\\d{3}\\s*[–-]\\s*(1\\d{3}|20[0-2]\\d)\\s*\\)")
    private val museumRe = Regex("museum|gallery|galerie|exhibition|art collection", RegexOption.IGNORE_CASE)
    private val parkRe = Regex("park|garden|hill|mound|forest|lake|beach|viewpoint|botanical|zoo|river|island|mountain|lookout|promenade|waterfront", RegexOption.IGNORE_CASE)
    private val outRe = Regex("square|bridge|monument|statue|fountain|gate|memorial|column|obelisk|piazza|plaza|platz|market", RegexOption.IGNORE_CASE)

    /** Builds a full city guide and saves it on the phone. Calls [progress] with what it's doing. */
    suspend fun build(country: Country, name: String, wiki: String?, at: Pair<Double, Double>?, progress: (String) -> Unit): Built = withContext(Dispatchers.IO) {
        progress("Finding $name on the map")
        val (lat, lng) = at ?: locate(country, name, wiki) ?: throw RuntimeException("Couldn't find $name on the map.")

        // Places to eat (OpenStreetMap) and holidays come from other services, so they are fetched at the same time
        // as the sights instead of after them (the steps used to run one after another).
        val side = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var keepFood = false
        try {
            val food = side.async { runCatching { findFood(lat, lng) }.getOrDefault(emptyList()) }
            val hol = side.async { runCatching { holidays(country) }.getOrDefault(JSONObject()) }

            progress("Finding the top sights")
            val sights = findSights(lat, lng, wiki ?: name).take(50)
            if (sights.size < 3) throw RuntimeException("Couldn't find enough sights for $name. Try a bigger city nearby.")

            progress("Finding cafés, restaurants and bars")
            // The public map servers are often slow (15-35 s). Once the sights are in, wait at most 15 s more:
            // after that the guide opens without places to eat and they arrive in the background.
            val eats = withTimeoutOrNull(15_000) { food.await() }
            val key0 = country.code.lowercase() + "-" + slug(name)
            if (eats == null) { lateFood[key0] = food; keepFood = true }

            progress("Building day plans")
            val key = country.code.lowercase() + "-" + slug(name)
            val json = assemble(key, country, name, lat, lng, sights, eats ?: emptyList())

            progress("Adding holidays and sunset times")
            json.put("holidays", hol.await())
            val zone = ZoneId.of(country.tz)
            val year = LocalDate.now(zone).year
            json.put("sunsets", JSONArray((1..12).map { m -> sunsetLocal(LocalDate.of(year, m, 15), lat, lng, zone) }))

            val f = File(dir, "$key.json")
            City(json) // check the guide opens before saving it
            f.writeAtomically(json.toString())
            Built(f, foodLater = keepFood)
        } catch (e: Throwable) {
            keepFood = false
            lateFood.remove(country.code.lowercase() + "-" + slug(name))
            throw e
        } finally {
            // Leaving the screen, or an error, stops the side lookups too; a deferred food lookup keeps going.
            if (!keepFood) side.cancel()
        }
    }

    private fun locate(country: Country, name: String, wiki: String?): Pair<Double, Double>? {
        runCatching {
            val r = JSONObject(httpGet("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=coordinates&titles=" + enc(wiki ?: name)))
            val pages = r.getJSONObject("query").getJSONObject("pages")
            val pg = pages.getJSONObject(pages.keys().next())
            val c = pg.optJSONArray("coordinates")?.optJSONObject(0)
            if (c != null) return c.getDouble("lat") to c.getDouble("lon")
        }
        return runCatching {
            val a = JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=" + country.code.lowercase() + "&q=" + enc(name)))
            a.getJSONObject(0).let { it.getDouble("lat") to it.getDouble("lon") }
        }.getOrNull()
    }

    private fun findSights(lat: Double, lng: Double, cityTitle: String): List<Sight> {
        val g = JSONObject(httpGet("https://en.wikipedia.org/w/api.php?action=query&format=json&list=geosearch&gsnamespace=0&gsradius=10000&gslimit=500&gscoord=$lat%7C$lng"))
        val geo = g.getJSONObject("query").getJSONArray("geosearch").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val city = cityTitle.replace('_', ' ').lowercase()
        val out = ArrayList<Sight>()
        geo.chunked(50).forEach { chunk ->
            val ids = chunk.joinToString("%7C") { it.getLong("pageid").toString() }
            val r = runCatching { JSONObject(httpGet("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageviews%7Cdescription%7Cpageprops&pvipdays=30&ppprop=disambiguation&pageids=$ids")) }.getOrNull() ?: return@forEach
            val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return@forEach
            chunk.forEach { gs ->
                val pg = pages.optJSONObject(gs.getLong("pageid").toString()) ?: return@forEach
                if (pg.optJSONObject("pageprops")?.has("disambiguation") == true) return@forEach
                val title = gs.getString("title")
                if (title.lowercase() == city || title.startsWith("List of")) return@forEach
                val desc = pg.optString("description")
                if (desc.isBlank() && !museumRe.containsMatchIn(title)) return@forEach   // no description: usually not a sight
                val isMuseum = museumRe.containsMatchIn(desc) || museumRe.containsMatchIn(title)
                if (!isMuseum && (exclude.containsMatchIn(desc) || area.containsMatchIn(desc))) return@forEach
                if (defunct.containsMatchIn(desc)) return@forEach   // "(1918–1928)": no longer exists
                var views = 0L
                pg.optJSONObject("pageviews")?.let { pv -> pv.keys().forEach { k -> if (!pv.isNull(k)) views += pv.optLong(k) } }
                val cat = when { isMuseum -> "museum"; parkRe.containsMatchIn(desc) || parkRe.containsMatchIn(title) -> "park"; else -> "sight" }
                val outdoor = cat == "park" || outRe.containsMatchIn(desc) || outRe.containsMatchIn(title)
                out.add(Sight(gs.getLong("pageid"), title, gs.getDouble("lat"), gs.getDouble("lon"), desc, views, cat, outdoor))
            }
        }
        return out.filter { it.views > 0 }.sortedByDescending { it.views }
    }

    private val overpassServers = listOf(
        "https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
    )

    /**
     * Asks the public Overpass servers; the fastest answer wins. The first server gets a head start of a few seconds;
     * if it is slow the next one is asked too, and a busy or broken server is skipped at once instead of waiting.
     * Gives up after [budgetMs], so a busy map service can no longer hold the guide up for minutes.
     */
    private suspend fun overpass(query: String, budgetMs: Long = 60_000): String {
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val winner = CompletableDeferred<String>()
        val jobs = ArrayList<Job>()
        fun ask(url: String): Job = scope.launch {
            var conn: HttpURLConnection? = null
            coroutineContext.job.invokeOnCompletion { runCatching { conn?.disconnect() } }
            runCatching { httpPost(url, body, readTimeout = 35_000) { conn = it } }
                .onSuccess { winner.complete(it) }
                .onFailure { android.util.Log.w("Waypoint", "overpass " + url.substringAfter("//").substringBefore("/") + ": " + it.message) }
        }.also { jobs += it }
        try {
            return withTimeoutOrNull(budgetMs) {
                var got: String? = null
                for (round in 0 until 2) {
                    if (round > 0) delay(1500)
                    for (url in overpassServers) {
                        val job = ask(url)
                        // Head start for this server; move on early if it already failed.
                        val until = System.currentTimeMillis() + if (round == 0) 5000 else 8000
                        while (!winner.isCompleted && job.isActive && System.currentTimeMillis() < until) delay(100)
                        if (winner.isCompleted) break
                    }
                    while (!winner.isCompleted && jobs.any { it.isActive }) delay(100)
                    if (winner.isCompleted) { got = winner.await(); break }
                }
                got
            } ?: throw BusyException("overpass")
        } finally { scope.cancel() }
    }

    private suspend fun findFood(lat: Double, lng: Double): List<Eat> {
        // A box of about 1.5 km around the centre (cheaper for the servers than a radius search), a shorter server
        // timeout, and no sorting of the result (qt). Distance still ranks the places below.
        val dLat = 1.5 / 111.0
        val dLng = dLat / Math.cos(Math.toRadians(lat))
        val box = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lat - dLat, lng - dLng, lat + dLat, lng + dLng)
        val q = "[out:json][timeout:25][bbox:$box];nwr[\"amenity\"~\"^(restaurant|cafe|bar|pub)$\"][\"name\"];out tags center qt 600;"
        val txt = overpass(q)
        val els = JSONObject(txt).getJSONArray("elements")
        val all = (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: return@mapNotNull null
            if (closedRe.containsMatchIn(t.optString("name")) || t.has("disused:amenity") || t.optString("takeaway") == "only" ||
                t.optString("delivery") == "only") return@mapNotNull null
            val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.getDouble("lat") ?: return@mapNotNull null
            val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.getDouble("lon") ?: return@mapNotNull null
            val amenity = t.optString("amenity")
            val kind = when (amenity) { "restaurant" -> "food"; "cafe" -> "coffee"; else -> "drinks" }
            val cuisine = t.optString("cuisine").split(';').map { it.trim().replace('_', ' ') }.filter { it.isNotBlank() }.take(2)
            val what = when (amenity) { "restaurant" -> "restaurant"; "cafe" -> "café"; "pub" -> "pub"; else -> "bar" }
            var desc = if (cuisine.isNotEmpty()) cuisine.joinToString(" and ").replaceFirstChar { it.uppercase() } + " $what." else what.replaceFirstChar { it.uppercase() } + " in the centre."
            t.optString("opening_hours").takeIf { it.isNotBlank() && it.length < 60 }?.let { desc += " Hours: $it." }
            val d = km(lat, lng, la, lo)
            val score = (if (t.has("wikidata") || t.has("wikipedia")) 6 else 0) + (if (t.has("website") || t.has("contact:website")) 3 else 0) +
                (if (cuisine.isNotEmpty()) 2 else 0) + (if (t.has("opening_hours")) 2 else 0) - (d * 3).toInt()
            Eat("o" + e.optString("type").take(1) + e.getLong("id"), t.getString("name"), la, lo, kind, desc, score)
        }.distinctBy { it.name.lowercase() }
        fun top(k: String, n: Int) = all.filter { it.kind == k }.sortedByDescending { it.score }.take(n)
        return top("food", 18) + top("coffee", 8) + top("drinks", 8)
    }

    private fun foodJson(country: Country, eats: List<Eat>): Pair<JSONArray, JSONObject> {
        val cost = JSONObject()
        val basis = mapOf("food" to "a main course and a drink", "coffee" to "coffee and something sweet", "drinks" to "two drinks")
        eats.forEach { e ->
            val (lo, hi) = country.price[e.kind] ?: (0 to 0)
            cost.put(e.id, JSONArray(listOf(lo, hi, basis.getValue(e.kind) + ", typical for " + country.name, JSONArray())))
        }
        val food = JSONArray(eats.map { e ->
            JSONObject().apply {
                put("id", e.id); put("name", e.name); put("lat", e.lat); put("lng", e.lng); put("desc", e.desc)
                put("kinds", JSONArray(when (e.kind) { "food" -> listOf("lunch", "dinner"); "coffee" -> listOf("coffee"); else -> listOf("bar") }))
            }
        })
        return food to cost
    }

    /** Fetches cafés, restaurants and bars again for a built guide (when they couldn't load the first time). */
    suspend fun refreshFood(city: City): File = withContext(Dispatchers.IO) {
        val f = File(dir, "${city.key}.json")
        val j = JSONObject(f.readText())
        val country = Europe.byCode(j.getString("countryCode")) ?: throw RuntimeException("Unknown country")
        // A lookup still running from when the guide was built is used first, so nothing is asked twice.
        val late = lateFood.remove(city.key)?.let { d -> runCatching { d.await() }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val eats = late ?: findFood(j.getDouble("centerLat"), j.getDouble("centerLng"))
        if (eats.isEmpty()) throw RuntimeException("Couldn't find places to eat nearby.")
        val (food, cost) = foodJson(country, eats)
        j.put("food", food); j.put("cost", cost)
        val filters = j.getJSONArray("filters")
        if ((0 until filters.length()).none { filters.getJSONArray(it).getString(0) == "drinks" } && eats.any { it.kind == "drinks" })
            filters.put(JSONArray(listOf("drinks", "Bars")))
        f.writeAtomically(j.toString())
        f
    }

    private fun holidays(country: Country): JSONObject {
        val o = JSONObject()
        val y = LocalDate.now(ZoneId.of(country.tz)).year
        listOf(y, y + 1).forEach { yr ->
            // One year failing (or a country the service doesn't know, like Kosovo) must not drop the other.
            val a = runCatching { JSONArray(httpGet("https://date.nager.at/api/v3/PublicHolidays/$yr/${country.code}")) }.getOrNull() ?: return@forEach
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                // Full date as the key: Easter and other moving holidays fall on different days each year.
                if (h.optBoolean("global", true)) o.put(h.getString("date"), h.optString("name").ifBlank { h.optString("localName") })
            }
        }
        return o
    }

    private fun color(hex: String) = hex.removePrefix("#").toLong(16)
    private fun mix(a: Long, b: Long, t: Double): String {
        fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toDouble()
        val r = (ch(a, 16) * (1 - t) + ch(b, 16) * t).toInt(); val g = (ch(a, 8) * (1 - t) + ch(b, 8) * t).toInt(); val bl = (ch(a, 0) * (1 - t) + ch(b, 0) * t).toInt()
        return String.format(Locale.US, "#%02X%02X%02X", r, g, bl) // Locale.US: some phone languages use non-Latin digits
    }

    /** Orders stops as a walk: start at the first, always go to the nearest one left. */
    private fun <T> route(items: List<T>, lat: (T) -> Double, lng: (T) -> Double): List<T> {
        if (items.size < 3) return items
        val left = items.drop(1).toMutableList(); val seq = mutableListOf(items.first())
        while (left.isNotEmpty()) {
            val cur = seq.last()
            val n = left.minBy { km(lat(cur), lng(cur), lat(it), lng(it)) }
            left.remove(n); seq.add(n)
        }
        return seq
    }

    private fun clusters(s: List<Sight>, k: Int): List<List<Sight>> {
        if (s.size < 6 || k < 2) return emptyList()
        val centers = mutableListOf(s.first().lat to s.first().lng)
        while (centers.size < k) centers.add(s.maxBy { p -> centers.minOf { km(it.first, it.second, p.lat, p.lng) } }.let { it.lat to it.lng })
        var groups: List<List<Sight>> = emptyList()
        repeat(12) {
            groups = s.groupBy { p -> centers.indices.minBy { km(centers[it].first, centers[it].second, p.lat, p.lng) } }.values.toList()
            groups.forEachIndexed { i, g -> if (i < centers.size) centers[i] = g.map { it.lat }.average() to g.map { it.lng }.average() }
        }
        return groups.filter { it.size >= 3 }.map { g -> g.sortedByDescending { it.views } }.sortedByDescending { g -> g.sumOf { it.views } }
    }

    private fun assemble(key: String, country: Country, name: String, lat: Double, lng: Double, sights: List<Sight>, eats: List<Eat>): JSONObject {
        fun sid(s: Sight) = "w${s.pageId}"
        val rank = sights.withIndex().associate { (i, s) -> s.pageId to i }
        fun place(s: Sight, minOverride: Int? = null) = JSONObject().apply {
            put("id", sid(s)); put("name", s.title.substringBefore(" (")); put("lat", s.lat); put("lng", s.lng)
            put("desc", (s.desc.ifBlank { when (s.cat) { "museum" -> "Museum"; "park" -> "Park"; else -> "Sight" } }).replaceFirstChar { it.uppercase() } + ".")
            put("min", minOverride ?: when (s.cat) { "museum" -> 90; "park" -> 40; else -> if ((rank[s.pageId] ?: 99) < 5) 45 else 30 })
            put("pri", when (rank[s.pageId] ?: 99) { in 0..9 -> 1; in 10..24 -> 2; else -> 3 })
            if (s.out) put("out", true)
        }
        val themes = JSONArray()
        val order = ArrayList<String>()
        fun theme(k: String, n: String, blurb: String, stops: List<Sight>) {
            if (stops.size < 2) return
            themes.put(JSONObject().apply {
                put("key", k); put("name", n); put("blurb", blurb)
                put("stops", JSONArray(route(stops, { it.lat }, { it.lng }).map { place(it) }))
            }); order.add(k)
        }
        fun short(s: String) = s.substringBefore(" (").let { if (it.length > 26) it.take(24).trimEnd() + "…" else it }
        val top = sights.take(8)
        theme("top", "Highlights", top.take(3).joinToString(", ") { short(it.title) }, top)
        val k = (sights.size / 9).coerceIn(2, 5)
        clusters(sights.take(40), k).forEachIndexed { i, g ->
            val stops = g.take(7)
            theme("area$i", "Around " + short(stops.first().title), stops.drop(1).take(2).joinToString(", ") { short(it.title) }, stops)
        }
        theme("museums", "Museums & galleries", "The most visited museums", sights.filter { it.cat == "museum" }.take(6))
        theme("parks", "Parks & views", "Green spaces and lookouts", sights.filter { it.cat == "park" }.take(6))

        val acc = color(country.accent)
        val hasBars = eats.any { it.kind == "drinks" }
        val moments = JSONArray(listOfNotNull(
            mapOf("until" to "10:30", "k" to country.greet, "cats" to listOf("coffee")),
            mapOf("until" to "12:30", "k" to "Late morning", "cats" to listOf("sight", "park", "museum")),
            mapOf("until" to "14:30", "k" to "Lunchtime", "cats" to listOf("food"), "kind" to "lunch"),
            mapOf("until" to "16:30", "k" to "Afternoon", "cats" to listOf("sight", "park", "museum")),
            mapOf("until" to "18:00", "k" to "Coffee break", "cats" to listOf("coffee")),
            if (hasBars) mapOf("until" to "20:00", "k" to "Drinks", "cats" to listOf("drinks")) else null,
            mapOf("until" to "22:30", "k" to "Evening", "cats" to listOf("food"), "kind" to "dinner"),
            mapOf("until" to "24:00", "k" to "Late", "cats" to listOf(if (hasBars) "drinks" else "sight")),
        ).map { JSONObject(it) })
        val (foodJson, cost) = foodJson(country, eats)
        val filters = mutableListOf(listOf("all", "All"), listOf("sight", "Sights"), listOf("museum", "Museums"), listOf("food", "Eat"), listOf("coffee", "Cafés"))
        if (hasBars) filters.add(listOf("drinks", "Bars"))
        filters.add(listOf("park", "Parks & views"))

        return JSONObject().apply {
            put("key", key); put("name", name); put("country", country.name); put("generated", true)
            put("countryCode", country.code); put("centerLat", lat); put("centerLng", lng)
            put("tagline", sights.take(3).joinToString(", ") { short(it.title) })
            put("tz", country.tz); put("cur", country.cur); put("curAfter", country.curAfter)
            put("cityLocal", name); put("cityEn", name); put("inner", "interior"); put("transit", "public transport")
            put("flag", JSONObject().apply { put("emoji", country.emoji); put("stripes", JSONArray(listOf(country.accent))); put("vertical", false) })
            put("accent", JSONObject().apply {
                put("light", country.accent); put("lightSoft", mix(acc, 0xFFFFFF, 0.9)); put("dark", mix(acc, 0xFFFFFF, 0.45))
                put("darkSoft", mix(acc, 0x131518, 0.75)); put("darkFg", "#0D0F12"); put("bar", mix(acc, 0x000000, 0.15))
            })
            put("moments", moments); put("rules", JSONArray())
            put("themes", themes); put("themeOrder", JSONArray(order)); put("defOrder", JSONArray(order))
            put("extra", JSONArray(sights.map { place(it) })); put("food", foodJson); put("cost", cost)
            put("free", JSONArray()); put("cat", JSONObject(sights.filter { it.cat != "sight" }.associate { sid(it) to it.cat }))
            put("catLabel", JSONObject(mapOf("sight" to "Sight", "museum" to "Museum", "park" to "Park & view", "food" to "Restaurant", "coffee" to "Café", "drinks" to "Bar")))
            put("price", JSONArray(listOf("", "", "", "")))
            put("wiki", JSONObject(sights.associate { sid(it) to it.title.replace(' ', '_') })); put("commons", JSONObject())
            put("lit", JSONArray()); put("landmarks", JSONArray(sights.take(8).map { JSONArray(listOf(sid(it), short(it.title))) }))
            put("filters", JSONArray(filters.map { JSONArray(it) }))
        }
    }
}
