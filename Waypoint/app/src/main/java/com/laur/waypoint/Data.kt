package com.laur.waypoint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// City data: each city is one JSON file in assets/cities. Adding a city means adding a file.

data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val min: Int = 0,
    val pri: Int = 2,
    val desc: String,
    val out: Boolean = false,
    val closed: List<Int> = emptyList(), // 0=Sun ... 6=Sat
    val kinds: List<String> = emptyList(), // coffee, lunch, dinner, bar, aperitivo
    val price: Int = 0,
) {
    val isFood get() = kinds.isNotEmpty()
}

data class Theme(val key: String, val name: String, val blurb: String, val stops: List<Place>)

data class Cost(val lo: Int, val hi: Int, val basis: String, val menu: List<Pair<String, Double>>)

data class MomentDef(val until: String, val k: String, val cats: List<String>, val kind: String?, val free: Boolean)

data class Rule(val dow: Int?, val stops: List<String>, val bold: String, val text: String)

data class FlagDef(val stripes: List<Long>, val vertical: Boolean, val emoji: String? = null)

data class Accent(val light: Long, val lightSoft: Long, val dark: Long, val darkSoft: Long, val darkFg: Long, val bar: Long)

class City(j: JSONObject) {
    val key = j.getString("key")
    /** Built on the phone from Wikipedia and OpenStreetMap (as opposed to a hand-made guide shipped with the app). */
    val generated = j.optBoolean("generated")
    val name = j.getString("name")
    val country = j.getString("country")
    val tagline = j.getString("tagline")
    val tz = j.getString("tz")
    val cur = j.getString("cur")
    val curAfter = j.getBoolean("curAfter")
    val cityLocal = j.getString("cityLocal")
    val cityEn = j.getString("cityEn")
    val inner = j.getString("inner")
    val transit = j.getString("transit")
    val flag = j.getJSONObject("flag").let { f -> FlagDef(f.getJSONArray("stripes").strings().map(::hex), f.getBoolean("vertical"), f.optString("emoji").ifBlank { null }) }
    val accent = j.getJSONObject("accent").let { a ->
        Accent(hex(a.getString("light")), hex(a.getString("lightSoft")), hex(a.getString("dark")), hex(a.getString("darkSoft")), hex(a.getString("darkFg")), hex(a.getString("bar")))
    }
    val moments = j.getJSONArray("moments").objects().map {
        MomentDef(it.getString("until"), it.getString("k"), it.getJSONArray("cats").strings(), it.optString("kind").ifBlank { null }, it.optBoolean("free"))
    }
    val rules = j.getJSONArray("rules").objects().map {
        Rule(if (it.has("dow")) it.getInt("dow") else null, it.optJSONArray("stops")?.strings() ?: emptyList(), it.getString("bold"), it.getString("text"))
    }
    val themes = j.getJSONArray("themes").objects().map { t ->
        Theme(t.getString("key"), t.getString("name"), t.getString("blurb"), t.getJSONArray("stops").objects().map(::place))
    }
    val themeMap = themes.associateBy { it.key }
    /** Only theme keys that really exist, so screens can look them up without crashing. */
    val themeOrder = j.getJSONArray("themeOrder").strings().filter { it in themeMap }.ifEmpty { themes.map { it.key } }
    val defOrder = j.getJSONArray("defOrder").strings().filter { it in themeMap }.ifEmpty { themeOrder }
    private val extra = j.getJSONArray("extra").objects().map(::place)
    val food = j.getJSONArray("food").objects().map(::place)
    val cost: Map<String, Cost> = j.getJSONObject("cost").let { o ->
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            Cost(a.getInt(0), a.getInt(1), a.getString(2), a.getJSONArray(3).arrays().map { it.getString(0) to it.getDouble(1) })
        }
    }
    val free = j.getJSONArray("free").strings().toSet()
    private val cat = j.getJSONObject("cat").strMap()
    val catLabel = j.getJSONObject("catLabel").strMap()
    val price = j.getJSONArray("price").strings()
    val wiki = j.getJSONObject("wiki").strMap()
    val commons = j.getJSONObject("commons").strMap()
    val holidays = j.getJSONObject("holidays").strMap()
    /** Always 12 entries (one per month) in HH:mm, so indexing by month is safe. */
    val sunsets = j.getJSONArray("sunsets").strings().let { s ->
        List(12) { m -> s.getOrNull(m)?.takeIf { Regex("\\d{1,2}:\\d{2}").matches(it) } ?: "18:00" }
    }
    val lit = j.getJSONArray("lit").strings().toSet()
    val landmarks = j.getJSONArray("landmarks").arrays().map { it.getString(0) to it.getString(1) }
    val filters = j.getJSONArray("filters").arrays().map { it.getString(0) to it.getString(1) }

    /** Every place once: extra places, then the first version from the themes, then food (same order as the web apps). */
    val places: List<Place> = run {
        val m = LinkedHashMap<String, Place>()
        extra.forEach { m[it.id] = it }
        themes.forEach { t -> t.stops.forEach { s -> if (!m.containsKey(s.id)) m[s.id] = s } }
        food.forEach { m[it.id] = it }
        m.values.toList()
    }
    val byId = places.associateBy { it.id }

    init {
        require(themes.isNotEmpty()) { "guide $key has no day plans" }
        require(moments.isNotEmpty()) { "guide $key has no moments" }
    }

    /** Middle of the city: the saved centre for built guides, otherwise the average of its places. */
    val centerLat: Double = if (j.has("centerLat")) j.getDouble("centerLat") else places.map { it.lat }.average()
    val centerLng: Double = if (j.has("centerLng")) j.getDouble("centerLng") else places.map { it.lng }.average()

    fun catOf(p: Place): String = when {
        p.kinds.isEmpty() -> cat[p.id] ?: "sight"
        "lunch" in p.kinds || "dinner" in p.kinds -> "food"
        "bar" in p.kinds || "aperitivo" in p.kinds -> "drinks"
        else -> "coffee"
    }

    fun money(n: Number): String {
        val d = n.toDouble()
        val s = if (d == Math.floor(d)) d.toLong().toString() else String.format(java.util.Locale.US, "%.2f", d)
        return if (curAfter) "$s $cur" else "$cur$s"
    }

    private fun place(o: JSONObject) = Place(
        id = o.getString("id"), name = o.getString("name"), lat = o.getDouble("lat"), lng = o.getDouble("lng"),
        min = o.optInt("min", 0), pri = o.optInt("pri", 2), desc = o.getString("desc"), out = o.optBoolean("out"),
        closed = o.optJSONArray("closed")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
        kinds = o.optJSONArray("kinds")?.strings() ?: emptyList(), price = o.optInt("price", 0),
    )

    companion object {
        /** Hand-made guides shipped with the app. */
        val HAND = emptyList<String>() // no built-in cities: every guide is built on the phone
        fun loadAll(ctx: Context): List<City> {
            val hand = HAND.map { k -> City(JSONObject(ctx.assets.open("cities/$k.json").bufferedReader().use { it.readText() })) }
            val built = (java.io.File(ctx.filesDir, "guides").listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }
                .mapNotNull { f -> runCatching { City(JSONObject(f.readText())) }.getOrNull() }
            return hand + built
        }
    }
}

