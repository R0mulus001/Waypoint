package com.laur.waypoint

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.LocalDate

// ---------- app shell ----------

class Ui(val st: TripState, val photos: Photos, val dl: Downloader, val openUrl: (String) -> Unit, val requestLocation: () -> Unit, val scope: CoroutineScope, val onBack: () -> Unit) {
    /** Set by the app: fetches food again for a built guide and swaps the city in. */
    var reloadFood: suspend () -> Unit = {}
    var open by mutableStateOf<String?>(null)
    val skipped = mutableStateOf(setOf<String>())
    var toast by mutableStateOf<Toast?>(null)

    /** Download all photos for this city (see [Downloader]). */
    fun downloadAll() = dl.start()

    /** Ticks or unticks a place and offers Undo, so a mis-tap never loses the saved visit time. */
    fun toggleSeen(id: String) {
        val prev = st.visited[id]
        st.toggleSeen(id)
        val name = Trip.BY_ID[id]?.name ?: "Place"
        // Undo does the exact opposite, so a second tap in between can't make Undo flip the wrong way.
        toast = if (prev != null) Toast("Removed $name from visited", { st.restoreSeen(id, prev) })
        else Toast("$name marked as visited", { st.unsee(id) })
    }
}

@Composable
fun TripApp(st: TripState, photos: Photos, dl: Downloader, gps: LatLng?, locationOn: Boolean, requestLocation: () -> Unit, openUrl: (String) -> Unit,
            reloadFood: suspend () -> Unit = {}, onBack: () -> Unit) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    // Keyed on photos and dl too: "Load places to eat" swaps in a new city, and its new Photos/Downloader must reach the screens.
    val ui = remember(st, photos, dl) { Ui(st, photos, dl, openUrl, requestLocation, scope, onBack) }
    ui.reloadFood = reloadFood
    var openSaved by rememberSaveable(Trip.city.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(ui) { if (openSaved != null) ui.open = openSaved }
    LaunchedEffect(ui.open) { openSaved = ui.open }

    var now by remember(Trip.city.key) { mutableStateOf(nowInTrip()) }
    LaunchedEffect(Trip.city.key) { while (true) { delay(30_000); now = nowInTrip() } }

    if (!st.onboarded) { CityWelcome(ui, now); return }

    // GPS only counts when you're actually in this city (within ~40 km). Away from it there are no distances:
    // places are sorted by importance, and the real location takes over by itself once you arrive.
    val away = gps != null && km(gps.lat, gps.lng, Trip.city.centerLat, Trip.city.centerLng) > 40
    val gpsHere = gps.takeIf { !away }
    val here = if (away) null else gpsHere ?: Trip.BY_ID[st.manual]?.let { LatLng(it.lat, it.lng) }
    BackHandler(ui.open == null) { onBack() }
    BackHandler(ui.open != null) { ui.open = null }

    val sub = if (st.tab == "visited") st.visited.keys.count { Trip.BY_ID.containsKey(it) }.let { n ->
        if (n == 0) "Nothing visited yet" else "$n place" + (if (n == 1) "" else "s") + " visited"
    } else tripLabel(tripDays(st.start.ifBlank { now.key }, st.len))

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize()) {
            CityBar(ui, sub)
            Box(Modifier.weight(1f)) {
                Crossfade(st.tab, animationSpec = tween(150), label = "tab") { tab ->
                    when (tab) {
                        "plans" -> PlansTab(ui, now, here, gpsHere != null)
                        "visited" -> VisitedTab(ui)
                        else -> NearbyTab(ui, now, here, gpsHere != null, locationOn && !away, away)
                    }
                }
                Snack(ui.toast, { ui.toast = null }, Modifier.align(Alignment.BottomCenter))
            }
            NavBar(CITY_TABS, st.tab.takeIf { t -> CITY_TABS.any { it.key == t } } ?: "near") { st.setTab(it) }
        }
        ui.open?.let { id -> Trip.BY_ID[id]?.let { PlaceSheet(ui, it, here) } }
    }
}

