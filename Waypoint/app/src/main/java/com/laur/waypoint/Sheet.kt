package com.laur.waypoint

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PlaceSheet(ui: Ui, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val close = { ui.open = null }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val maxH = maxHeight * 0.92f
            Column(Modifier.fillMaxWidth().heightIn(max = maxH)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.bg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())) {
                SheetContent(ui, p, here, maxH)
            }
        }
    }
}

@Composable
private fun SheetContent(ui: Ui, p: Place, here: LatLng?, maxH: androidx.compose.ui.unit.Dp) {
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

    Gallery(ui, p, info?.photos ?: emptyList(), loading && info?.photos.isNullOrEmpty(), maxH * 0.52f, food)

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(Trip.label(cat), food)
            if (food && Trip.priceText(p).isNotEmpty()) T(Trip.priceText(p), 14.sp, FontWeight.Medium, c.muted)
            if (!food && Trip.isFree(p)) FreeBadge()
        }
        T(p.name, 25.sp, FontWeight.SemiBold, family = Fonts.display)
        distInfo(here, p)?.let { T(it.text, 15.sp, FontWeight.SemiBold, if (food) c.food else c.accent) }
        T(p.desc + if (p.min > 0) " Plan about ${dur(p.min)}." else "", 15.sp)

        if (!food) {
            val ex = info?.extract.orEmpty()
            if (ex.isNotBlank()) T(ex, 14.sp, color = c.muted)
            val page = info?.page.orEmpty()
            if (page.isNotBlank()) T("Read more on Wikipedia", 14.sp, FontWeight.SemiBold, c.accent, modifier = Modifier.clickable { ui.openUrl(page) })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Take me there", Modifier.weight(1f)) { ui.openUrl(mapsUrl(here, p)) }
                SeenBtn(ui, p, Modifier.weight(1f))
            }
        } else {
            FoodBlock(ui, p, here)
        }
        st.visited[p.id]?.let { T("You visited on " + visitedWhen(it) + ".", 13.5.sp, FontWeight.SemiBold, c.food) }
        NotesBox(ui, p)
        T(if (food) "Inside photos: Wikimedia Commons, up to 5. Saved on your phone after the first look."
          else "Photos and text: Wikipedia / Wikimedia Commons. Saved on your phone after the first look.", 11.5.sp, color = c.muted)
    }
}

@Composable
private fun SeenBtn(ui: Ui, p: Place, modifier: Modifier) {
    val c = LocalPal.current
    val seen = ui.st.isSeen(p.id)
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.heightIn(min = 46.dp).clip(shape).background(c.surface).border(1.dp, if (seen) c.food else c.line, shape)
        .clickable { ui.toggleSeen(p.id) }.padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        T(if (seen) "Visited ✓" else "Mark as visited", 15.sp, FontWeight.SemiBold, if (seen) c.food else c.fg)
    }
}

@Composable
private fun FoodBlock(ui: Ui, p: Place, here: LatLng?) {
    val c = LocalPal.current
    val cost = Trip.COST[p.id]
    var menuOpen by remember(p.id) { mutableStateOf(false) }
    if (cost != null) {
        Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.foodSoft).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("Estimated cost per person", c.food)
            T(Trip.money(cost.lo) + " – " + Trip.money(cost.hi), 27.sp, FontWeight.SemiBold, c.food, Fonts.display)
            T("For ${cost.basis}. An estimate, not the bill.", 13.5.sp, color = c.muted)
        }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val menuSearch = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(p.name + " " + Trip.CITY_EN + " menu", "UTF-8")
        if (cost?.menu.isNullOrEmpty()) Btn("Menu online", Modifier.weight(1f), BtnKind.Food) { ui.openUrl(menuSearch) }
        else Btn(if (menuOpen) "Hide menu" else "Menu", Modifier.weight(1f), if (menuOpen) BtnKind.Dark else BtnKind.Food) { menuOpen = !menuOpen }
        Btn("Take me there", Modifier.weight(1f), BtnKind.Ghost) { ui.openUrl(mapsUrl(here, p)) }
    }
    if (menuOpen) {
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Menu highlights")
            cost?.menu?.forEachIndexed { i, (dish, pr) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(dish, 15.sp, FontWeight.Medium, modifier = Modifier.weight(1f))
                    T("~" + Trip.money(pr), 15.sp, FontWeight.SemiBold)
                }
            }
            if (cost != null) T("Approximate prices. Menus and prices change.", 12.5.sp, color = c.muted)
            T("See the full menu online ›", 14.sp, FontWeight.SemiBold, c.accent, modifier = Modifier.clickable {
                ui.openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(p.name + " " + Trip.CITY_EN + " menu", "UTF-8"))
            })
        }
    }
    SeenBtn(ui, p, Modifier.fillMaxWidth())
}

@Composable
private fun NotesBox(ui: Ui, p: Place) {
    val c = LocalPal.current
    val st = ui.st
    var text by remember(p.id) { mutableStateOf(st.note(p.id).text) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Your notes")
        val stars = st.note(p.id).stars
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { n ->
                T(if (n <= stars) "★" else "☆", 28.sp, color = if (n <= stars) c.warn else c.muted,
                    modifier = Modifier.clickable { st.setNote(p.id, st.note(p.id).copy(stars = if (stars == n) 0 else n)) }.padding(2.dp))
            }
        }
        Box {
            if (text.isEmpty()) T("Add a note: what you ate, what to remember…", 15.sp, color = c.muted)
            BasicTextField(
                value = text,
                onValueChange = { text = it; st.setNote(p.id, st.note(p.id).copy(text = it)) },
                textStyle = TextStyle(fontSize = 15.sp, color = c.fg, fontFamily = Fonts.body, lineHeight = 21.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            )
        }
        T("Saved automatically on your phone.", 12.sp, color = c.muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Gallery(ui: Ui, p: Place, files: List<File>, loading: Boolean, maxH: androidx.compose.ui.unit.Dp, food: Boolean) {
    val c = LocalPal.current
    val closeBtn = @Composable { onPhoto: Boolean ->
        Box(Modifier.padding(12.dp).size(38.dp).clip(CircleShape)
            .background(if (onPhoto) Color.Black.copy(alpha = .55f) else c.surface)
            .then(if (onPhoto) Modifier else Modifier.border(1.dp, c.line, CircleShape))
            .clickable { ui.open = null }, contentAlignment = Alignment.Center) {
            T("×", 20.sp, color = if (onPhoto) Color.White else c.fg)
        }
    }
    when {
        loading -> {
            val t = rememberInfiniteTransition(label = "pulse")
            val a by t.animateFloat(1f, .6f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).heightIn(max = maxH)) {
                Box(Modifier.fillMaxSize().alpha(a).background(c.line))
                Box(Modifier.align(Alignment.TopEnd)) { closeBtn(true) }
            }
        }
        files.isEmpty() -> {
            Column {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(if (food) "No inside photos available" else "No preview available", 13.5.sp, color = c.muted, modifier = Modifier.weight(1f))
                    Box(Modifier.padding(end = 0.dp)) { closeBtn(false) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            }
        }
        else -> {
            val pager = rememberPagerState { files.size }
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).heightIn(max = maxH).background(c.line)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { files[it].path }) { i ->
                    PhotoView(ui, files[i], p.name + " photo " + (i + 1))
                }
                if (files.size > 1) {
                    Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        T("${pager.currentPage + 1} / ${files.size}", 12.sp, FontWeight.SemiBold, Color.White)
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
