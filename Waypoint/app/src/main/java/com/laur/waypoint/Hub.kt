package com.laur.waypoint

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Every city guide on the phone (hand-made and built), plus each city's saved data and photos. */
class CityStores(private val ctx: Context, initial: List<City>) {
    val cities = mutableStateListOf<City>().apply { addAll(initial) }
    /** Guides whose places to eat are still loading in the background. */
    val foodLoading = mutableStateListOf<String>()
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

@Composable
fun WaypointApp(
    stores: CityStores, gps: LatLng?, locationOn: Boolean,
    requestLocation: () -> Unit, openUrl: (String) -> Unit, setDarkBars: (Boolean) -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    val dark = when (Hub.themeMode) { "light" -> false; "dark" -> true; else -> sysDark }
    val city = stores.find(Hub.city)?.takeIf { Hub.onboarded }
    if (city != null) Trip.city = city
    val pal = palFor(city, dark)
    SideEffect { setDarkBars(dark) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    MaterialTheme(colorScheme = schemeFor(pal)) {
        CompositionLocalProvider(LocalPal provides pal) {
            when {
                !Hub.onboarded -> HubWelcome(locationOn, requestLocation)
                city != null -> TripApp(stores.state(city), stores.photos(city), stores.downloader(city), gps, locationOn, requestLocation, openUrl,
                    foodLoading = city.key in stores.foodLoading,
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

/** Bottom inset of the navigation bar, so lists scroll under it (edge to edge) but end above it. */
@Composable
fun navBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Waypoint's own mark: the pin on Waypoint blue. Only on Waypoint's own screens. */
@Composable
fun BrandMark(size: Int = 32) {
    val c = LocalPal.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(c.accent), contentAlignment = Alignment.Center) {
        Icon(PinIcon, null, tint = c.accentFg, modifier = Modifier.size((size * 0.6f).dp))
    }
}

/** A Waypoint screen: top app bar, then a scrolling list that runs under the navigation bar. */
@Composable
private fun HubPage(title: @Composable () -> Unit, onBack: (() -> Unit)?, gap: Dp = 8.dp, content: LazyListScope.() -> Unit) {
    val c = LocalPal.current
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        AppBar(onBack = onBack, title = title)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp + navBottom()),
            verticalArrangement = Arrangement.spacedBy(gap), content = content)
    }
}

@Composable
private fun BigTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(text, Ty.display, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })
        if (!sub.isNullOrBlank()) T(sub, Ty.bodyS, color = LocalPal.current.muted)
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) =
    Label(text, color, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))

private val UpChevron = icon("M6 15l6-6 6 6")

