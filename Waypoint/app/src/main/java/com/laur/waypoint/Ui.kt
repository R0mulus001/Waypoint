package com.laur.waypoint

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.CircularProgressIndicator
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
    /** Places to eat are still loading in the background (the guide opened without them). */
    var foodLoading by mutableStateOf(false)
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
            reloadFood: suspend () -> Unit = {}, foodLoading: Boolean = false, onBack: () -> Unit) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    // Keyed on photos and dl too: "Load places to eat" swaps in a new city, and its new Photos/Downloader must reach the screens.
    val ui = remember(st, photos, dl) { Ui(st, photos, dl, openUrl, requestLocation, scope, onBack) }
    ui.reloadFood = reloadFood
    ui.foodLoading = foodLoading
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

/** "Visited" as a real toggle: a checkbox for screen readers, a check and the word on screen. */
@Composable
fun VisitedToggle(seen: Boolean, modifier: Modifier = Modifier, label: String = "Visited", onToggle: () -> Unit) {
    val c = LocalPal.current
    val shape = CircleShape
    var m = modifier.heightIn(min = 48.dp).clip(shape).background(if (seen) c.visitedSoft else c.surface)
    if (!seen) m = m.border(1.dp, c.line, shape)
    Row(m.toggleable(value = seen, role = Role.Checkbox) { onToggle() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val pop by animateFloatAsState(if (seen) 1f else 0.85f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "pop")
        Icon(if (seen) CheckIcon else VisitedIcon, null, tint = if (seen) c.visited else c.muted, modifier = Modifier.size(18.dp).scale(pop))
        Spacer(Modifier.width(6.dp))
        T(label, Ty.bodyS, FontWeight.SemiBold, if (seen) c.visited else c.fg, maxLines = 1)
    }
}

/** A note and stars on a place, shown under its description. */
@Composable
fun NoteLine(note: Note) {
    if (note.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Stars(note.stars)
        if (note.text.isNotBlank()) T("“" + note.text.trim() + "”", Ty.bodyS, color = LocalPal.current.fg, maxLines = 2, italic = true)
    }
}

private fun mustSub(cat: String) = when (cat) {
    "museum" -> "The most visited museums in ${Trip.city.name}"
    "food" -> "The best-known places to eat in town"
    else -> "What everyone comes to ${Trip.city.name} to see"
}

/** Heading of the "Don't miss" group (with a star) and of the list after it. */
@Composable
private fun MustSeeHead(title: String, sub: String, star: Boolean = true) {
    val c = LocalPal.current
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (star) Box(Modifier.size(32.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(StarFilled, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Column {
            T(title, Ty.bodyL, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })
            T(sub, Ty.caption, color = c.muted)
        }
    }
}

@Composable
fun PlaceCard(ui: Ui, p: Place, here: LatLng?, top: Boolean = false) {
    val c = LocalPal.current
    val st = ui.st
    val cat = Trip.catOf(p)
    val seen = st.isSeen(p.id)
    // "Don't miss" places: an accent outline and a star, so they stand apart from the rest of the list.
    val mark = if (top) Modifier.border(1.5.dp, c.accent.copy(alpha = if (seen) .35f else 1f), RoundedCornerShape(Rad.m)) else Modifier
    Card(mark, onClick = { ui.open = p.id }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (top) Icon(StarFilled, "Don't miss", tint = c.accent, modifier = Modifier.size(16.dp))
                Tag(Trip.label(cat), icon = catIcon(cat))
                if (Trip.priceText(p).isNotEmpty()) T(Trip.priceText(p), Ty.label, FontWeight.Medium, c.muted)
                Spacer(Modifier.weight(1f))
                distInfo(here, p)?.let { T(it.text, Ty.label, FontWeight.SemiBold, c.accent, maxLines = 1) }
            }
            T(p.name, Ty.bodyL, FontWeight.SemiBold, color = if (seen) c.muted else c.fg)
            if (Trip.isFree(p)) FreeBadge()
            T(p.desc, Ty.bodyS, color = c.muted, maxLines = 3)
            NoteLine(st.note(p.id))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Directions", Modifier.weight(1f), BtnKind.Tonal, small = true, icon = GoIcon) { ui.openUrl(mapsUrl(here, p)) }
                VisitedToggle(seen, Modifier.weight(1f)) { ui.toggleSeen(p.id) }
            }
        }
    }
}

