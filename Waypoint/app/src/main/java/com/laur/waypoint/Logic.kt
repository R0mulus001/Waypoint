package com.laur.waypoint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

data class Now(val key: String, val mins: Int, val month: Int)

val ZONE: ZoneId get() = ZoneId.of(Trip.TZ)
val DOW = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
val DOW_PLURAL = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")
val MON = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

fun nowInTrip(): Now {
    val z = ZonedDateTime.now(ZONE)
    return Now(z.toLocalDate().toString(), z.hour * 60 + z.minute, z.monthValue - 1)
}

/** "HH:mm" to minutes since midnight; anything malformed counts as end of day instead of crashing. */
fun toMin(s: String): Int {
    val p = s.split(":")
    val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return 24 * 60
    val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return h * 60 + m
}

fun km(a: LatLng, b: Place): Double = km(a.lat, a.lng, b.lat, b.lng)
fun km(alat: Double, alng: Double, blat: Double, blng: Double): Double {
    val r = Math.PI / 180
    val dl = (blat - alat) * r; val dg = (blng - alng) * r
    val x = sin(dl / 2) * sin(dl / 2) + cos(alat * r) * cos(blat * r) * sin(dg / 2) * sin(dg / 2)
    return 2 * 6371 * asin(sqrt(x))
}

data class Dist(val d: Double, val far: Boolean, val text: String)

fun distInfo(here: LatLng?, p: Place): Dist? {
    here ?: return null
    val d = km(here, p)
    val walk = (d * 1.3 * 13).roundToInt()
    val far = walk > 25
    val tr = (10 + d * 3).roundToInt()
    val m = (d * 100).roundToInt() * 10
    val where = if (d < 0.08) "You're here" else (if (m < 1000) "$m m" else String.format(Locale.US, "%.1f km", d)) +
        " · " + (if (far) "about $tr min by ${Trip.city.transit}" else "$walk min walk")
    return Dist(d, far, where)
}

fun mapsUrl(here: LatLng?, p: Place): String {
    val far = distInfo(here, p)?.far == true
    return "https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lng}&travelmode=" + (if (far) "transit" else "walking")
}

fun dur(m: Int) = if (m >= 60) "${m / 60} h" + (if (m % 60 != 0) " ${m % 60} min" else "") else "$m min"

fun visitedWhen(at: Long, city: City = Trip.city): String {
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.of(city.tz))
    val pattern = if (z.year == thisYear()) "EEE d MMM, HH:mm" else "EEE d MMM yyyy, HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.UK).format(z)
}

fun visitedDayKey(at: Long, city: City = Trip.city): String = Instant.ofEpochMilli(at).atZone(ZoneId.of(city.tz)).toLocalDate().toString()
fun dayTitle(key: String): String {
    val d = LocalDate.parse(key)
    return DateTimeFormatter.ofPattern(if (d.year == thisYear()) "EEEE d MMMM" else "EEEE d MMMM yyyy", Locale.UK).format(d)
}

// ---------- right-now suggestion ----------

data class Moment(val k: String, val cats: List<String>, val kind: String? = null, val free: Boolean = false)

fun moment(m: Int): Moment {
    val d = Trip.city.moments.firstOrNull { m < toMin(it.until) } ?: Trip.city.moments.last()
    return Moment(d.k, d.cats, d.kind, d.free)
}

fun afterDark(now: Now) = now.mins > toMin(Trip.SUNSETS[now.month]) - 15

fun heroPick(st: TripState, now: Now, here: LatLng?, skipped: Set<String>): Pair<Moment, Place?> {
    val mo = moment(now.mins)
    val c = Trip.PLACES.filter { p ->
        Trip.catOf(p) in mo.cats && (!mo.free || Trip.isFree(p)) && (mo.kind == null || p.kinds.isEmpty() || mo.kind in p.kinds) &&
            !st.isSeen(p.id) && p.id !in skipped && !(p.out && afterDark(now) && p.id !in Trip.LIT_AFTER_DARK)
    }.filter { val i = distInfo(here, it); i == null || i.d >= 0.08 }
    return mo to sortNear(c, here).firstOrNull()
}