// ---------- My trips ----------

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

    HubPage(title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark(); T("Waypoint", Ty.headline, FontWeight.SemiBold, family = Fonts.display)
        }
    }, onBack = null, gap = 0.dp) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    BigTitle("Your trips", when {
                        trips.isEmpty() -> "Add a trip to get started."
                        arranging -> "Use the arrows to move trips. Your order is saved."
                        else -> "Tap a city to open it. Long-press to remove it."
                    })
                }
                if (trips.size > 1) TextButton(onClick = { arranging = !arranging }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(if (arranging) "Done" else "Arrange", fontWeight = FontWeight.SemiBold, color = c.accent)
                }
            }
        }
        fun section(key: String, title: String?, list: List<City>, isNow: Boolean = false) {
            if (list.isEmpty()) return
            if (title != null) item(key = "h-$key") { SectionLabel(title, if (isNow) c.accent else c.muted) }
            itemsIndexed(list, key = { _, it -> it.key }) { i, city ->
                TripRow(stores, city, i, list.size, isNow, arranging,
                    onOpen = { Hub.open(city.key) }, onRemove = { removing = city },
                    onUp = if (i > 0) ({ Hub.swapTrips(city.key, list[i - 1].key) }) else null,
                    onDown = if (i < list.size - 1) ({ Hub.swapTrips(city.key, list[i + 1].key) }) else null)
            }
        }
        // Recent trips always come first, right under "Your trips".
        section("recent", "Recent trips", recent)
        section("now", "Happening now", now, isNow = true)
        section("ahead", if (now.isNotEmpty() || recent.isNotEmpty()) "Coming up" else null, ahead)
        if (older.isNotEmpty()) {
            item(key = "h-older") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(Rad.s)).clickable { showOlder = !showOlder }
                    .heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Older than 10 months (${older.size})", Ty.bodyS, FontWeight.SemiBold, c.muted, modifier = Modifier.weight(1f).semantics { heading() })
                    T(if (showOlder) "Hide" else "Show", Ty.bodyS, FontWeight.SemiBold, c.accent)
                    Icon(if (showOlder) UpChevron else DownIcon, null, tint = c.accent, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                }
            }
            if (showOlder) section("older", null, older)
        }
        item { Btn("Plan a new trip", Modifier.fillMaxWidth().padding(top = 20.dp), icon = PlusIcon) { onAdd() } }
        item {
            val n = diary.map { it.first.key }.toSet().size
            SectionHead("Your travel diary",
                if (diary.isEmpty()) "Places you visit in any city show up here." else "${diary.size} place" + (if (diary.size == 1) "" else "s") +
                    " visited in $n cit" + (if (n == 1) "y" else "ies"),
                Modifier.padding(start = 4.dp, top = 32.dp, bottom = 12.dp))
        }
        val shown = diary.take(12)
        itemsIndexed(shown, key = { _, it -> "d-" + it.first.key + "|" + it.second.id }) { i, (city, p, at) ->
            GroupRow(i, shown.size, onClick = { Hub.open(city.key); stores.state(city).setTab("visited") }, dividerInset = 64.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlagTile(codeOf(city), 36.dp, label = city.country)
                    Column(Modifier.weight(1f)) {
                        T(p.name, Ty.body, FontWeight.Medium, maxLines = 1)
                        T(city.name + " · " + visitedWhen(at, city), Ty.label, color = c.muted)
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

/** One trip: the city tile (flag + pin), name, country and dates in the city's own colour. */
@Composable
private fun TripRow(stores: CityStores, city: City, i: Int, n: Int, now: Boolean, arranging: Boolean,
                    onOpen: () -> Unit, onRemove: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    val c = LocalPal.current
    val st = stores.state(city)
    val accent = cityAccent(city, c.dark)
    val dates = if (st.start.isBlank()) "Trip dates not set" else tripLabel(tripDays(st.start, st.len))
    val visited = st.visited.size
    GroupRow(i, n, onClick = if (arranging) null else onOpen, onLongClick = if (arranging) null else onRemove, dividerInset = 84.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FlagTile(codeOf(city), 52.dp, label = city.country)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(city.name, 22.sp, FontWeight.SemiBold, family = Fonts.display, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    if (now) NowDot(accent)
                    if (!city.generated) Tag("Full guide")
                }
                T(city.country + " · " + city.tagline, Ty.label, color = c.muted, maxLines = 1)
                T(dates + (if (visited > 0) " · $visited visited" else ""), Ty.label, FontWeight.SemiBold, accent)
            }
            if (arranging) Column {
                IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
                    Icon(UpIcon, "Move ${city.name} up", tint = if (onUp != null) c.fg else c.line)
                }
                IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) {
                    Icon(DownArrowIcon, "Move ${city.name} down", tint = if (onDown != null) c.fg else c.line)
                }
            } else Icon(ChevronIcon, null, tint = c.muted, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        }
    }
}

/** "You're on this trip now": a dot in the city's colour, with the word too (not colour alone). */
@Composable
private fun NowDot(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        T("Now", Ty.caption, FontWeight.SemiBold, color)
    }
}

// ---------- country and city pickers ----------

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, onSearch: (() -> Unit)? = null) {
    val c = LocalPal.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(Rad.m)).background(c.surface).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(SearchIcon, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty()) T(hint, Ty.body, color = c.muted, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                textStyle = TextStyle(fontSize = Ty.body, color = c.fg, fontFamily = Fonts.body), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }))
        }
        if (value.isNotEmpty()) IconBtn(CloseIcon, "Clear search", c.muted) { onChange("") }
    }
}