// ---------- Nearby tab ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyTab(ui: Ui, now: Now, here: LatLng?, hasGps: Boolean, locationOn: Boolean, away: Boolean = false) {
    val c = LocalPal.current
    val st = ui.st
    val filter = st.filter.takeIf { f -> Trip.FILTERS.any { it.first == f } } ?: Trip.FILTERS.first().first
    var showN by remember { mutableIntStateOf(12) }
    val (mo, pick) = heroPick(st, now, here, ui.skipped.value)

    // Sights, museums and restaurants open with their best-known places; the rest follow, closest first.
    var must = mustSees(filter)
    val mustIds = must.map { it.id }.toSet()
    var list = Trip.PLACES.filter { p -> p.id !in mustIds && (filter == "all" || (if (filter == "free") Trip.isFree(p) else Trip.catOf(p) == filter)) }
    if (!st.showSeen) { list = list.filter { !st.isSeen(it.id) }; must = must.filter { !st.isSeen(it.id) } }
    list = sortNear(list, here)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { LocRow(ui, hasGps, locationOn, away) }
        if (pick != null) item { Hero(ui, mo, pick, here) }
        item {
            SectionHead(if (away) "Top places" else "Around you",
                if (away) "Most important first. Distances appear when you're in ${Trip.city.name}." else "Closest first. Tap Directions to get there.",
                Modifier.padding(top = 12.dp))
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Trip.FILTERS.forEach { (v, l) ->
                    val on = filter == v
                    FilterChip(selected = on, onClick = { st.setFilter(v); showN = 12 }, label = { Text(l, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) },
                        leadingIcon = if (on) ({ Icon(CheckIcon, null, Modifier.size(16.dp)) }) else null,
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(containerColor = c.surface, labelColor = c.fg,
                            selectedContainerColor = c.fg, selectedLabelColor = c.bg, selectedLeadingIconColor = c.bg))
                }
            }
        }
        if (here == null && !away) item { WarnNote(listOf("" to "Turn on location, or pick where you are above, to sort places by distance.")) }
        if (must.isNotEmpty()) {
            item(key = "must-head") { MustSeeHead("Don't miss", mustSub(filter)) }
            items(must, key = { "must-" + it.id }) { PlaceCard(ui, it, here, top = true) }
            if (list.isNotEmpty()) item(key = "more-head") {
                MustSeeHead(if (filter == "food") "More places to eat" else "More to explore", if (away) "Most important first" else "Closest first", star = false)
            }
        }
        if (list.isEmpty() && must.isEmpty()) item {
            T("Nothing left in this category. Show visited places to see them again.", Ty.body, color = c.muted, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
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
        item { T("Opening hours change, so check in Google Maps before you go.", Ty.caption, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

/** Card title row for the small status cards: an icon tile and a label. */
@Composable
private fun CardHead(icon: ImageVector, text: String) {
    val c = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.fg, modifier = Modifier.size(18.dp))
        }
        Label(text, c.fg)
    }
}

@Composable
fun FoodRetryRow(ui: Ui) {
    val c = LocalPal.current
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHead(FoodIcon, "Places to eat and drink")
            if (ui.foodLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    T("Finding cafés, restaurants and bars… They'll appear here by themselves in a moment.", Ty.bodyS, color = c.muted)
                }
                return@Column
            }
            T(msg ?: "Cafés, restaurants and bars couldn't load when this guide was built. The map service may have been busy.", Ty.bodyS, color = c.muted)
            Btn(if (busy) "Loading…" else "Load places to eat", Modifier.fillMaxWidth(), BtnKind.Tonal, small = true) {
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHead(PhotoIcon, "Offline photos")
            when {
                dl.running -> {
                    T("Downloading… ${dl.done} of ${dl.total} places", Ty.body, FontWeight.SemiBold)
                    Bar(if (dl.total == 0) 0f else dl.done / dl.total.toFloat())
                    T("It keeps going if you leave this screen. Photos found so far: ${dl.withPhotos} places.", Ty.label, color = c.muted)
                }
                dl.failed == 0 -> {
                    T("All done. Photos and info are saved on your phone and work without internet.", Ty.body)
                    val none = dl.places - dl.withPhotos
                    if (none > 0) T("Photos for ${dl.withPhotos} places. The other $none have no photos online (mostly cafés and restaurants).", Ty.label, color = c.muted)
                }
                dl.finished == 0 && dl.withPhotos == 0 -> {
                    T("Download photos and info for all ${dl.places} places so the app works without internet (Wi-Fi is best).", Ty.bodyS, color = c.muted)
                    Btn("Download all", Modifier.fillMaxWidth(), BtnKind.Tonal, small = true, icon = DownloadIcon) { dl.start() }
                }
                else -> {
                    T("${dl.finished} of ${dl.places} places done, photos for ${dl.withPhotos}. ${dl.failed} still to go " +
                        "(or the server was busy).", Ty.bodyS, color = c.muted)
                    Btn("Download the remaining ${dl.failed}", Modifier.fillMaxWidth(), BtnKind.Tonal, small = true, icon = DownloadIcon) { dl.start() }
                }
            }
        }
    }
}