private val CITY_TABS = listOf(NavTab("near", "Nearby", PinIcon), NavTab("plans", "Day plans", ListIcon), NavTab("visited", "Visited", VisitedIcon))

/** The city's top bar: back to My trips, the city tile (flag + pin), name and trip line, overflow menu. */
@Composable
fun CityBar(ui: Ui, sub: String) {
    val c = LocalPal.current
    val city = Trip.city
    AppBar(onBack = ui.onBack, actions = { MoreMenu(listOf("Download photos for offline" to { ui.downloadAll() })) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlagTile(codeOf(city), 36.dp, label = city.country)
            Column {
                T(city.name, 22.sp, FontWeight.SemiBold, family = Fonts.display, maxLines = 1, modifier = Modifier.semantics { heading() })
                if (sub.isNotBlank()) T(sub, Ty.label, color = c.muted, maxLines = 1)
            }
        }
    }
}

private val listPad = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)

// ---------- place card ----------

@Composable
fun PlaceCard(ui: Ui, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val st = ui.st
    val cat = Trip.catOf(p)
    val food = Trip.isFoodCat(cat)
    val seen = st.isSeen(p.id)
    val note = st.note(p.id)
    Card(Modifier.alpha(if (seen) .55f else 1f).clickable { ui.open = p.id }) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(Trip.label(cat), food)
                    if (Trip.priceText(p).isNotEmpty()) T(Trip.priceText(p), 13.5.sp, FontWeight.Medium, c.muted)
                }
                T(p.name, 17.sp, FontWeight.SemiBold)
                if (Trip.isFree(p)) FreeBadge()
                distInfo(here, p)?.let { T(it.text, 14.sp, FontWeight.SemiBold, if (food) c.food else c.accent) }
                T(p.desc, 14.5.sp, color = c.muted)
                if (!note.isEmpty) {
                    Stars(note.stars)
                    if (note.text.isNotBlank()) T("“" + note.text.trim() + "”", 13.sp, color = c.fg, maxLines = 2, italic = true)
                }
            }
            Column(Modifier.width(86.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Go", Modifier.fillMaxWidth(), small = true) { ui.openUrl(mapsUrl(here, p)) }
                val sb = RoundedCornerShape(9.dp)
                Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(sb).border(1.dp, if (seen) c.food else c.line, sb)
                    .clickable { ui.toggleSeen(p.id) }.padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    T(if (seen) "Visited ✓" else "Visited", 12.5.sp, color = if (seen) c.food else c.muted)
                }
            }
        }
    }
}

// ---------- Nearby tab ----------