@Composable
fun CountriesScreen(onBack: () -> Unit, onPick: (Country) -> Unit) {
    val c = LocalPal.current
    var q by remember { mutableStateOf("") }
    BackHandler { onBack() }
    val list = Europe.countries.filter { q.isBlank() || plainText(it.name).contains(plainText(q)) || it.cities.any { ct -> plainText(ct.name).contains(plainText(q)) } }
    HubPage(title = { T("Plan a trip", Ty.headline, FontWeight.SemiBold, family = Fonts.display) }, onBack = onBack, gap = 0.dp) {
        item { BigTitle("Which country?") }
        item { Box(Modifier.padding(bottom = 16.dp)) { SearchField(q, "Search countries or cities", { q = it }) } }
        itemsIndexed(list, key = { _, it -> it.code }) { i, co ->
            GroupRow(i, list.size, onClick = { onPick(co) }, dividerInset = 68.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlagRect(co.code, 36.dp)
                    Column(Modifier.weight(1f)) {
                        T(co.name, Ty.bodyL, FontWeight.Medium)
                        T(co.cities.take(3).joinToString(", ") { it.name } + (if (co.cities.size > 3) "…" else ""), Ty.label, color = c.muted, maxLines = 1)
                    }
                    Icon(ChevronIcon, null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (list.isEmpty()) item { T("No country matches \"$q\".", Ty.body, color = c.muted, modifier = Modifier.padding(16.dp)) }
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
    HubPage(title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlagRect(co.code, 30.dp); T(co.name, Ty.headline, FontWeight.SemiBold, family = Fonts.display, maxLines = 1)
        }
    }, onBack = onBack, gap = 0.dp) {
        item { BigTitle("Which city?") }
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(q, "Search any city or town in ${co.name}", { q = it; if (it.isBlank()) { hits = null; error = null } }, search)
                if (q.isNotBlank()) Btn(if (searching) "Searching…" else "Search online for \"${q.trim()}\"", Modifier.fillMaxWidth(), BtnKind.Tonal, icon = SearchIcon) { search() }
                error?.let { T(it, Ty.bodyS, color = c.caution) }
            }
        }
        hits?.let { hs ->
            if (hs.isNotEmpty()) item { SectionLabel("Search results", c.muted) }
            itemsIndexed(hs, key = { _, it -> "h-" + it.name + "|" + it.lat + "|" + it.lng }) { i, h ->
                CityRow(i, hs.size, h.name, h.detail, stores.find(co.code.lowercase() + "-" + slug(h.name)) != null, false) { onPickHit(h) }
            }
        }
        item { SectionLabel("Big cities", c.muted) }
        itemsIndexed(co.cities, key = { _, it -> "c-" + it.name }) { i, ref ->
            val key = ref.guide ?: (co.code.lowercase() + "-" + slug(ref.name))
            CityRow(i, co.cities.size, ref.name, null, stores.find(key) != null, ref.guide != null) { onPick(ref) }
        }
        item {
            T("Picking a city builds its guide from Wikipedia and OpenStreetMap the first time. It takes under a minute and needs internet.",
                Ty.caption, color = c.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp))
        }
    }
}

@Composable
private fun CityRow(i: Int, n: Int, name: String, detail: String?, ready: Boolean, full: Boolean, onClick: () -> Unit) {
    val c = LocalPal.current
    GroupRow(i, n, onClick = onClick) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(name, Ty.bodyL, FontWeight.Medium)
                    if (full) Tag("Full guide")
                }
                if (detail != null) T(detail, Ty.label, color = c.muted, maxLines = 1)
                else if (ready) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(CheckIcon, null, tint = c.muted, modifier = Modifier.size(14.dp))
                    T("Guide saved on your phone", Ty.label, color = c.muted)
                }
            }
            Icon(ChevronIcon, null, tint = c.muted, modifier = Modifier.size(20.dp))
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
        }.onSuccess { b ->
            val city = City(JSONObject(b.file.readText()))
            stores.add(city); Hub.addTrip(city.key); Hub.open(city.key)
            // Opened without places to eat (the map service was slow): they keep loading and join the guide when ready.
            if (b.foodLater) {
                stores.foodLoading.add(city.key)
                stores.scope.launch {
                    runCatching { stores.add(City(JSONObject(stores.builder.refreshFood(city).readText()))) }
                    stores.foodLoading.remove(city.key)
                }
            }
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(onBack = onBack) { FlagRect(co.code, 30.dp) }
        if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
        Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Building your ${s.name} guide", 30.sp, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })
            T("Collecting sights, places to eat and drink, and day plans. This takes under a minute.", Ty.body, color = c.muted)
            Spacer(Modifier.size(4.dp))
            done.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(CheckIcon, null, tint = c.accent, modifier = Modifier.size(14.dp))
                    }
                    T(d, Ty.body, color = c.muted)
                }
            }
            if (error == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp) }
                T(step, Ty.body, FontWeight.SemiBold)
            }
            error?.let {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.cautionSoft).padding(16.dp)) { T(it, Ty.body, color = c.fg) }
                Btn("Try again", Modifier.fillMaxWidth()) { attempt++ }
                Btn("Pick another city", Modifier.fillMaxWidth(), BtnKind.Ghost) { onBack() }
            }
        }
    }
}
