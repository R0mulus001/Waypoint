package com.laur.waypoint

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate

// ---------- text ----------

@Composable
fun T(
    text: String, size: TextUnit = Ty.body, weight: FontWeight = FontWeight.Normal, color: Color = LocalPal.current.fg,
    family: FontFamily = Fonts.body, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, italic: Boolean = false, underline: Boolean = false,
) {
    // Display sizes sit tighter; reading sizes get room to breathe.
    val lh = if (size.value >= 20f) size * 1.2f else size * 1.4f
    Text(
        text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontSize = size, fontWeight = weight, color = color, fontFamily = family, lineHeight = lh,
            textAlign = align ?: TextAlign.Unspecified, letterSpacing = letterSpacing,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (underline) TextDecoration.Underline else null,
        ),
    )
}

/** Section label, announced as a heading. */
@Composable
fun Label(text: String, color: Color = LocalPal.current.muted, modifier: Modifier = Modifier) =
    T(text, Ty.bodyS, FontWeight.SemiBold, color, modifier = modifier.semantics { heading() })

/** Display-face section title with an optional line under it. */
@Composable
fun SectionHead(title: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        T(title, Ty.headline, FontWeight.SemiBold, family = Fonts.display, modifier = Modifier.semantics { heading() })
        if (!sub.isNullOrBlank()) T(sub, Ty.bodyS, color = LocalPal.current.muted)
    }
}

// ---------- icons (one set: 24 dp grid, 2 dp round stroke) ----------

fun icon(vararg paths: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    paths.forEach {
        addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

fun iconFilled(vararg paths: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) }
}.build()

private const val STAR = "M12 2.8l2.8 5.8 6.3.9-4.6 4.4 1.1 6.3L12 17.2l-5.6 3 1.1-6.3-4.6-4.4 6.3-.9z"