@Composable
fun LocRow(ui: Ui, hasGps: Boolean, locationOn: Boolean, away: Boolean = false) {
    val c = LocalPal.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(if (hasGps) 16.dp else 8.dp).clip(CircleShape).background(if (hasGps) c.visited.copy(alpha = .22f) else Color.Transparent),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (hasGps) c.visited else c.muted))
            }
            if (hasGps) { T("Using your location", Ty.label, color = c.muted); return@Row }
            if (away) { T("You're not in ${Trip.city.name} yet", Ty.label, color = c.muted); return@Row }
            if (locationOn) { T("Finding your location…", Ty.label, color = c.muted); return@Row }
            T("Location off. I'm near:", Ty.label, color = c.muted)
            var exp by remember { mutableStateOf(false) }
            Box {
                PickerChip(Trip.LANDMARKS.firstOrNull { it.first == ui.st.manual }?.second ?: "Choose a place", PinIcon, "Choose where you are") { exp = true }
                DropdownMenu(exp, { exp = false }) {
                    Trip.LANDMARKS.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { ui.st.setManual(id); exp = false }) }
                }
            }
        }
        if (!hasGps && !locationOn && !away) TextButton(onClick = { ui.requestLocation() }) {
            Icon(LocateIcon, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Turn on location", color = c.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Right now": the one suggestion for this moment. Tonal, with a single filled action. */
@Composable
fun Hero(ui: Ui, mo: Moment, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val cat = Trip.catOf(p)
    val food = Trip.isFoodCat(cat)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.l)).background(c.accentSoft)) {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${p.name}") { ui.open = p.id }.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(catIcon(cat), null, tint = c.fg, modifier = Modifier.size(18.dp))
                T(mo.k + " · " + Trip.label(cat), Ty.label, FontWeight.SemiBold, c.fg)
            }
            T(p.name, 26.sp, FontWeight.SemiBold, c.fg, Fonts.display)
            T(distInfo(here, p)?.text ?: "Distance shows when you're nearby", Ty.bodyS, FontWeight.SemiBold, c.fg)
            T(p.desc, Ty.bodyS, color = c.fg, maxLines = 4)
            T(if (food) "Prices & menu" else "See photos", Ty.bodyS, FontWeight.SemiBold, c.fg, underline = true, modifier = Modifier.padding(vertical = 6.dp))
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Take me there", Modifier.weight(1f), icon = GoIcon) { ui.openUrl(mapsUrl(here, p)) }
            Btn("Something else", Modifier.weight(1f), BtnKind.Ghost, icon = ShuffleIcon) { ui.skipped.value = ui.skipped.value + p.id }
        }
    }
}