@Composable
fun NearbyTab(ui: Ui, now: Now, here: LatLng?, hasGps: Boolean, locationOn: Boolean, away: Boolean = false) {
    val c = LocalPal.current
    val st = ui.st
    val filter = st.filter.takeIf { f -> Trip.FILTERS.any { it.first == f } } ?: Trip.FILTERS.first().first
    val days = tripDays(st.start.ifBlank { now.key }, st.len)
    var showN by remember { mutableIntStateOf(12) }
    val (mo, pick) = heroPick(st, now, here, ui.skipped.value)

    var list = Trip.PLACES.filter { p -> filter == "all" || (if (filter == "free") Trip.isFree(p) else Trip.catOf(p) == filter) }
    if (!st.showSeen) list = list.filter { !st.isSeen(it.id) }
    list = sortNear(list, here)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(4.dp)); LocRow(ui, hasGps, locationOn, away) }
        if (pick != null) item { Hero(ui, mo, pick, here) }
        item {
            Column(Modifier.padding(top = 6.dp)) {
                T(if (away) "Top places" else "Around you", 20.sp, FontWeight.SemiBold, family = Fonts.display)
                T(if (away) "Most important first. Distances appear when you're in ${Trip.city.name}." else "Closest first. Tap Go for directions.", 14.sp, color = c.muted)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Trip.FILTERS.forEach { (v, l) ->
                    val on = filter == v
                    Box(Modifier.clip(CircleShape).background(if (on) c.fg else c.surface).border(1.dp, if (on) c.fg else c.line, CircleShape)
                        .clickable { st.setFilter(v); showN = 12 }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        T(l, 14.sp, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) c.bg else c.fg)
                    }
                }
            }
        }
        if (here == null && !away) item { WarnNote(listOf("" to "Turn on location, or pick where you are above, to sort places by distance.")) }
        if (list.isEmpty()) item {
            T("Nothing left in this category. Show visited places to see them again.", 15.sp, color = c.muted, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
        }
        items(list.take(showN), key = { it.id }) { PlaceCard(ui, it, here) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.size > showN) Btn("Show more", Modifier.weight(1f), BtnKind.Ghost, small = true) { showN += 12 }
                val n = st.visited.size
                if (n > 0) Btn(if (st.showSeen) "Hide visited" else "Show visited ($n)", Modifier.weight(1f), BtnKind.Ghost, small = true) { st.setShowSeen(!st.showSeen) }
            }
        }
        if (Trip.city.generated && Trip.FOOD.isEmpty()) item { FoodRetryRow(ui) }
        item { OfflineRow(ui) }
        item { T("Opening hours change, so check in Google Maps before you go.", 12.5.sp, color = c.muted) }
    }
}

@Composable
fun FoodRetryRow(ui: Ui) {
    val c = LocalPal.current
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Places to eat and drink")
            T(msg ?: "Cafés, restaurants and bars couldn't load when this guide was built. The map service may have been busy.", 14.5.sp, color = c.muted)
            Btn(if (busy) "Loading…" else "Load places to eat", Modifier.fillMaxWidth(), BtnKind.Ghost, small = true) {
                if (!busy) { busy = true; msg = null
                    ui.scope.launch {
                        runCatching { ui.reloadFood() }.onFailure { msg = "Still couldn't load them. Try again in a few minutes." }
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineRow(ui: Ui) {
    val c = LocalPal.current
    val dl = ui.dl
    LaunchedEffect(ui.open) { dl.refresh() }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Offline photos")
            when {
                dl.running -> {
                    T("Downloading… ${dl.done} of ${dl.total} places", 15.sp, FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                        Box(Modifier.fillMaxWidth(if (dl.total == 0) 0f else dl.done / dl.total.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.accent))
                    }
                    T("It keeps going if you leave this screen. Photos found so far: ${dl.withPhotos} places.", 13.sp, color = c.muted)
                }
                dl.failed == 0 -> {
                    T("All done. Photos and info are saved on your phone and work without internet.", 15.sp)
                    val none = dl.places - dl.withPhotos
                    if (none > 0) T("Photos for ${dl.withPhotos} places. The other $none have no photos online (mostly cafés and restaurants).", 13.sp, color = c.muted)
                }
                dl.finished == 0 && dl.withPhotos == 0 -> {
                    T("Download photos and info for all ${dl.places} places so the app works without internet (Wi-Fi is best).", 14.5.sp, color = c.muted)
                    Btn("Download all", Modifier.fillMaxWidth(), BtnKind.Ghost, small = true) { dl.start() }
                }
                else -> {
                    T("${dl.finished} of ${dl.places} places done, photos for ${dl.withPhotos}. ${dl.failed} still to go " +
                        "(or the server was busy).", 14.5.sp, color = c.muted)
                    Btn("Download the remaining ${dl.failed}", Modifier.fillMaxWidth(), BtnKind.Ghost, small = true) { dl.start() }
                }
            }
        }
    }
}

@Composable
fun LocRow(ui: Ui, hasGps: Boolean, locationOn: Boolean, away: Boolean = false) {
    val c = LocalPal.current
    Column {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val on = Color(0xFF2FA36B)
        Box(Modifier.size(if (hasGps) 17.dp else 9.dp).clip(CircleShape).background(if (hasGps) on.copy(alpha = .25f) else Color.Transparent), contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (hasGps) on else c.muted))
        }
        if (hasGps) { T("Using your location", 13.5.sp, color = c.muted); return@Row }
        if (away) { T("You're not in ${Trip.city.name} yet", 13.5.sp, color = c.muted); return@Row }
        if (locationOn) { T("Finding your location…", 13.5.sp, color = c.muted); return@Row }
        T("Location off. I'm near:", 13.5.sp, color = c.muted)
        var exp by remember { mutableStateOf(false) }
        Box {
            val shape = RoundedCornerShape(9.dp)
            Box(Modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable { exp = true }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                T((Trip.LANDMARKS.firstOrNull { it.first == ui.st.manual }?.second ?: "Choose a place") + "  ▾", 14.sp)
            }
            DropdownMenu(exp, { exp = false }) {
                Trip.LANDMARKS.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { ui.st.setManual(id); exp = false }) }
            }
        }
    }
    if (!hasGps && !locationOn && !away) T("Turn on location", 14.sp, FontWeight.SemiBold, c.accent,
        modifier = Modifier.padding(top = 8.dp).clickable { ui.requestLocation() })
    }
}

