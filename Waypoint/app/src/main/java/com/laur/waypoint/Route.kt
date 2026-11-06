package com.laur.waypoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The real walking route to a place, from OpenStreetMap's public foot router (routing.openstreetmap.de):
 * distance along streets and the walking time, instead of a straight line times a guess.
 * Offline, or if the router doesn't answer, the screens fall back to the estimate from [distInfo].
 */
object Route {
    data class Walk(val metres: Int, val mins: Int)

    private val cache = ConcurrentHashMap<String, Walk>()

    suspend fun walk(from: LatLng, p: Place): Walk? = withContext(Dispatchers.IO) {
        // Same spot to within ~10 m counts as the same start, so moving a little doesn't ask again.
        val key = String.format(Locale.US, "%.4f,%.4f>%.5f,%.5f", from.lat, from.lng, p.lat, p.lng)
        cache[key]?.let { return@withContext it }
        val d = km(from, p)
        if (d < 0.08 || d > 15) return@withContext null // you're there, or too far to walk
        runCatching {
            val u = String.format(Locale.US, "https://routing.openstreetmap.de/routed-foot/route/v1/foot/%.6f,%.6f;%.6f,%.6f?overview=false&alternatives=false",
                from.lng, from.lat, p.lng, p.lat)
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Waypoint/1.1 (personal Android city guide; https://github.com/R0mulus001/Waypoint)")
            c.connectTimeout = 8000; c.readTimeout = 10000
            val body = try {
                if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            val j = JSONObject(body)
            if (j.optString("code") != "Ok") throw RuntimeException(j.optString("code"))
            val r = j.getJSONArray("routes").getJSONObject(0)
            val m = r.getDouble("distance"); val s = r.getDouble("duration")
            // A route several times longer than the straight line means the router snapped to the wrong side of something.
            if (m <= 0 || m > d * 1000 * 4 + 300) throw RuntimeException("odd route")
            Walk(m.roundToInt(), (s / 60).roundToInt().coerceAtLeast(1))
        }.onFailure { android.util.Log.w("Waypoint", "route ${p.id}: ${it.message}") }.getOrNull()?.also { cache[key] = it }
    }

    private val driveCache = ConcurrentHashMap<String, Walk>()

    /** The road trip by car from far away (another town or country), from OpenStreetMap's public car router. */
    suspend fun drive(from: LatLng, p: Place): Walk? = withContext(Dispatchers.IO) {
        // Within ~1 km counts as the same start: a moving phone doesn't ask again.
        val key = String.format(Locale.US, "%.2f,%.2f>%.4f,%.4f", from.lat, from.lng, p.lat, p.lng)
        driveCache[key]?.let { return@withContext it }
        val d = km(from, p)
        runCatching {
            val u = String.format(Locale.US, "https://routing.openstreetmap.de/routed-car/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false&alternatives=false",
                from.lng, from.lat, p.lng, p.lat)
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Waypoint/1.1 (personal Android city guide; https://github.com/R0mulus001/Waypoint)")
            c.connectTimeout = 8000; c.readTimeout = 20000
            val body = try {
                if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            val j = JSONObject(body)
            if (j.optString("code") != "Ok") throw RuntimeException(j.optString("code"))
            val r = j.getJSONArray("routes").getJSONObject(0)
            val m = r.getDouble("distance"); val s = r.getDouble("duration")
            if (m <= 0 || m > d * 1000 * 3 + 5000) throw RuntimeException("odd route")
            Walk(m.roundToInt(), (s / 60).roundToInt().coerceAtLeast(1))
        }.onFailure { android.util.Log.w("Waypoint", "drive ${p.id}: ${it.message}") }.getOrNull()?.also { driveCache[key] = it }
    }

    /** "1,046 km · about 10 h 40 min by car" + where it's from; while it loads or offline, the straight-line distance. */
    fun driveText(from: LatLng, p: Place, drive: Walk?, tried: Boolean): Pair<String, String> {
        fun kmText(k: Double) = String.format(Locale.US, "%,d km", k.roundToInt().coerceAtLeast(1))
        if (drive == null) return "${kmText(km(from, p))} away" to
            (if (tried) "From your location, in a straight line (no route without internet)" else "From your location · finding the road route…")
        // Long trips rounded to 10 minutes: the router's times are a guide, not a promise.
        val mins = if (drive.mins > 120) (drive.mins / 10.0).roundToInt() * 10 else drive.mins
        return "${kmText(drive.metres / 1000.0)} · about ${dur(mins)} by car" to "From your location, by road"
    }

    /** "1.3 km · 18 min walk", from the real route when there is one, otherwise the estimate. */
    fun text(from: LatLng, p: Place, walk: Walk?): String {
        val est = distInfo(from, p) ?: return ""
        if (est.d < 0.08) return "You're here"
        walk ?: return est.text
        val dist = if (walk.metres < 1000) "${(walk.metres / 10.0).roundToInt() * 10} m" else String.format(Locale.US, "%.1f km", walk.metres / 1000.0)
        return if (walk.mins <= 25) "$dist · ${walk.mins} min walk"
        else "$dist · ${dur(walk.mins)} walk, or about ${(10 + est.d * 3).roundToInt()} min by ${Trip.city.transit}"
    }
}
