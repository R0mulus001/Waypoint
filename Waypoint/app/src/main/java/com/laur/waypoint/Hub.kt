package com.laur.waypoint

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Every city guide on the phone (hand-made and built), plus each city's saved data and photos. */
class CityStores(private val ctx: Context, initial: List<City>) {
    val cities = mutableStateListOf<City>().apply { addAll(initial) }
    val builder = GuideBuilder(ctx)
    private val states = HashMap<String, TripState>()
    private val photos = HashMap<String, Photos>()
    private val downloads = HashMap<String, Downloader>()
    /** Lives as long as the app, so downloads keep going when you leave a city. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    fun downloader(c: City) = downloads.getOrPut(c.key) { Downloader(photos(c), c, scope) }
    fun state(c: City) = states.getOrPut(c.key) { TripState(TripDb(ctx, c.key)) }
    fun photos(c: City) = photos.getOrPut(c.key) { Photos(ctx, state(c).db, c) }
    fun find(key: String) = cities.firstOrNull { it.key == key }
    /** A guide that finished building after its screen was left is on disk but not in the list yet. */
    fun findOrLoad(key: String): City? = find(key) ?: runCatching {
        City(JSONObject(java.io.File(builder.dir, "$key.json").readText()))
    }.getOrNull()?.also { add(it) }
    fun add(c: City) { cities.removeAll { it.key == c.key }; cities.add(c); photos.remove(c.key); downloads.remove(c.key)?.stop() }
}

/** What the start screens are showing (not saved; the open city is). */
sealed class Screen {
    data object Home : Screen()
    data object Countries : Screen()
    data class Cities(val code: String) : Screen()
    data class Build(val code: String, val name: String, val wiki: String?, val at: Pair<Double, Double>?) : Screen()
}

private const val HUB_BAR = 0xFF1A4FA0

@Composable
fun WaypointApp(
    stores: CityStores, gps: LatLng?, locationOn: Boolean,
    requestLocation: () -> Unit, openUrl: (String) -> Unit, setBarColor: (Long) -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    val dark = when (Hub.themeMode) { "light" -> false; "dark" -> true; else -> sysDark }
    val city = stores.find(Hub.city)?.takeIf { Hub.onboarded }
    if (city != null) Trip.city = city
    val pal = palFor(city, dark)
    SideEffect { setBarColor(city?.accent?.bar ?: HUB_BAR) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalPal provides pal) {
            when {
                !Hub.onboarded -> HubWelcome(locationOn, requestLocation)
                city != null -> TripApp(stores.state(city), stores.photos(city), stores.downloader(city), gps, locationOn, requestLocation, openUrl,
                    reloadFood = { stores.add(City(JSONObject(stores.builder.refreshFood(city).readText()))) }) { screen = Screen.Home; Hub.open("") }
                else -> when (val s = screen) {
                    Screen.Home -> HomeScreen(stores) { screen = Screen.Countries }
                    Screen.Countries -> CountriesScreen(onBack = { screen = Screen.Home }) { screen = Screen.Cities(it.code) }
                    is Screen.Cities -> CitiesScreen(stores, Europe.byCode(s.code)!!, onBack = { screen = Screen.Countries },
                        onPick = { ref -> openOrBuild(stores, s.code, ref.name, ref.wiki, ref.guide, null) { screen = it } },
                        onPickHit = { hit -> openOrBuild(stores, s.code, hit.name, null, null, hit.lat to hit.lng) { screen = it } })
                    is Screen.Build -> BuildScreen(stores, s, onBack = { screen = Screen.Cities(s.code) })
                }
            }
        }
    }
}

/** Opens a city that already has a guide (adding it to My trips), or starts building one. */
private fun openOrBuild(stores: CityStores, code: String, name: String, wiki: String?, guide: String?, at: Pair<Double, Double>?, go: (Screen) -> Unit) {
    val key = guide ?: (code.lowercase() + "-" + slug(name))
    if (stores.findOrLoad(key) != null) { Hub.addTrip(key); Hub.open(key) }
    else go(Screen.Build(code, name, wiki, at))
}

@Composable
fun BrandMark(size: Int = 34) {
    val c = LocalPal.current
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        Icon(PinIcon, null, tint = c.accentFg, modifier = Modifier.size((size * 0.62f).dp))
    }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, lead: @Composable () -> Unit = { BrandMark() }) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onBack != null) BackButton(onBack)
        lead()
        T(title, 25.sp, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.weight(1f), maxLines = 1)
        ThemePill()
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    val c = LocalPal.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape).clickable { onBack() },
        contentAlignment = Alignment.Center) {
        T("‹", 24.sp, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
    }
}