@Composable
fun Hero(ui: Ui, mo: Moment, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val food = Trip.isFoodCat(Trip.catOf(p))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.accent).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(mo.k + " · " + Trip.label(Trip.catOf(p)), c.accentFg.copy(alpha = .85f))
        T(p.name, 24.sp, FontWeight.SemiBold, c.accentFg, Fonts.display)
        T(distInfo(here, p)?.text ?: "Distance shows when you're nearby", 15.sp, FontWeight.SemiBold, c.accentFg)
        T(p.desc, 15.sp, color = c.accentFg.copy(alpha = .9f))
        T(if (food) "Prices & menu" else "See photos", 14.sp, FontWeight.SemiBold, c.accentFg, underline = true, modifier = Modifier.clickable { ui.open = p.id })
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Take me there", Modifier.weight(1f), BtnKind.OnAccent) { ui.openUrl(mapsUrl(here, p)) }
            Btn("Something else", Modifier.weight(1f), BtnKind.OutlineOnAccent) { ui.skipped.value = ui.skipped.value + p.id }
        }
    }
}

@Composable
fun WarnNote(parts: List<Pair<String, String>>) {
    val c = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.warn, shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(buildAnnotatedString {
            parts.forEach { (b, rest) ->
                if (b.isNotEmpty()) { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.warn)); append(b); pop() }
                append(rest)
            }
        }, style = TextStyle(fontSize = 13.5.sp, color = c.fg, fontFamily = Fonts.body, lineHeight = 19.sp))
    }
}

// ---------- Day plans tab ----------

