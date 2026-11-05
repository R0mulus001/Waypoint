package com.laur.waypoint

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers lives in one SQLite database on the phone. */
/** One database per city ("city_krakow.db" and so on). */
class TripDb(ctx: Context, cityKey: String) : SQLiteOpenHelper(ctx, "city_$cityKey.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE visited(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY, text TEXT NOT NULL, stars INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE photos(id TEXT NOT NULL, idx INTEGER NOT NULL, file TEXT NOT NULL, PRIMARY KEY(id, idx))")
        db.execSQL("CREATE TABLE info(id TEXT PRIMARY KEY, extract TEXT NOT NULL, page TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun <T> query(sql: String, args: Array<String> = emptyArray(), row: (android.database.Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    fun put(table: String, values: ContentValues) {
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(table: String, id: String) {
        writableDatabase.delete(table, "id=?", arrayOf(id))
    }

    fun kvGet(k: String): String? = query("SELECT v FROM kv WHERE k=?", arrayOf(k)) { it.getString(0) }.firstOrNull()
    fun kvPut(k: String, v: String) = put("kv", ContentValues().apply { put("k", k); put("v", v) })
}

data class Note(val text: String = "", val stars: Int = 0) {
    val isEmpty get() = text.isBlank() && stars == 0
}

/** App state backed by the database. Compose redraws whenever these change. */
class TripState(val db: TripDb) {
    val visited = mutableStateMapOf<String, Long>()
    val notes = mutableStateMapOf<String, Note>()
    val themeByDay = mutableStateMapOf<String, String>()
    val orderByPlan = mutableStateMapOf<String, List<String>>()

    private var _tab by mutableStateOf(db.kvGet("tab") ?: "near")
    val tab get() = _tab
    private var _filter by mutableStateOf(db.kvGet("filter") ?: "free")
    val filter get() = _filter
    private var _manual by mutableStateOf(db.kvGet("manual") ?: "")
    val manual get() = _manual
    private var _showSeen by mutableStateOf(db.kvGet("showSeen") == "1")
    val showSeen get() = _showSeen
    private var _start by mutableStateOf(db.kvGet("start") ?: "")
    val start get() = _start
    private var _len by mutableStateOf(db.kvGet("len")?.toIntOrNull() ?: 4)
    val len get() = _len
    private var _day by mutableStateOf(db.kvGet("day") ?: "")
    val day get() = _day
    private var _onboarded by mutableStateOf(db.kvGet("onboarded") == "1")
    val onboarded get() = _onboarded
    fun setOnboarded() { _onboarded = true; db.kvPut("onboarded", "1") }
    val themeMode get() = Hub.themeMode

    init {
        db.query("SELECT id, at FROM visited") { it.getString(0) to it.getLong(1) }.forEach { visited[it.first] = it.second }
        db.query("SELECT id, text, stars FROM notes") { it.getString(0) to Note(it.getString(1), it.getInt(2)) }.forEach { notes[it.first] = it.second }
        db.kvGet("themes")?.let { s -> runCatching { val o = JSONObject(s); o.keys().forEach { themeByDay[it] = o.getString(it) } } }
        db.kvGet("orders")?.let { s ->
            runCatching {
                val o = JSONObject(s)
                o.keys().forEach { k -> val a = o.getJSONArray(k); orderByPlan[k] = List(a.length()) { a.getString(it) } }
            }
        }
    }

    fun isSeen(id: String) = visited.containsKey(id)
    fun toggleSeen(id: String) {
        if (visited.remove(id) != null) db.delete("visited", id)
        else {
            val now = System.currentTimeMillis()
            visited[id] = now
            db.put("visited", ContentValues().apply { put("id", id); put("at", now) })
        }
    }

    /** Puts a visit back with its original time (used by Undo). */
    fun restoreSeen(id: String, at: Long) {
        visited[id] = at
        db.put("visited", ContentValues().apply { put("id", id); put("at", at) })
    }

    /** Removes a visit whatever its current state (Undo of "marked as visited" must never re-add it). */
    fun unsee(id: String) {
        if (visited.remove(id) != null) db.delete("visited", id)
    }

    fun note(id: String) = notes[id] ?: Note()
    fun setNote(id: String, n: Note) {
        if (n.isEmpty) { notes.remove(id); db.delete("notes", id) }
        else { notes[id] = n; db.put("notes", ContentValues().apply { put("id", id); put("text", n.text); put("stars", n.stars) }) }
    }

    fun setTab(v: String) { _tab = v; db.kvPut("tab", v) }
    fun setFilter(v: String) { _filter = v; db.kvPut("filter", v) }
    fun setManual(v: String) { _manual = v; db.kvPut("manual", v) }
    fun setShowSeen(v: Boolean) { _showSeen = v; db.kvPut("showSeen", if (v) "1" else "0") }
    fun setStart(v: String) { _start = v; db.kvPut("start", v) }
    fun setLen(v: Int) { _len = v; db.kvPut("len", v.toString()) }
    fun setDay(v: String) { _day = v; db.kvPut("day", v) }
    fun setThemeMode(v: String) = Hub.setThemeMode(v)

    fun setTheme(dayKey: String, theme: String) {
        themeByDay[dayKey] = theme
        db.kvPut("themes", JSONObject(themeByDay.toMap()).toString())
    }

    fun setOrder(planKey: String, ids: List<String>) {
        orderByPlan[planKey] = ids
        val o = JSONObject(); orderByPlan.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        db.kvPut("orders", o.toString())
    }
}


/** App-wide settings shared by all cities: which city is open, first-run done, light/dark choice. */
object Hub {
    private lateinit var prefs: android.content.SharedPreferences
    var city by mutableStateOf(""); private set
    var onboarded by mutableStateOf(false); private set
    private var _themeMode by mutableStateOf("auto")
    val themeMode get() = _themeMode

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("waypoint", Context.MODE_PRIVATE)
        city = prefs.getString("city", "") ?: ""
        onboarded = prefs.getBoolean("onboarded", false)
        _themeMode = prefs.getString("themeMode", "auto") ?: "auto"
        trips.clear()
        trips.addAll((prefs.getString("trips", null) ?: City.HAND.joinToString(",")).split(",").filter { it.isNotBlank() })
    }
    fun open(key: String) { city = key; prefs.edit().putString("city", key).apply() }

    /** Cities on the My trips screen, in the order they were added. Starts with the three hand-made guides. */
    val trips = androidx.compose.runtime.mutableStateListOf<String>()
    private fun saveTrips() = prefs.edit().putString("trips", trips.joinToString(",")).apply()
    fun addTrip(key: String) { if (key !in trips) { trips.add(key); saveTrips() } }
    fun removeTrip(key: String) { trips.remove(key); saveTrips() }
    /** Swaps two trips' places in your own order (used by the arrows on My trips). */
    fun swapTrips(a: String, b: String) {
        val i = trips.indexOf(a); val j = trips.indexOf(b)
        if (i < 0 || j < 0) return
        trips[i] = b; trips[j] = a; saveTrips()
    }
    fun setOnboarded() { onboarded = true; prefs.edit().putBoolean("onboarded", true).apply() }
    fun setThemeMode(v: String) { _themeMode = v; prefs.edit().putString("themeMode", v).apply() }
    fun getBool(k: String) = prefs.getBoolean(k, false)
    fun putBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
}