@Composable
fun WarnNote(parts: List<Pair<String, String>>) {
    val c = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.cautionSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(TipIcon, null, tint = c.caution, modifier = Modifier.size(20.dp))
        Text(buildAnnotatedString {
            parts.forEach { (b, rest) ->
                if (b.isNotEmpty()) { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.caution)); append(b); pop() }
                append(rest)
            }
        }, style = TextStyle(fontSize = Ty.bodyS, color = c.fg, fontFamily = Fonts.body, lineHeight = 20.sp))
    }
}

// ---------- Day plans tab ----------

@Composable
fun PlansTab(ui: Ui, now: Now, here: LatLng?, hasGps: Boolean) {
    val c = LocalPal.current
    val st = ui.st
    val days = tripDays(st.start.ifBlank { now.key }, st.len)
    val day = days.firstOrNull { it.key == st.day } ?: days.firstOrNull { it.key == now.key } ?: days.first()
    val tk = themeOf(st, days, day.key)
    val stops = planStops(st, days, day)
    val doneN = stops.count { st.isSeen(it.id) }
    val next = stops.firstOrNull { !st.isSeen(it.id) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            var picking by remember { mutableStateOf(false) }
            var exp by remember { mutableStateOf(false) }
            val sd = days.first()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerChip("Starts ${sd.d} ${MON[sd.m].take(3)}", CalendarIcon, "Change when the trip starts") { picking = true }
                Box {
                    PickerChip("${st.len} day" + (if (st.len == 1) "" else "s"), null, "Change how many days") { exp = true }
                    DropdownMenu(exp, { exp = false }) {
                        (1..7).forEach { n ->
                            DropdownMenuItem(text = { Text("$n day" + if (n == 1) "" else "s") }, onClick = { st.setLen(n); exp = false },
                                trailingIcon = { if (n == st.len) Icon(CheckIcon, "Selected", tint = c.accent) })
                        }
                    }
                }
            }
            if (picking) TripDatePicker(LocalDate.parse(sd.key), onPick = { st.setStart(it.toString()); st.setDay("") }, onDismiss = { picking = false })
        }
        item {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    val on = d.key == day.key
                    val isToday = d.key == now.key
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surface)
                        .selectable(selected = on, role = Role.Tab) { st.setDay(d.key) }
                        .semantics { contentDescription = (if (isToday) "Today, " else "") + "${DOW[d.dow]} ${d.d} ${MON[d.m]}" }
                        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        T(if (isToday) "Today" else DOW[d.dow], Ty.caption, FontWeight.SemiBold, if (on) c.accentFg else c.muted, maxLines = 1)
                        T("${d.d}", 22.sp, FontWeight.SemiBold, if (on) c.accentFg else c.fg, Fonts.display)
                    }
                }
                repeat(maxOf(0, 4 - days.size)) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Theme for this day", modifier = Modifier.weight(1f))
                T("Swipe for more", Ty.label, FontWeight.Medium, c.muted)
                Icon(ChevronIcon, null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Trip.THEME_ORDER.mapNotNull { k -> Trip.THEME_MAP[k]?.let { k to it } }.forEach { (k, th) ->
                    val on = k == tk
                    val shape = RoundedCornerShape(Rad.m)
                    Column(Modifier.width(164.dp).clip(shape).background(if (on) c.accentSoft else c.surface)
                        .then(if (on) Modifier.border(2.dp, c.accent, shape) else Modifier)
                        .selectable(selected = on, role = Role.RadioButton) { st.setTheme(day.key, k) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T(th.name, Ty.bodyS, FontWeight.SemiBold, maxLines = 2)
                        T(th.blurb, Ty.caption, color = c.muted, maxLines = 2)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val total = stops.sumOf { it.min }
                SectionHead(Trip.THEME_MAP[tk]?.name.orEmpty(),
                    "$doneN of ${stops.size} done · about ${dur(Math.round(total / 30.0).toInt() * 30)} of visits, at your own pace")
                Bar(if (stops.isEmpty()) 0f else doneN / stops.size.toFloat())
            }
        }
        val notes = dayNotes(day, stops)
        if (notes.size == 1) item { WarnNote(notes) }
        if (notes.size > 1) item {
            var openTips by remember(day.key) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Rad.m)).background(c.cautionSoft)
                    .clickable(onClickLabel = if (openTips) "Hide tips" else "Show tips") { openTips = !openTips }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TipIcon, null, tint = c.caution, modifier = Modifier.size(20.dp))
                    T("${notes.size} tips for this day", Ty.bodyS, FontWeight.SemiBold, c.fg, modifier = Modifier.weight(1f))
                    T(if (openTips) "Hide" else "Show", Ty.bodyS, FontWeight.SemiBold, c.caution)
                    Icon(if (openTips) UpChevronIcon else DownIcon, null, tint = c.caution, modifier = Modifier.size(18.dp))
                }
                if (openTips) notes.forEach { WarnNote(listOf(it)) }
            }
        }
        if (here != null) item {
            Btn("Reorder from where I am", Modifier.fillMaxWidth(), BtnKind.Ghost, icon = LocateIcon) {
                st.setOrder(day.key + ":" + tk, reorderFromHere(stops, st, here))
            }
        }
        item {
            Column(Modifier.clip(RoundedCornerShape(Rad.m)).background(c.surface).padding(vertical = 8.dp)) {
                stops.forEachIndexed { i, s ->
                    RouteStop(ui, i, s, isNext = s == next, here = here,
                        prevDone = i > 0 && st.isSeen(stops[i - 1].id), first = i == 0, last = i == stops.size - 1)
                }
            }
        }
        val ref = next ?: stops.lastOrNull()
        if (ref != null && Trip.FOOD.isNotEmpty()) {
            item { Label("Eat near " + (next?.name ?: "your last stop"), modifier = Modifier.padding(top = 8.dp)) }
            items(eatNear(ref), key = { "eat-" + it.id }) { PlaceCard(ui, it, here) }
        }
        item {
            T("No fixed times: mark a stop Visited when you finish it, or skip ahead. Visited stops count on the other tabs too.",
                Ty.caption, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

private val UpChevronIcon = icon("M6 15l6-6 6 6")

/**
 * One stop on the day's route line: a numbered node on a line in the country's colour.
 * The line above a stop is solid once the stop before it is visited, faint until then.
 */
@Composable
fun RouteStop(ui: Ui, i: Int, s: Place, isNext: Boolean, here: LatLng?, prevDone: Boolean, first: Boolean, last: Boolean) {
    val c = LocalPal.current
    val st = ui.st
    val done = st.isSeen(s.id)
    val nodeFill by animateColorAsState(if (done) c.visited else if (isNext) c.accentSoft else c.surface, tween(200), label = "node")
    val lineDone = c.accent
    val lineTodo = c.accent.copy(alpha = 0.28f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 16.dp)) {
        // The rail: line segments and the node (48 dp touch target, a checkbox for screen readers).
        Box(Modifier.width(64.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2f
            val nodeY = 12.dp.toPx() + 24.dp.toPx()
            val w = 3.dp.toPx()
            if (!first) drawLine(if (prevDone) lineDone else lineTodo, Offset(x, 0f), Offset(x, nodeY), w)
            if (!last) drawLine(if (done) lineDone else lineTodo, Offset(x, nodeY), Offset(x, size.height), w)
        }) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(48.dp).clip(CircleShape)
                .toggleable(value = done, role = Role.Checkbox) { ui.toggleSeen(s.id) }
                .semantics { contentDescription = "Stop ${i + 1}, ${s.name}, visited" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(nodeFill)
                    .border(if (isNext && !done) 2.5.dp else 1.5.dp, if (done) c.visited else if (isNext) c.accent else c.line, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(CheckIcon, null, tint = if (c.dark) c.bg else Color.White, modifier = Modifier.size(18.dp))
                    else T("${i + 1}", Ty.label, FontWeight.SemiBold, if (isNext) c.accent else c.muted, Fonts.display)
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.clip(RoundedCornerShape(Rad.s)).clickable(onClickLabel = "Open ${s.name}") { ui.open = s.id },
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (isNext && !done) T("Next stop", Ty.caption, FontWeight.SemiBold, c.accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(s.name, Ty.bodyL, FontWeight.SemiBold, if (done) c.muted else c.fg, modifier = Modifier.weight(1f, fill = false))
                    Icon(ChevronIcon, null, tint = c.muted, modifier = Modifier.size(18.dp))
                }
                if (Trip.isFree(s)) FreeBadge()
                if (!done) distInfo(here, s)?.let { T(it.text, Ty.label, FontWeight.SemiBold, c.accent) }
                T(s.desc + " About " + dur(s.min) + ".", Ty.bodyS, color = c.muted)
            }
            NoteLine(st.note(s.id))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Directions", Modifier.weight(1f), BtnKind.Tonal, small = true, icon = GoIcon) { ui.openUrl(mapsUrl(here, s)) }
                VisitedToggle(done, Modifier.weight(1f)) { ui.toggleSeen(s.id) }
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (entries.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.visitedSoft), contentAlignment = Alignment.Center) {
                    Icon(VisitedIcon, null, tint = c.visited, modifier = Modifier.size(30.dp))
                }
                T("Nothing visited yet", Ty.headline, FontWeight.SemiBold, family = Fonts.display, align = TextAlign.Center)
                T("Tap Visited on any place, or tick a stop in Day plans. It's saved on your phone with the date and time, together with your notes and stars.",
                    Ty.body, color = c.muted, align = TextAlign.Center)
            }
        }
        byDay.forEach { (dk, list) ->
            item(key = "d-$dk") { Label(dayTitle(dk), modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)) }
            itemsIndexed(list, key = { _, e -> "v-" + e.key }) { i, e -> VisitedRow(ui, Trip.BY_ID.getValue(e.key), e.value, i, list.size) }
        }
        if (noted.isNotEmpty()) {
            item(key = "notes") { Label("Notes on places not visited yet", modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)) }
            itemsIndexed(noted, key = { _, id -> "n-$id" }) { i, id -> VisitedRow(ui, Trip.BY_ID.getValue(id), null, i, noted.size) }
        }
    }
}

@Composable
fun VisitedRow(ui: Ui, p: Place, at: Long?, i: Int, n: Int) {
    val c = LocalPal.current
    val cat = Trip.catOf(p)
    val note = ui.st.note(p.id)
    GroupRow(i, n, onClick = { ui.open = p.id }, dividerInset = 72.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (at != null) c.visitedSoft else c.surface2), contentAlignment = Alignment.Center) {
                Icon(if (at != null) CheckIcon else NoteIcon, null, tint = if (at != null) c.visited else c.muted, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(Trip.label(cat), icon = catIcon(cat))
                    Spacer(Modifier.weight(1f))
                    if (at != null) T(visitedWhen(at).substringAfter(", "), Ty.label, FontWeight.SemiBold, c.muted)
                }
                T(p.name, Ty.bodyL, FontWeight.SemiBold)
                Stars(note.stars)
                if (note.text.isNotBlank()) T("“" + note.text.trim() + "”", Ty.bodyS, italic = true, maxLines = 4)
            }
        }
    }
}