@Composable
fun PlansTab(ui: Ui, now: Now, here: LatLng?, hasGps: Boolean) {
    val c = LocalPal.current
    val st = ui.st
    val ctx = LocalContext.current
    val days = tripDays(st.start.ifBlank { now.key }, st.len)
    val day = days.firstOrNull { it.key == st.day } ?: days.firstOrNull { it.key == now.key } ?: days.first()
    val tk = themeOf(st, days, day.key)
    val stops = planStops(st, days, day)
    val doneN = stops.count { st.isSeen(it.id) }
    val next = stops.firstOrNull { !st.isSeen(it.id) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Trip starts", 13.5.sp, color = c.muted)
                val shape = RoundedCornerShape(9.dp)
                val sd = days.first()
                Box(Modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable {
                    val d = LocalDate.parse(sd.key)
                    DatePickerDialog(ctx, { _, y, m, dd ->
                        st.setStart(LocalDate.of(y, m + 1, dd).toString()); st.setDay("")
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }.padding(horizontal = 10.dp, vertical = 7.dp)) { T("${sd.d} ${MON[sd.m].take(3)}", 14.sp) }
                Spacer(Modifier.width(6.dp))
                T("Days", 13.5.sp, color = c.muted)
                var exp by remember { mutableStateOf(false) }
                Box {
                    Box(Modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable { exp = true }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        T("${st.len}  ▾", 14.sp)
                    }
                    DropdownMenu(exp, { exp = false }) {
                        (1..7).forEach { n -> DropdownMenuItem(text = { Text("$n") }, onClick = { st.setLen(n); exp = false }) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    val on = d.key == day.key
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.weight(1f).clip(shape).background(if (on) c.accent else c.surface).border(1.dp, if (on) c.accent else c.line, shape)
                        .clickable { st.setDay(d.key) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        T((if (d.key == now.key) "Today" else DOW[d.dow]).uppercase(), 11.sp, FontWeight.SemiBold, if (on) c.accentFg else c.muted, letterSpacing = .6.sp)
                        T("${d.d}", 20.sp, FontWeight.SemiBold, if (on) c.accentFg else c.fg, Fonts.display)
                    }
                }
                repeat(maxOf(0, 4 - days.size)) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Theme for this day", modifier = Modifier.weight(1f))
                T("Swipe for more ›", 13.sp, FontWeight.SemiBold, c.muted)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Trip.THEME_ORDER.mapNotNull { k -> Trip.THEME_MAP[k]?.let { k to it } }.forEach { (k, th) ->
                    val on = k == tk
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.width(150.dp).clip(shape).background(if (on) c.accentSoft else c.surface)
                        .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, shape).clickable { st.setTheme(day.key, k) }.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T(th.name, 14.5.sp, FontWeight.SemiBold, maxLines = 2)
                        T(th.blurb, 12.5.sp, color = c.muted, maxLines = 2)
                    }
                }
            }
        }
        item {
            Column {
                T(Trip.THEME_MAP[tk]?.name.orEmpty(), 20.sp, FontWeight.SemiBold, family = Fonts.display)
                val total = stops.sumOf { it.min }
                T("$doneN of ${stops.size} done · about ${dur(Math.round(total / 30.0).toInt() * 30)} of visits, at your own pace", 14.sp, color = c.muted)
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                    Box(Modifier.fillMaxWidth(if (stops.isEmpty()) 0f else doneN / stops.size.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.accent))
                }
            }
        }
        val notes = dayNotes(day, stops)
        if (notes.size == 1) item { WarnNote(notes) }
        if (notes.size > 1) item {
            var openTips by remember(day.key) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shape = RoundedCornerShape(10.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.warn, shape).clickable { openTips = !openTips }
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${notes.size} tips for this day", 14.5.sp, FontWeight.SemiBold, c.warn, modifier = Modifier.weight(1f))
                    T(if (openTips) "Hide" else "Show ›", 14.sp, FontWeight.SemiBold, c.warn)
                }
                if (openTips) notes.forEach { WarnNote(listOf(it)) }
            }
        }
        if (here != null) item {
            Btn("Reorder from where I am", Modifier.fillMaxWidth(), BtnKind.Ghost) {
                st.setOrder(day.key + ":" + tk, reorderFromHere(stops, st, here))
            }
        }
        item {
            Column {
                stops.forEachIndexed { i, s -> StepRow(ui, i, s, s == next, here) }
            }
        }
        val ref = next ?: stops.lastOrNull()
        if (ref != null && Trip.FOOD.isNotEmpty()) {
            item { Label("Eat near " + (next?.name ?: "your last stop")) }
            items(eatNear(ref), key = { "eat-" + it.id }) { PlaceCard(ui, it, here) }
        }
        item { T("No fixed times: tap Done when you finish a stop, or skip ahead. Done stops also count as visited on the other tabs.", 12.5.sp, color = c.muted) }
    }
}