val PinIcon = icon("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", "M14.5 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0")
val ListIcon = icon("M9 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0", "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15", "M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0")
val VisitedIcon = icon("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M8 12l3 3 5-6")
val BackIcon = icon("M19 12H5", "M12 19l-7-7 7-7")
val CloseIcon = icon("M18 6L6 18", "M6 6l12 12")
val ChevronIcon = icon("M9 6l6 6-6 6")
val DownIcon = icon("M6 9l6 6 6-6")
val CheckIcon = icon("M5 12.5l4.5 4.5L19 7")
val PlusIcon = icon("M12 5v14", "M5 12h14")
val MoreIcon = icon("M12 5h.01", "M12 12h.01", "M12 19h.01")
val GoIcon = icon("M3 11l19-9-9 19-2-8-8-2z")
val CalendarIcon = icon("M8 2v4", "M16 2v4", "M3 10h18", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")
val StarIcon = icon(STAR)
val StarFilled = iconFilled(STAR)
val FoodIcon = icon("M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2", "M7 2v20", "M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3", "M21 15v7")
val CoffeeIcon = icon("M17 8h1a4 4 0 1 1 0 8h-1", "M3 8h14v9a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4z", "M6 2v2", "M10 2v2", "M14 2v2")
val DrinkIcon = icon("M8 22h8", "M12 15v7", "M12 15a5 5 0 0 0 5-5c0-2-.5-4-2-8H9c-1.5 4-2 6-2 8a5 5 0 0 0 5 5z")
val MuseumIcon = icon("M3 22h18", "M6 18v-7", "M10 18v-7", "M14 18v-7", "M18 18v-7", "M12 2l8 5H4z")
val ParkIcon = icon("M12 22v-6", "M8 16h8a4 4 0 0 0 1-7.9A5 5 0 0 0 7 8.1A4 4 0 0 0 8 16z")
val SightIcon = icon("M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z", "M15 13a3 3 0 1 1-6 0a3 3 0 1 1 6 0")
val DownloadIcon = icon("M12 3v12", "M7 10l5 5 5-5", "M5 21h14")
val LocateIcon = icon("M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M19 12a7 7 0 1 1-14 0a7 7 0 1 1 14 0")
val SearchIcon = icon("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0", "M21 21l-4.3-4.3")
val UpIcon = icon("M12 19V5", "M5 12l7-7 7 7")
val DownArrowIcon = icon("M12 5v14", "M19 12l-7 7-7-7")
val TipIcon = icon("M9 18h6", "M10 22h4", "M12 2a7 7 0 0 0-4 12.7V16h8v-1.3A7 7 0 0 0 12 2z")
val ExternalIcon = icon("M15 3h6v6", "M10 14L21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
val PhotoIcon = icon("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M10 9a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0", "M21 15l-5-5L5 21")
val NoteIcon = icon("M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
val ShuffleIcon = icon("M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")

/** Icon for a place category. */
fun catIcon(cat: String): ImageVector = when (cat) {
    "food" -> FoodIcon; "coffee" -> CoffeeIcon; "drinks" -> DrinkIcon
    "museum" -> MuseumIcon; "park" -> ParkIcon; else -> SightIcon
}

@Composable
fun Ic(ic: ImageVector, tint: Color = LocalPal.current.fg, size: Dp = 20.dp, desc: String? = null) =
    Icon(ic, contentDescription = desc, tint = tint, modifier = Modifier.size(size))

/** 48 dp icon button with a spoken label. */
@Composable
fun IconBtn(ic: ImageVector, desc: String, tint: Color = LocalPal.current.fg, onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(ic, contentDescription = desc, tint = tint, modifier = Modifier.size(24.dp)) }

// ---------- buttons, tags, stars ----------

enum class BtnKind { Primary, Tonal, Ghost, Food, Dark, OnAccent, OutlineOnAccent }

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Primary, small: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalPal.current
    val (bg, fg) = when (kind) {
        BtnKind.Primary -> c.accent to c.accentFg
        BtnKind.Tonal, BtnKind.Food -> c.accentSoft to c.fg
        BtnKind.Ghost -> c.surface to c.fg
        BtnKind.Dark -> c.fg to c.bg
        BtnKind.OnAccent -> c.accentFg to c.accent
        BtnKind.OutlineOnAccent -> Color.Transparent to c.accentFg
    }
    var m = modifier.heightIn(min = 48.dp).clip(CircleShape).background(bg)
    if (kind == BtnKind.Ghost) m = m.border(1.dp, c.line, CircleShape)
    if (kind == BtnKind.OutlineOnAccent) m = m.border(1.dp, c.accentFg.copy(alpha = .5f), CircleShape)
    Row(m.clickable(role = Role.Button, onClick = onClick).padding(horizontal = if (small) 14.dp else 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, null, tint = if (kind == BtnKind.Tonal || kind == BtnKind.Food) c.accent.takeIf { !c.dark } ?: fg else fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        T(text, if (small) Ty.bodyS else 15.sp, FontWeight.SemiBold, fg, align = TextAlign.Center, maxLines = 2)
    }
}

/** Category tag: neutral, with an icon, so food and sights are told apart by more than colour. */
@Composable
fun Tag(text: String, food: Boolean = false, icon: ImageVector? = null) {
    val c = LocalPal.current
    Row(Modifier.clip(RoundedCornerShape(Rad.s)).background(c.surface2).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(icon, null, tint = c.muted, modifier = Modifier.size(14.dp))
        T(text, Ty.caption, FontWeight.Medium, c.muted)
    }
}

@Composable
fun FreeBadge() {
    val c = LocalPal.current
    Box(Modifier.clip(RoundedCornerShape(Rad.s)).border(1.dp, c.line, RoundedCornerShape(Rad.s)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        T("Free to visit", Ty.caption, FontWeight.Medium, c.fg)
    }
}

@Composable
fun Stars(n: Int, size: TextUnit = 13.sp) {
    if (n <= 0) return
    val c = LocalPal.current
    val d = (size.value + 1).dp
    Row(Modifier.clearAndSetSemantics { contentDescription = "$n of 5 stars" }, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        (1..5).forEach { i -> Icon(if (i <= n) StarFilled else StarIcon, null, tint = if (i <= n) c.fg else c.line, modifier = Modifier.size(d)) }
    }
}

// ---------- surfaces ----------

/** A content card: one surface, 16 dp corners, no border; ripple stays inside the corners. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = LocalPal.current
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.surface)
    if (onClick != null) m = m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    Box(m) { content() }
}

/** Shape of row [i] of [n] in an inset grouped list. */
fun groupShape(i: Int, n: Int): Shape {
    val top = if (i == 0) Rad.m else 0.dp
    val bottom = if (i == n - 1) Rad.m else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** One row of an inset grouped list, with a divider above every row but the first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(i: Int, n: Int, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, dividerInset: Dp = 16.dp, content: @Composable () -> Unit) {
    val c = LocalPal.current
    var m = Modifier.fillMaxWidth().clip(groupShape(i, n)).background(c.surface)
    if (onClick != null) m = m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    Column(m) {
        if (i > 0) HorizontalDivider(Modifier.padding(start = dividerInset), thickness = 1.dp, color = c.line)
        content()
    }
}

// ---------- bars ----------

/** Top app bar: Android back arrow, title, and the overflow menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = { MoreMenu() }, title: @Composable () -> Unit) {
    val c = LocalPal.current
    TopAppBar(
        title = title,
        navigationIcon = { if (onBack != null) IconBtn(BackIcon, "Back", onClick = onBack) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, titleContentColor = c.fg, navigationIconContentColor = c.fg, actionIconContentColor = c.fg),
    )
}

/** Overflow menu: the screen's own actions, then Appearance (moved here from the header pill). */
@Composable
fun MoreMenu(items: List<Pair<String, () -> Unit>> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    val c = LocalPal.current
    Box {
        IconBtn(MoreIcon, "More options") { open = true }
        DropdownMenu(open, { open = false }) {
            items.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() }) }
            if (items.isNotEmpty()) HorizontalDivider(color = c.line)
            T("Appearance", Ty.label, FontWeight.SemiBold, c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            listOf("auto" to "System default", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { Hub.setThemeMode(k); open = false },
                    trailingIcon = { if (Hub.themeMode == k) Icon(CheckIcon, "Selected", tint = c.accent) })
            }
        }
    }
}

data class NavTab(val key: String, val label: String, val icon: ImageVector)

/** Bottom navigation: Material's NavigationBar (tab role, selected state and insets come with it). */
@Composable
fun NavBar(tabs: List<NavTab>, selected: String, onSelect: (String) -> Unit) {
    val c = LocalPal.current
    Column {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = selected == t.key, onClick = { onSelect(t.key) },
                    icon = { Icon(t.icon, null) }, label = { Text(t.label, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent, selectedTextColor = c.fg, indicatorColor = c.accentSoft,
                        unselectedIconColor = c.muted, unselectedTextColor = c.muted,
                    ),
                )
            }
        }
    }
}

