package com.laur.waypoint

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** A place, in Material's bottom sheet: drag handle, swipe down or back to close, scrim tap to close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSheet(ui: Ui, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    ModalBottomSheet(
        onDismissRequest = { ui.open = null }, sheetState = state,
        containerColor = c.bg, contentColor = c.fg, scrimColor = c.scrim, tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.muted.copy(alpha = 0.5f)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SheetContent(ui, p, here, maxH)
        }
    }
}

@Composable
private fun SheetContent(ui: Ui, p: Place, here: LatLng?, maxH: Dp) {
    val c = LocalPal.current
    val st = ui.st
    val cat = Trip.catOf(p)
    val food = p.isFood
    var info by remember(p.id) { mutableStateOf(ui.photos.cached(p.id)) }
    // Look online only if this place was never fully looked up. Places with no photos online used to be
    // searched again (three Commons queries) every single time they were opened.
    var loading by remember(p.id) { mutableStateOf(!ui.photos.isDone(p.id)) }
    LaunchedEffect(p.id) {
        if (loading) { info = runCatching { ui.photos.load(p) }.getOrDefault(info); loading = false }
    }

    Gallery(ui, p, info?.photos ?: emptyList(), loading && info?.photos.isNullOrEmpty(), maxH * 0.5f, food)

    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(Trip.label(cat), icon = catIcon(cat))
            if (food && Trip.priceText(p).isNotEmpty()) T(Trip.priceText(p), Ty.label, FontWeight.Medium, c.muted)
            if (!food && Trip.isFree(p)) FreeBadge()
        }
        T(p.name, 28.sp, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })
        distInfo(here, p)?.let { T(it.text, Ty.body, FontWeight.SemiBold, c.accent) }
        T(p.desc + if (p.min > 0) " Plan about ${dur(p.min)}." else "", Ty.body)

        if (!food) {
            val ex = info?.extract.orEmpty()
            if (ex.isNotBlank()) T(ex, Ty.bodyS, color = c.muted)
            val page = info?.page.orEmpty()
            if (page.isNotBlank()) LinkBtn("Read more on Wikipedia") { ui.openUrl(page) }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Take me there", Modifier.weight(1f), icon = GoIcon) { ui.openUrl(mapsUrl(here, p)) }
                VisitedToggle(st.isSeen(p.id), Modifier.weight(1f), if (st.isSeen(p.id)) "Visited" else "Mark as visited") { ui.toggleSeen(p.id) }
            }
        } else {
            FoodBlock(ui, p, here)
        }
        st.visited[p.id]?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CheckIcon, null, tint = c.visited, modifier = Modifier.size(18.dp))
                T("You visited on " + visitedWhen(it) + ".", Ty.bodyS, FontWeight.SemiBold, c.visited)
            }
        }
        NotesBox(ui, p)
        T(if (food) "Photos from the last 20 years: Wikimedia Commons and Mapillary contributors (CC BY-SA). Saved on your phone after the first look."
          else "Text: Wikipedia. Photos from the last 20 years: Wikimedia Commons and Mapillary contributors (CC BY-SA). Saved on your phone after the first look.",
          Ty.caption, color = c.muted)
    }
}

/** Text link with an external-link icon, at full touch height. */
@Composable
private fun LinkBtn(text: String, onClick: () -> Unit) {
    val c = LocalPal.current
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 0.dp)) {
        Text(text, color = c.accent, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Icon(ExternalIcon, null, tint = c.accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FoodBlock(ui: Ui, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val cost = Trip.COST[p.id]
    var menuOpen by remember(p.id) { mutableStateOf(false) }
    if (cost != null) {
        Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("Estimated cost per person")
            T(Trip.money(cost.lo) + " – " + Trip.money(cost.hi), 30.sp, FontWeight.SemiBold, c.fg, Fonts.display)
            T("For ${cost.basis}. An estimate, not the bill.", Ty.label, color = c.muted)
        }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val menuSearch = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(p.name + " " + Trip.CITY_EN + " menu", "UTF-8")
        if (cost?.menu.isNullOrEmpty()) Btn("Menu online", Modifier.weight(1f), BtnKind.Tonal, icon = ExternalIcon) { ui.openUrl(menuSearch) }
        else Btn(if (menuOpen) "Hide menu" else "Menu", Modifier.weight(1f), if (menuOpen) BtnKind.Dark else BtnKind.Tonal, icon = FoodIcon) { menuOpen = !menuOpen }
        Btn("Take me there", Modifier.weight(1f), icon = GoIcon) { ui.openUrl(mapsUrl(here, p)) }
    }
    if (menuOpen) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Menu highlights")
            cost?.menu?.forEachIndexed { i, (dish, pr) ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = c.line)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(dish, Ty.body, FontWeight.Medium, modifier = Modifier.weight(1f))
                    T("~" + Trip.money(pr), Ty.body, FontWeight.SemiBold, family = Fonts.display)
                }
            }
            if (cost != null) T("Approximate prices. Menus and prices change.", Ty.caption, color = c.muted)
            LinkBtn("See the full menu online") {
                ui.openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(p.name + " " + Trip.CITY_EN + " menu", "UTF-8"))
            }
        }
    }
    VisitedToggle(ui.st.isSeen(p.id), Modifier.fillMaxWidth(), if (ui.st.isSeen(p.id)) "Visited" else "Mark as visited") { ui.toggleSeen(p.id) }
}