// ---------- My trips ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(stores: CityStores, onAdd: () -> Unit) {
    val c = LocalPal.current
    val trips = Hub.trips.mapNotNull { stores.find(it) }
    var removing by remember { mutableStateOf<City?>(null) }
    var arranging by remember { mutableStateOf(false) }
    var showOlder by remember { mutableStateOf(false) }
    val diary = trips.flatMap { city ->
        stores.state(city).visited.entries.mapNotNull { (id, at) -> city.byId[id]?.let { Triple(city, it, at) } }
    }.sortedByDescending { it.third }
    // Each section keeps your own order (the order of Hub.trips).
    val byWhen = trips.groupBy { whenOf(stores, it) }
    val now = byWhen[TripWhen.Now].orEmpty()
    val ahead = byWhen[TripWhen.Ahead].orEmpty()
    val recent = byWhen[TripWhen.Recent].orEmpty()
    val older = byWhen[TripWhen.Older].orEmpty()

    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TopBar("Waypoint", null) }
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Your trips", 22.sp, FontWeight.SemiBold, family = Fonts.display)
                    T(when {
                        trips.isEmpty() -> "Add a trip to get started."
                        arranging -> "Use the arrows to move trips. Your order is saved."
                        else -> "Tap a city to open it. Long-press to remove it."
                    }, 14.sp, color = c.muted)
                }
                if (trips.size > 1) T(if (arranging) "Done" else "Arrange", 15.sp, FontWeight.SemiBold, c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { arranging = !arranging }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        @Composable
        fun row(city: City, section: List<City>, isNow: Boolean = false) {
            val i = section.indexOf(city)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).combinedClickable(onClick = { Hub.open(city.key) }, onLongClick = { removing = city })) { TripCard(stores, city, isNow) }
                if (arranging) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MoveBtn("▲", i > 0) { Hub.swapTrips(city.key, section[i - 1].key) }
                    MoveBtn("▼", i < section.size - 1) { Hub.swapTrips(city.key, section[i + 1].key) }
                }
            }
        }
        // Recent trips always come first, right under "Your trips".
        if (recent.isNotEmpty()) {
            item(key = "h-recent") { Label("Recent trips", modifier = Modifier.padding(top = 4.dp)) }
            items(recent, key = { it.key }) { row(it, recent) }
        }
        if (now.isNotEmpty()) {
            item(key = "h-now") { Label("Happening now", c.accent, Modifier.padding(top = 4.dp)) }
            items(now, key = { it.key }) { row(it, now, isNow = true) }
        }
        if (ahead.isNotEmpty()) {
            if (now.isNotEmpty() || recent.isNotEmpty()) item(key = "h-ahead") { Label("Coming up", modifier = Modifier.padding(top = 4.dp)) }
            items(ahead, key = { it.key }) { row(it, ahead) }
        }
        if (older.isNotEmpty()) {
            item(key = "h-older") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showOlder = !showOlder }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Label("Older than 10 months (${older.size})", modifier = Modifier.weight(1f))
                    T(if (showOlder) "Hide" else "Show ›", 14.sp, FontWeight.SemiBold, c.accent)
                }
            }
            if (showOlder) items(older, key = { it.key }) { row(it, older) }
        }
        item { Btn("+  Plan a new trip", Modifier.fillMaxWidth()) { onAdd() } }
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Your travel diary", 20.sp, FontWeight.SemiBold, family = Fonts.display)
                val n = diary.map { it.first.key }.toSet().size
                T(if (diary.isEmpty()) "Places you visit in any city show up here." else "${diary.size} place" + (if (diary.size == 1) "" else "s") +
                    " visited in $n cit" + (if (n == 1) "y" else "ies"), 14.sp, color = c.muted)
            }
        }
        items(diary.take(12), key = { "d-" + it.first.key + "|" + it.second.id }) { (city, p, at) ->
            Card(Modifier.clickable { Hub.open(city.key); stores.state(city).setTab("visited") }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Flag(27, 18, city.flag)
                    Column(Modifier.weight(1f)) {
                        T(p.name, 15.5.sp, FontWeight.SemiBold, maxLines = 1)
                        T(city.name + " · " + visitedWhen(at, city), 13.sp, color = c.muted)
                    }
                    Stars(stores.state(city).note(p.id).stars)
                }
            }
        }
    }
    removing?.let { city ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove ${city.name}?") },
            text = { Text("It leaves Your trips. Your visits, notes and photos stay saved, so adding it again brings them back.") },
            confirmButton = { TextButton(onClick = { Hub.removeTrip(city.key); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

/** Where a trip sits on My trips: on now, coming up (or no dates yet), ended in the last 10 months, or older. */
enum class TripWhen { Now, Ahead, Recent, Older }

fun whenOf(stores: CityStores, city: City): TripWhen {
    val st = stores.state(city)
    if (st.start.isBlank()) return TripWhen.Ahead
    val days = tripDays(st.start, st.len)
    val today = java.time.LocalDate.now(java.time.ZoneId.of(city.tz))
    val first = java.time.LocalDate.parse(days.first().key)
    val last = java.time.LocalDate.parse(days.last().key)
    return when {
        today < first -> TripWhen.Ahead
        today <= last -> TripWhen.Now
        !last.isBefore(today.minusMonths(10)) -> TripWhen.Recent
        else -> TripWhen.Older
    }
}

@Composable
private fun MoveBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalPal.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape)
        .then(if (enabled) Modifier.clickable { onClick() } else Modifier), contentAlignment = Alignment.Center) {
        T(label, 15.sp, FontWeight.SemiBold, if (enabled) c.fg else c.line)
    }
}

@Composable
private fun TripCard(stores: CityStores, city: City, now: Boolean = false) {
    val c = LocalPal.current
    val st = stores.state(city)
    val accent = Color(if (c.dark) city.accent.dark else city.accent.light)
    val dates = if (st.start.isBlank()) "Trip dates not set" else tripLabel(tripDays(st.start, st.len))
    val visited = st.visited.size
    // The trip you're on now gets an outline in its own colour.
    Card(if (now) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Flag(48, 32, city.flag)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(city.name, 21.sp, FontWeight.SemiBold, family = Fonts.display, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (!city.generated) GuideBadge()
                }
                T(city.country + " · " + city.tagline, 13.5.sp, color = c.muted, maxLines = 1)
                T(dates + (if (visited > 0) " · $visited visited" else ""), 13.5.sp, FontWeight.SemiBold, accent)
            }
            T("›", 26.sp, color = c.muted)
        }
    }
}

