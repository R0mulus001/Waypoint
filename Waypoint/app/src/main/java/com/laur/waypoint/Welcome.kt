package com.laur.waypoint

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Shared layout for the welcome screens: scrolling content, page dots, buttons at the bottom. */
@Composable
private fun Pages(page: Int, count: Int, content: @Composable ColumnScope.() -> Unit, buttons: @Composable ColumnScope.() -> Unit) {
    val c = LocalPal.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (count > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                repeat(count) { i -> Box(Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) c.accent else c.line)) }
            }
            buttons()
        }
    }
}

/** First launch of Waypoint: what it is, then location. */
@Composable
fun HubWelcome(locationOn: Boolean, requestLocation: () -> Unit) {
    val c = LocalPal.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(page > 0) { page-- }
    Pages(page, 2, content = {
        if (page == 0) {
            BrandMark(54)
            T("Welcome to Waypoint", 30.sp, FontWeight.SemiBold, family = Fonts.display)
            T("Pocket city guides that work the way you travel. No account needed, and everything you save stays on your phone.", 16.sp, color = c.muted)
            T(Europe.countries.take(12).joinToString(" ") { it.emoji }, 22.sp)
            T("All ${Europe.countries.size} European countries and their big cities, plus any town you search for.", 14.sp, FontWeight.SemiBold, c.muted)
            Spacer(Modifier.height(4.dp))
            Feature("📍", "What's near you right now", "Sights, food and bars sorted by walking distance, with one-tap directions.")
            Feature("🗓", "Day plans at your own pace", "Ready-made routes for each day. No fixed times, just tick stops off.")
            Feature("🍽", "Prices and menus", "An estimated cost per person and typical dishes for every restaurant and café.")
            Feature("✓", "Your travel diary", "Places you visited in every city, with the date and time, your notes and star ratings.")
        } else {
            T("Show what's near you?", 28.sp, FontWeight.SemiBold, family = Fonts.display)
            T("Waypoint uses your location only while it's open, to sort places by distance and give walking times. Your location is never sent anywhere.",
                16.sp, color = c.muted)
            if (locationOn) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.foodSoft).padding(16.dp)) {
                    T("Location is on ✓", 16.sp, FontWeight.SemiBold, c.food)
                }
            } else T("If you'd rather not, you can pick where you are by hand inside each city.", 15.sp, color = c.muted)
        }
    }) {
        if (page == 0) Btn("Get started", Modifier.fillMaxWidth()) { page = 1 }
        else if (locationOn) Btn("Show my trips", Modifier.fillMaxWidth()) { Hub.setOnboarded() }
        else {
            Btn("Allow location", Modifier.fillMaxWidth()) { requestLocation() }
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
            T(city.name, 22.sp, FontWeight.SemiBold, family = Fonts.display)
        }
        if (page == 0) {
            T("When is your trip?", 28.sp, FontWeight.SemiBold, family = Fonts.display)
            T("The day plans follow your dates. You can change this any time in Day plans.", 16.sp, color = c.muted)
            val ctx = LocalContext.current
            val days = tripDays(st.start.ifBlank { now.key }, st.len)
            val sd = days.first()
            Label("First day", modifier = Modifier.padding(top = 8.dp))
            val shape = RoundedCornerShape(12.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable {
                val d = LocalDate.parse(sd.key)
                DatePickerDialog(ctx, { _, y, m, dd -> st.setStart(LocalDate.of(y, m + 1, dd).toString()); st.setDay("") },
                    d.year, d.monthValue - 1, d.dayOfMonth).show()
            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${DOW[sd.dow]} ${sd.d} ${MON[sd.m]}" + (if (sd.key == now.key) " (today)" else ""), 17.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("Change", 15.sp, FontWeight.SemiBold, c.accent)
            }
            Label("How many days", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { n ->
                    val on = st.len == n
                    Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.accent else c.surface)
                        .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(12.dp)).clickable { st.setLen(n) },
                        contentAlignment = Alignment.Center) {
                        T("$n", 18.sp, FontWeight.SemiBold, if (on) c.accentFg else c.fg, Fonts.display)
                    }
                }
            }
            T(tripLabel(days), 15.sp, color = c.muted)
        } else {
            T("Save photos for offline?", 28.sp, FontWeight.SemiBold, family = Fonts.display)
            T("Download photos and info for all ${Trip.PLACES.size} places in ${city.name} now, so everything works without internet. " +
                "It's roughly 30–50 MB, so Wi-Fi is best. It keeps going in the background while you use the app.", 16.sp, color = c.muted)
            T("If you skip, each place loads the first time you open it, and you can download everything later from the bottom of the Nearby tab.",
                15.sp, color = c.muted)
        }
    }) {
        if (page == 0) {
            Btn("Next", Modifier.fillMaxWidth()) { if (st.start.isBlank()) st.setStart(now.key); page = 1 }
            Btn("I'm already in ${city.name}, start today", Modifier.fillMaxWidth(), BtnKind.Ghost) { st.setStart(now.key); st.setDay(""); page = 1 }
        } else {
            Btn("Download now and start", Modifier.fillMaxWidth()) { ui.downloadAll(); finish() }
            Btn("Skip, load photos as I go", Modifier.fillMaxWidth(), BtnKind.Ghost) { finish() }
        }
    }
}

@Composable
private fun Feature(icon: String, title: String, text: String) {
    val c = LocalPal.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            T(icon, 18.sp, FontWeight.SemiBold, c.accent, align = TextAlign.Center)
        }
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, FontWeight.SemiBold)
            T(text, 14.5.sp, color = c.muted)
        }
    }
}