// ---------- feedback ----------

data class Toast(val msg: String, val undo: () -> Unit, val stamp: Long = System.nanoTime())

/** Snackbar with Undo. Stays as long as the phone's accessibility settings ask for (8 s by default). */
@Composable
fun Snack(t: Toast?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    t ?: return
    val c = LocalPal.current
    val am = LocalAccessibilityManager.current
    val ms = am?.calculateRecommendedTimeoutMillis(8000L, containsIcons = false, containsText = true, containsControls = true) ?: 8000L
    LaunchedEffect(t.stamp) { delay(ms); onDone() }
    Row(modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(Rad.m)).background(c.fg).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T(t.msg, Ty.bodyS, color = c.bg, modifier = Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2)
        TextButton(onClick = { t.undo(); onDone() }) { Text("Undo", color = c.inverseAccent, fontWeight = FontWeight.SemiBold) }
    }
}

// ---------- dates ----------

/** Material date picker, drawn in the app's palette (the old platform dialog stayed light in dark mode). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDatePicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val day = 86_400_000L
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * day)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / day)) }; onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/** A small field-like control: icon, value, chevron. 48 dp tall. */
@Composable
fun PickerChip(text: String, icon: ImageVector?, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPal.current
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(Rad.m)).background(c.surface)
        .clickable(role = Role.Button, onClickLabel = desc, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
        T(text, Ty.body, FontWeight.Medium)
        Icon(DownIcon, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

/** Progress bar, 6 dp, in the accent colour. */
@Composable
fun Bar(fraction: Float, color: Color = LocalPal.current.accent, modifier: Modifier = Modifier) {
    val c = LocalPal.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
    }
}