@Composable
private fun GuideBadge() {
    val c = LocalPal.current
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(c.foodSoft).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T("FULL GUIDE", 10.5.sp, FontWeight.SemiBold, c.food, letterSpacing = 0.6.sp)
    }
}

// ---------- country and city pickers ----------

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, onSearch: (() -> Unit)? = null) {
    val c = LocalPal.current
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) T(hint, 15.sp, color = c.muted)
        BasicTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 15.sp, color = c.fg, fontFamily = Fonts.body), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }))
    }
}

@Composable
fun CountriesScreen(onBack: () -> Unit, onPick: (Country) -> Unit) {
    val c = LocalPal.current
    var q by remember { mutableStateOf("") }
    BackHandler { onBack() }
    val list = Europe.countries.filter { q.isBlank() || plainText(it.name).contains(plainText(q)) || it.cities.any { ct -> plainText(ct.name).contains(plainText(q)) } }
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TopBar("Plan a trip", onBack) }
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Which country?", 22.sp, FontWeight.SemiBold, family = Fonts.display)
                SearchField(q, "Search countries or cities", { q = it })
            }
        }
        items(list, key = { it.code }) { co ->
            Card(Modifier.clickable { onPick(co) }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    T(co.emoji, 26.sp)
                    Column(Modifier.weight(1f)) {
                        T(co.name, 16.5.sp, FontWeight.SemiBold)
                        T(co.cities.take(3).joinToString(", ") { it.name } + (if (co.cities.size > 3) "…" else ""), 13.sp, color = c.muted, maxLines = 1)
                    }
                    T("›", 24.sp, color = c.muted)
                }
            }
        }
        if (list.isEmpty()) item { T("No country matches \"$q\".", 15.sp, color = c.muted, modifier = Modifier.padding(16.dp)) }
    }
}

fun plainText(s: String) = slug(s).replace("-", " ")