@Composable
fun StepRow(ui: Ui, i: Int, s: Place, isNext: Boolean, here: LatLng?) {
    val c = LocalPal.current
    val st = ui.st
    val done = st.isSeen(s.id)
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ring = if (done) c.food else if (isNext) c.accent else c.line
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) c.food else c.surface).border(1.5.dp, ring, CircleShape)
                .clickable { ui.toggleSeen(s.id) }, contentAlignment = Alignment.Center) {
                T(if (done) "✓" else "${i + 1}", 13.sp, FontWeight.SemiBold, if (done) c.surface else if (isNext) c.accent else c.muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.name, modifier = Modifier.clickable { ui.open = s.id }, style = TextStyle(
                    fontFamily = Fonts.body, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (done) c.muted else c.fg,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.Underline))
                if (Trip.isFree(s)) FreeBadge()
                if (!done) distInfo(here, s)?.let { T(it.text, 13.5.sp, FontWeight.SemiBold, c.accent) }
                T(s.desc + " About " + dur(s.min) + ".", 14.sp, color = c.muted)
                val n = st.note(s.id)
                if (!n.isEmpty) { Stars(n.stars); if (n.text.isNotBlank()) T("“" + n.text.trim() + "”", 13.sp, maxLines = 2, italic = true) }
            }
            Column(Modifier.width(74.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Go", Modifier.fillMaxWidth(), small = true) { ui.openUrl(mapsUrl(here, s)) }
                val sb = RoundedCornerShape(9.dp)
                Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(sb).background(if (done) c.foodSoft else Color.Transparent)
                    .border(1.dp, if (done) c.food else c.line, sb).clickable { ui.toggleSeen(s.id) }.padding(6.dp), contentAlignment = Alignment.Center) {
                    T(if (done) "Done ✓" else "Done", 13.sp, FontWeight.SemiBold, if (done) c.food else c.muted)
                }
            }
        }
    }
}

// ---------- Visited tab ----------

@Composable
fun VisitedTab(ui: Ui) {
    val c = LocalPal.current
    val st = ui.st
    val entries = st.visited.entries.filter { Trip.BY_ID.containsKey(it.key) }.sortedByDescending { it.value }
    val byDay = entries.groupBy { visitedDayKey(it.value) }
    val noted = st.notes.keys.filter { Trip.BY_ID.containsKey(it) && !st.visited.containsKey(it) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entries.isEmpty()) item {
            T("Tap Visited on any place, or tick a stop in Day plans. It's saved on your phone with the date and time, together with your notes and stars.",
                15.sp, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
        }
        byDay.forEach { (dk, list) ->
            item(key = "d-$dk") { Label(dayTitle(dk), modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { "v-" + it.key }) { e -> VisitedRow(ui, Trip.BY_ID.getValue(e.key), e.value) }
        }
        if (noted.isNotEmpty()) {
            item(key = "notes") { Label("Notes on places not visited yet", modifier = Modifier.padding(top = 8.dp)) }
            items(noted, key = { "n-$it" }) { VisitedRow(ui, Trip.BY_ID.getValue(it), null) }
        }
    }
}

@Composable
fun VisitedRow(ui: Ui, p: Place, at: Long?) {
    val c = LocalPal.current
    val cat = Trip.catOf(p)
    val n = ui.st.note(p.id)
    Card(Modifier.clickable { ui.open = p.id }) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Tag(Trip.label(cat), Trip.isFoodCat(cat))
                T(p.name, 16.sp, FontWeight.SemiBold)
                Stars(n.stars)
                if (n.text.isNotBlank()) T("“" + n.text.trim() + "”", 13.5.sp, italic = true, maxLines = 4)
            }
            if (at != null) T(visitedWhen(at).substringAfter(", "), 14.sp, FontWeight.SemiBold, c.food)
        }
    }
}
