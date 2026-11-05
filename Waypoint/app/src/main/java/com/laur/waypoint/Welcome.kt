package com.laur.waypoint

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Shared layout for the welcome screens: scrolling content, page dots, buttons at the bottom. */
@Composable
private fun Pages(page: Int, count: Int, content: @Composable ColumnScope.() -> Unit, buttons: @Composable ColumnScope.() -> Unit) {
    val c = LocalPal.current
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (count > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp).clearAndSetSemantics { contentDescription = "Step ${page + 1} of $count" }) {
                repeat(count) { i -> Box(Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) c.accent else c.line)) }
            }
            buttons()
        }
    }
}

@Composable
private fun Title(text: String) =
    T(text, 32.sp, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })

/** First launch of Waypoint: what it is, then location. */
@Composable
fun HubWelcome(locationOn: Boolean, requestLocation: () -> Unit) {
    val c = LocalPal.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(page > 0) { page-- }
    Pages(page, 2, content = {
        if (page == 0) {
            BrandMark(56)
            Title("Welcome to Waypoint")
            T("Pocket city guides that work the way you travel. No account needed, and everything you save stays on your phone.", Ty.bodyL, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics { }) {
                Europe.countries.take(8).forEach { FlagRect(it.code, 30.dp) }
            }
            T("All ${Europe.countries.size} European countries and their big cities, plus any town you search for.", Ty.bodyS, FontWeight.SemiBold, c.muted)
            Spacer(Modifier.height(4.dp))
            Feature(PinIcon, "What's near you right now", "Sights, food and bars sorted by walking distance, with one-tap directions.")
            Feature(ListIcon, "Day plans at your own pace", "Ready-made routes for each day. No fixed times, just tick stops off.")
            Feature(FoodIcon, "Prices and menus", "An estimated cost per person and typical dishes for every restaurant and café.")
            Feature(VisitedIcon, "Your travel diary", "Places you visited in every city, with the date and time, your notes and star ratings.")
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(LocateIcon, null, tint = c.accent, modifier = Modifier.size(28.dp))
            }
            Title("Show what's near you?")
            T("Waypoint uses your location only while it's open, to sort places by distance and give walking times. Your location is never sent anywhere.",
                Ty.bodyL, color = c.muted)
            if (locationOn) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.accentSoft).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(CheckIcon, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    T("Location is on", Ty.body, FontWeight.SemiBold)
                }
            } else T("If you'd rather not, you can pick where you are by hand inside each city.", Ty.body, color = c.muted)
        }
    }) {
        if (page == 0) Btn("Get started", Modifier.fillMaxWidth()) { page = 1 }
        else if (locationOn) Btn("Show my trips", Modifier.fillMaxWidth()) { Hub.setOnboarded() }
        else {
            Btn("Allow location", Modifier.fillMaxWidth(), icon = LocateIcon) { requestLocation() }
            Btn("Not now", Modifier.fillMaxWidth(), BtnKind.Ghost) { Hub.setOnboarded() }
        }
    }
}

/** First time a city is opened: trip dates, then offline photos. */
@Composable
fun CityWelcome(ui: Ui, now: Now) {
    val c = LocalPal.current
    val st = ui.st
    val city = Trip.city
    var page by rememberSaveable(city.key) { mutableIntStateOf(0) }
    BackHandler { if (page > 0) page-- else ui.onBack() }
    val finish = { st.setTab("near"); st.setOnboarded() }
    Pages(page, 2, content = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlagTile(codeOf(city), 44.dp, label = city.country)
            T(city.name, Ty.title, FontWeight.SemiBold, family = Fonts.display)
        }
        if (page == 0) {
            Title("When is your trip?")
            T("The day plans follow your dates. You can change this any time in Day plans.", Ty.bodyL, color = c.muted)
            val days = tripDays(st.start.ifBlank { now.key }, st.len)
            val sd = days.first()
            var picking by remember { mutableStateOf(false) }
            Label("First day", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Rad.m)).background(c.surface)
                .clickable(role = Role.Button, onClickLabel = "Change the first day") { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(CalendarIcon, null, tint = c.accent, modifier = Modifier.size(22.dp))
                T("${DOW[sd.dow]} ${sd.d} ${MON[sd.m]}" + (if (sd.key == now.key) " (today)" else ""), Ty.bodyL, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("Change", Ty.body, FontWeight.SemiBold, c.accent)
            }
            if (picking) TripDatePicker(LocalDate.parse(sd.key), onPick = { st.setStart(it.toString()); st.setDay("") }, onDismiss = { picking = false })
            Label("How many days", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { n ->
                    val on = st.len == n
                    Box(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surface)
                        .clickable(role = Role.RadioButton) { st.setLen(n) }
                        .semantics { selected = on; contentDescription = "$n day" + if (n == 1) "" else "s" },
                        contentAlignment = Alignment.Center) {
                        T("$n", 20.sp, FontWeight.SemiBold, if (on) c.accentFg else c.fg, Fonts.display)
                    }
                }
            }
            T(tripLabel(days), Ty.body, color = c.muted)
        } else {
            Title("Save photos for offline?")
            T("Download photos and info for all ${Trip.PLACES.size} places in ${city.name} now, so everything works without internet. " +
                "It's roughly 30–50 MB, so Wi-Fi is best. It keeps going in the background while you use the app.", Ty.bodyL, color = c.muted)
            T("If you skip, each place loads the first time you open it, and you can download everything later from the bottom of the Nearby tab.",
                Ty.body, color = c.muted)
        }
    }) {
        if (page == 0) {
            Btn("Next", Modifier.fillMaxWidth()) { if (st.start.isBlank()) st.setStart(now.key); page = 1 }
            Btn("I'm already in ${city.name}, start today", Modifier.fillMaxWidth(), BtnKind.Ghost) { st.setStart(now.key); st.setDay(""); page = 1 }
        } else {
            Btn("Download now and start", Modifier.fillMaxWidth(), icon = DownloadIcon) { ui.downloadAll(); finish() }
            Btn("Skip, load photos as I go", Modifier.fillMaxWidth(), BtnKind.Ghost) { finish() }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    val c = LocalPal.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, Ty.bodyL, FontWeight.SemiBold)
            T(text, Ty.bodyS, color = c.muted)
        }
    }
}