/** Closest first; with no location, most important first (priority 1 before 2 and 3, otherwise the guide's order). */
fun sortNear(list: List<Place>, here: LatLng?): List<Place> = if (here == null) list.sortedBy { it.pri } else list.sortedBy { km(here, it) }

// ---------- trip days & plans ----------

data class TripDay(val key: String, val dow: Int, val d: Int, val m: Int, val y: Int = LocalDate.now().year)

/** The year is shown only when it isn't this year. */
private fun thisYear() = LocalDate.now().year

fun tripDays(start: String, len: Int): List<TripDay> {
    val s = runCatching { LocalDate.parse(start) }.getOrElse { LocalDate.now(ZONE) }
    return (0 until len).map { i ->
        val d = s.plusDays(i.toLong())
        TripDay(d.toString(), d.dayOfWeek.value % 7, d.dayOfMonth, d.monthValue - 1, d.year)
    }
}

fun tripLabel(days: List<TripDay>): String {
    val a = days.first(); val b = days.last()
    val now = thisYear()
    fun day(t: TripDay, withYear: Boolean) = "${t.d} ${MON[t.m]}" + if (withYear) " ${t.y}" else ""
    return when {
        a.key == b.key -> day(a, a.y != now)
        a.y != b.y -> day(a, true) + " to " + day(b, true)          // 30 December 2026 to 2 January 2027
        else -> day(a, false) + " to " + day(b, a.y != now)          // 2 November to 5 November 2027
    }
}

/** The theme picked for a day, or the default rotation. A saved pick that no longer exists in the guide is ignored. */
fun themeOf(st: TripState, days: List<TripDay>, key: String): String =
    st.themeByDay[key]?.takeIf { it in Trip.THEME_MAP } ?: Trip.DEF_ORDER[maxOf(0, days.indexOfFirst { it.key == key }) % Trip.DEF_ORDER.size]

fun planStops(st: TripState, days: List<TripDay>, day: TripDay): List<Place> {
    val tk = themeOf(st, days, day.key)
    val stops = Trip.THEME_MAP[tk]?.stops ?: return emptyList()
    val ord = st.orderByPlan[day.key + ":" + tk] ?: return stops
    val pos = ord.withIndex().associate { (i, id) -> id to i }
    return stops.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
}

/** Notes for a day as (bold part, rest) pairs. */
fun dayNotes(day: TripDay, stops: List<Place>): List<Pair<String, String>> {
    val n = ArrayList<Pair<String, String>>()
    (Trip.HOLIDAYS[day.key] ?: Trip.HOLIDAYS[day.key.substring(5)])?.let { n.add(it to " is a public holiday: some shops and museums close or open shorter hours.") }
    stops.forEach { s -> if (day.dow in s.closed) n.add(s.name to " is usually closed on ${DOW_PLURAL[day.dow]}.") }
    Trip.city.rules.forEach { r ->
        val hit = (r.dow != null && r.dow == day.dow) || (r.stops.isNotEmpty() && stops.any { it.id in r.stops })
        if (hit) n.add(r.bold to r.text)
    }
    if (stops.any { it.out }) n.add("" to "Do the outdoor stops before about ${Trip.SUNSETS[day.m]}, when it gets dark.")
    return n
}

fun reorderFromHere(stops: List<Place>, st: TripState, here: LatLng): List<String> {
    val left = stops.filter { !st.isSeen(it.id) }.toMutableList()
    val seq = stops.filter { st.isSeen(it.id) }.map { it.id }.toMutableList()
    var cur = here
    while (left.isNotEmpty()) {
        val c = cur
        left.sortBy { km(c, it) }
        val n = left.removeAt(0); seq.add(n.id); cur = LatLng(n.lat, n.lng)
    }
    return seq
}

fun eatNear(ref: Place): List<Place> {
    val r = LatLng(ref.lat, ref.lng)
    val meals = Trip.FOOD.filter { "lunch" in it.kinds || "dinner" in it.kinds }.sortedBy { km(r, it) }.take(2)
    // A café that also does lunch must not be listed twice (duplicate list keys crash the app).
    val coffee = Trip.FOOD.filter { "coffee" in it.kinds && it !in meals }.sortedBy { km(r, it) }.take(1)
    return (meals + coffee).distinctBy { it.id }
}