private fun hex(s: String): Long = 0xFF000000L or s.removePrefix("#").toLong(16)
private fun JSONArray.strings() = List(length()) { getString(it) }
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONArray.arrays() = List(length()) { getJSONArray(it) }
private fun JSONObject.strMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

/** The city currently open. The screens read everything through here. */
object Trip {
    lateinit var city: City
    val CITY get() = city.cityLocal
    val CITY_EN get() = city.cityEn
    val TZ get() = city.tz
    val THEMES get() = city.themes
    val THEME_MAP get() = city.themeMap
    val THEME_ORDER get() = city.themeOrder
    val DEF_ORDER get() = city.defOrder
    val FOOD get() = city.food
    val COST get() = city.cost
    val FREE get() = city.free
    val CATLABEL get() = city.catLabel
    val PRICE get() = city.price
    val WIKI get() = city.wiki
    val COMMONS get() = city.commons
    val HOLIDAYS get() = city.holidays
    val SUNSETS get() = city.sunsets
    val LIT_AFTER_DARK get() = city.lit
    val LANDMARKS get() = city.landmarks
    val FILTERS get() = city.filters
    val PLACES get() = city.places
    val BY_ID get() = city.byId

    fun catOf(p: Place) = city.catOf(p)
    /** Label for a category; never crashes on a category the guide forgot to name. */
    fun label(cat: String) = city.catLabel[cat] ?: cat.replaceFirstChar { it.uppercase() }
    /** "€€" style price text, or "" when the place has none or the guide has no such level. */
    fun priceText(p: Place) = city.price.getOrNull(p.price).orEmpty()
    fun isFoodCat(c: String) = c == "food" || c == "coffee" || c == "drinks"
    fun isFree(p: Place) = p.id in city.free
    fun money(n: Number) = city.money(n)
}