@Composable
private fun NotesBox(ui: Ui, p: Place) {
    val c = LocalPal.current
    val st = ui.st
    var text by remember(p.id) { mutableStateOf(st.note(p.id).text) }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NoteIcon, null, tint = c.fg, modifier = Modifier.size(18.dp))
            Label("Your notes", c.fg)
        }
        val stars = st.note(p.id).stars
        Row(Modifier.padding(start = 0.dp).semantics { contentDescription = "Your rating: $stars of 5 stars" }) {
            (1..5).forEach { n ->
                IconButton(onClick = { st.setNote(p.id, st.note(p.id).copy(stars = if (stars == n) 0 else n)) },
                    modifier = Modifier.semantics { selected = n <= stars }) {
                    Icon(if (n <= stars) StarFilled else StarIcon, "Rate $n star" + if (n == 1) "" else "s",
                        tint = if (n <= stars) c.fg else c.muted, modifier = Modifier.size(28.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp)) {
            if (text.isEmpty()) T("Add a note: what you ate, what to remember…", Ty.body, color = c.muted)
            BasicTextField(
                value = text,
                onValueChange = { text = it; st.setNote(p.id, st.note(p.id).copy(text = it)) },
                textStyle = TextStyle(fontSize = Ty.body, color = c.fg, fontFamily = Fonts.body, lineHeight = 22.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics { contentDescription = "Your note" },
            )
        }
        T("Saved automatically on your phone.", Ty.caption, color = c.muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Gallery(ui: Ui, p: Place, files: List<File>, loading: Boolean, maxH: Dp, food: Boolean) {
    val c = LocalPal.current
    val closeBtn = @Composable { onPhoto: Boolean ->
        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(if (onPhoto) Color.Black.copy(alpha = .5f) else c.surface),
            contentAlignment = Alignment.Center) {
            IconButton(onClick = { ui.open = null }) {
                Icon(CloseIcon, "Close", tint = if (onPhoto) Color.White else c.fg, modifier = Modifier.size(20.dp))
            }
        }
    }
    val shape = RoundedCornerShape(Rad.m)
    when {
        loading -> {
            val t = rememberInfiniteTransition(label = "pulse")
            val a by t.animateFloat(1f, .6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(4f / 3f).heightIn(max = maxH).clip(shape)) {
                Box(Modifier.fillMaxSize().alpha(a).background(c.surface2))
                Box(Modifier.align(Alignment.TopEnd)) { closeBtn(true) }
            }
        }
        files.isEmpty() -> {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PhotoIcon, null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                T(if (food) "No inside photos available" else "No preview available", Ty.label, color = c.muted, modifier = Modifier.weight(1f))
                closeBtn(false)
            }
        }
        else -> {
            val pager = rememberPagerState { files.size }
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(4f / 3f).heightIn(max = maxH).clip(shape).background(c.surface2)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { files[it].path }) { i ->
                    PhotoView(ui, files[i], p.name + " photo " + (i + 1))
                }
                if (files.size > 1) {
                    Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        T("${pager.currentPage + 1} / ${files.size}", Ty.caption, FontWeight.SemiBold, Color.White)
                    }
                }
                Box(Modifier.align(Alignment.TopEnd)) { closeBtn(true) }
            }
        }
    }
}

@Composable
private fun PhotoView(ui: Ui, f: File, desc: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, f) { value = ui.photos.bitmap(f, 1080) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), desc, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize())
}