@Composable
fun CitiesScreen(stores: CityStores, co: Country, onBack: () -> Unit, onPick: (CityRef) -> Unit, onPickHit: (SearchHit) -> Unit) {
    val c = LocalPal.current
    val scope = rememberCoroutineScope()
    var q by remember(co.code) { mutableStateOf("") }
    var hits by remember(co.code) { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember(co.code) { mutableStateOf(false) }
    var error by remember(co.code) { mutableStateOf<String?>(null) }
    BackHandler { onBack() }
    val search = {
        if (q.isNotBlank() && !searching) {
            searching = true; error = null
            scope.launch {
                runCatching { searchCities(co, q.trim()) }
                    .onSuccess { hits = it; if (it.isEmpty()) error = "No towns called \"$q\" found in ${co.name}." }
                    .onFailure { error = "Couldn't search right now. Check your internet connection." }
                searching = false
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TopBar(co.name, onBack) { T(co.emoji, 26.sp) } }
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Which city?", 22.sp, FontWeight.SemiBold, family = Fonts.display)
                SearchField(q, "Search any city or town in ${co.name}", { q = it; if (it.isBlank()) { hits = null; error = null } }, search)
                if (q.isNotBlank()) Btn(if (searching) "Searching…" else "Search online for \"${q.trim()}\"", Modifier.fillMaxWidth(), BtnKind.Ghost, small = true) { search() }
                error?.let { T(it, 14.sp, color = c.warn) }
            }
        }
        hits?.let { hs ->
            if (hs.isNotEmpty()) item { Label("Search results") }
            items(hs, key = { "h-" + it.name + "|" + it.lat + "|" + it.lng }) { h ->
                CityRow(h.name, h.detail, stores.find(co.code.lowercase() + "-" + slug(h.name)) != null, false) { onPickHit(h) }
            }
        }
        item { Label("Big cities", modifier = Modifier.padding(top = 8.dp)) }
        items(co.cities, key = { "c-" + it.name }) { ref ->
            val key = ref.guide ?: (co.code.lowercase() + "-" + slug(ref.name))
            CityRow(ref.name, null, stores.find(key) != null, ref.guide != null) { onPick(ref) }
        }
        item {
            T("Picking a city builds its guide from Wikipedia and OpenStreetMap the first time. It takes under a minute and needs internet.",
                12.5.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CityRow(name: String, detail: String?, ready: Boolean, full: Boolean, onClick: () -> Unit) {
    val c = LocalPal.current
    Card(Modifier.clickable { onClick() }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(name, 16.5.sp, FontWeight.SemiBold)
                    if (full) GuideBadge()
                }
                if (detail != null) T(detail, 13.sp, color = c.muted, maxLines = 1)
                else if (ready) T("Guide saved on your phone", 13.sp, color = c.food)
            }
            T("›", 24.sp, color = c.muted)
        }
    }
}

// ---------- building a guide ----------

@Composable
fun BuildScreen(stores: CityStores, s: Screen.Build, onBack: () -> Unit) {
    val c = LocalPal.current
    val co = Europe.byCode(s.code)!!
    var step by remember(s) { mutableStateOf("Starting") }
    val done = remember(s) { mutableStateListOf<String>() }
    var error by remember(s) { mutableStateOf<String?>(null) }
    var attempt by remember(s) { mutableStateOf(0) }
    BackHandler { onBack() }
    LaunchedEffect(s, attempt) {
        error = null; done.clear()
        runCatching {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            stores.builder.build(co, s.name, s.wiki, s.at) { msg -> main.post { if (step != "Starting") done.add(step); step = msg } }
        }.onSuccess { f ->
            val city = City(JSONObject(f.readText()))
            stores.add(city); Hub.addTrip(city.key); Hub.open(city.key)
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e // left the screen: not an error to show
            error = when {
                e is BusyException -> "Wikipedia or OpenStreetMap is busy right now. Wait a minute and tap Try again."
                e is java.net.UnknownHostException || e is java.net.ConnectException -> "Couldn't reach the internet. Check your connection and try again."
                e is java.net.SocketTimeoutException -> "The connection is too slow right now. Try again, ideally on Wi-Fi."
                e.message?.startsWith("Couldn't") == true -> e.message
                else -> "Something went wrong while building the guide (${e.message}). Try again."
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onBack)
            T(co.emoji, 30.sp)
        }
        T("Building your ${s.name} guide", 28.sp, FontWeight.SemiBold, family = Fonts.display)
        T("Collecting sights, places to eat and drink, and day plans. This takes under a minute.", 15.sp, color = c.muted)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            done.forEach { T("✓  $it", 15.5.sp, FontWeight.Medium, c.food) }
            if (error == null) T("…  $step", 15.5.sp, FontWeight.SemiBold, c.accent)
        }
        error?.let {
            T(it, 15.sp, color = c.warn)
            Btn("Try again", Modifier.fillMaxWidth()) { attempt++ }
            Btn("Pick another city", Modifier.fillMaxWidth(), BtnKind.Ghost) { onBack() }
        }
    }
}

@Composable
fun ThemePill() {
    val c = LocalPal.current
    val label = when (Hub.themeMode) { "light" -> "Light"; "dark" -> "Dark"; else -> "Auto" }
    Box(Modifier.clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape)
        .clickable { Hub.setThemeMode(when (Hub.themeMode) { "auto" -> "light"; "light" -> "dark"; else -> "auto" }) }
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        T(label, 13.sp, FontWeight.SemiBold)
    }
}
