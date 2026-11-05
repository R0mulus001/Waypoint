package com.laur.waypoint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Flags drawn as vectors, so they are crisp, work offline and look the same on every phone.
// Emblems are left out on purpose: on a city tile the location pin sits exactly where they would be.

private fun c(hex: Long) = Color(0xFF000000 or hex)

private fun DrawScope.stripes(colors: List<Long>, vertical: Boolean = false, weights: List<Float>? = null) {
    val w = weights ?: List(colors.size) { 1f }
    val total = w.sum()
    var at = 0f
    colors.forEachIndexed { i, col ->
        val part = w[i] / total
        if (vertical) drawRect(c(col), Offset(size.width * at, 0f), Size(size.width * part + 1f, size.height))
        else drawRect(c(col), Offset(0f, size.height * at), Size(size.width, size.height * part + 1f))
        at += part
    }
}

/** Nordic cross, set towards the hoist. */
private fun DrawScope.nordic(bg: Long, cross: Long, inner: Long? = null) {
    drawRect(c(bg))
    val cx = size.width * 0.38f; val cy = size.height / 2f
    val t = size.height * 0.24f
    drawRect(c(cross), Offset(cx - t / 2, 0f), Size(t, size.height))
    drawRect(c(cross), Offset(0f, cy - t / 2), Size(size.width, t))
    if (inner != null) {
        val ti = t * 0.5f
        drawRect(c(inner), Offset(cx - ti / 2, 0f), Size(ti, size.height))
        drawRect(c(inner), Offset(0f, cy - ti / 2), Size(size.width, ti))
    }
}

private fun DrawScope.poly(col: Long, vararg pts: Pair<Float, Float>) {
    val p = Path()
    pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(size.width * x, size.height * y) else p.lineTo(size.width * x, size.height * y) }
    p.close()
    drawPath(p, c(col))
}

private fun DrawScope.unionJack() {
    drawRect(c(0x012169))
    val w = size.width; val h = size.height
    val diag = h * 0.2f
    drawLine(Color.White, Offset(0f, 0f), Offset(w, h), diag)
    drawLine(Color.White, Offset(w, 0f), Offset(0f, h), diag)
    drawLine(c(0xC8102E), Offset(0f, 0f), Offset(w, h), diag / 3)
    drawLine(c(0xC8102E), Offset(w, 0f), Offset(0f, h), diag / 3)
    val t = h * 0.33f
    drawRect(Color.White, Offset(w / 2 - t / 2, 0f), Size(t, h))
    drawRect(Color.White, Offset(0f, h / 2 - t / 2), Size(w, t))
    val r = h * 0.2f
    drawRect(c(0xC8102E), Offset(w / 2 - r / 2, 0f), Size(r, h))
    drawRect(c(0xC8102E), Offset(0f, h / 2 - r / 2), Size(w, r))
}

private fun DrawScope.greece() {
    stripes(List(9) { if (it % 2 == 0) 0x0D5EAFL else 0xFFFFFFL })
    val s = size.height * 5f / 9f
    drawRect(c(0x0D5EAF), Offset.Zero, Size(s, s))
    val t = size.height / 9f
    drawRect(Color.White, Offset(s / 2 - t / 2, 0f), Size(t, s))
    drawRect(Color.White, Offset(0f, s / 2 - t / 2), Size(s, t))
}

private fun DrawScope.swissCross(bg: Long) {
    drawRect(c(bg))
    val m = minOf(size.width, size.height)
    val arm = m * 0.6f; val t = m * 0.2f
    val cx = size.width / 2; val cy = size.height / 2
    drawRect(Color.White, Offset(cx - t / 2, cy - arm / 2), Size(t, arm))
    drawRect(Color.White, Offset(cx - arm / 2, cy - t / 2), Size(arm, t))
}

private fun DrawScope.turkey() {
    drawRect(c(0xE30A17))
    val r = size.height * 0.25f
    val o = Offset(size.width * 0.36f, size.height / 2)
    drawCircle(Color.White, r, o)
    drawCircle(c(0xE30A17), r * 0.8f, Offset(o.x + r * 0.25f, o.y))
    drawCircle(Color.White, r * 0.28f, Offset(o.x + r * 1.15f, o.y))
}

private fun DrawScope.macedonia() {
    drawRect(c(0xCE2028))
    val cx = size.width / 2; val cy = size.height / 2
    val w = size.width; val h = size.height
    val rays = listOf(Offset(0f, 0f), Offset(w / 2, 0f), Offset(w, 0f), Offset(w, h / 2), Offset(w, h), Offset(w / 2, h), Offset(0f, h), Offset(0f, h / 2))
    rays.forEach { e ->
        val dx = e.x - cx; val dy = e.y - cy
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val nx = -dy / len * h * 0.07f; val ny = dx / len * h * 0.07f
        val p = Path().apply { moveTo(cx, cy); lineTo(e.x + nx, e.y + ny); lineTo(e.x - nx, e.y - ny); close() }
        drawPath(p, c(0xF8E92E))
    }
    drawCircle(c(0xCE2028), h * 0.17f, Offset(cx, cy))
    drawCircle(c(0xF8E92E), h * 0.13f, Offset(cx, cy))
}

/** Draws the flag of [code] over the whole draw area. Unknown codes get a neutral field in [fallback]. */
fun DrawScope.drawFlag(code: String, fallback: Color) {
    when (code) {
        "AL" -> drawRect(c(0xE41E20))
        "AD" -> stripes(listOf(0x10069F, 0xFEDF00, 0xD50032), vertical = true)
        "AT" -> stripes(listOf(0xC8102E, 0xFFFFFF, 0xC8102E))
        "BY" -> { stripes(listOf(0xC8313E, 0x4AA657), weights = listOf(2f, 1f)); drawRect(Color.White, Offset.Zero, Size(size.width * 0.11f, size.height)) }
        "BE" -> stripes(listOf(0x000000, 0xFDDA24, 0xEF3340), vertical = true)
        "BA" -> { drawRect(c(0x002395)); poly(0xFECB00, 0.28f to 0f, 0.78f to 0f, 0.78f to 1f) }
        "BG" -> stripes(listOf(0xFFFFFF, 0x00966E, 0xD62612))
        "HR" -> stripes(listOf(0xFF0000, 0xFFFFFF, 0x171796))
        "CY" -> { drawRect(Color.White); drawOval(c(0xD57800), Offset(size.width * 0.25f, size.height * 0.3f), Size(size.width * 0.5f, size.height * 0.26f)) }
        "CZ" -> { stripes(listOf(0xFFFFFF, 0xD7141A)); poly(0x11457E, 0f to 0f, 0.5f to 0.5f, 0f to 1f) }
        "DK" -> nordic(0xC8102E, 0xFFFFFF)
        "EE" -> stripes(listOf(0x0072CE, 0x000000, 0xFFFFFF))
        "FI" -> nordic(0xFFFFFF, 0x002F6C)
        "FR" -> stripes(listOf(0x002654, 0xFFFFFF, 0xCE1126), vertical = true)
        "DE" -> stripes(listOf(0x000000, 0xDD0000, 0xFFCE00))
        "GR" -> greece()
        "HU" -> stripes(listOf(0xCE2939, 0xFFFFFF, 0x477050))
        "IS" -> nordic(0x02529C, 0xFFFFFF, 0xDC1E35)
        "IE" -> stripes(listOf(0x169B62, 0xFFFFFF, 0xFF883E), vertical = true)
        "IT" -> stripes(listOf(0x009246, 0xFFFFFF, 0xCE2B37), vertical = true)
        "XK" -> { drawRect(c(0x244AA5)); drawOval(c(0xD0A650), Offset(size.width * 0.3f, size.height * 0.35f), Size(size.width * 0.4f, size.height * 0.3f)) }
        "LV" -> stripes(listOf(0x9E3039, 0xFFFFFF, 0x9E3039), weights = listOf(2f, 1f, 2f))
        "LI" -> { stripes(listOf(0x002B7F, 0xCE1126)); drawCircle(c(0xFFD83D), size.height * 0.1f, Offset(size.width * 0.26f, size.height * 0.25f)) }
        "LT" -> stripes(listOf(0xFDB913, 0x006A44, 0xC1272D))
        "LU" -> stripes(listOf(0xEF3340, 0xFFFFFF, 0x00A3E0))
        "MT" -> { stripes(listOf(0xFFFFFF, 0xCF142B), vertical = true); drawRect(c(0xA0A0A0), Offset(size.width * 0.1f, size.height * 0.1f), Size(size.width * 0.12f, size.height * 0.12f)) }
        "MD" -> stripes(listOf(0x0046AE, 0xFFD200, 0xCC092F), vertical = true)
        "MC" -> stripes(listOf(0xCE1126, 0xFFFFFF))
        "ME" -> { drawRect(c(0xD3AE3B)); val b = size.height * 0.07f; drawRect(c(0xC40308), Offset(b, b), Size(size.width - 2 * b, size.height - 2 * b)) }
        "NL" -> stripes(listOf(0xAE1C28, 0xFFFFFF, 0x21468B))
        "MK" -> macedonia()
        "NO" -> nordic(0xBA0C2F, 0xFFFFFF, 0x00205B)
        "PL" -> stripes(listOf(0xFFFFFF, 0xDC143C))
        "PT" -> { stripes(listOf(0x046A38, 0xDA291C), vertical = true, weights = listOf(2f, 3f)); drawCircle(c(0xFFE900), size.height * 0.2f, Offset(size.width * 0.4f, size.height / 2)) }
        "RO" -> stripes(listOf(0x002B7F, 0xFCD116, 0xCE1126), vertical = true)
        "RU" -> stripes(listOf(0xFFFFFF, 0x0039A6, 0xD52B1E))
        "SM" -> stripes(listOf(0xFFFFFF, 0x5EB6E4))
        "RS" -> stripes(listOf(0xC6363C, 0x0C4076, 0xFFFFFF))
        "SK" -> stripes(listOf(0xFFFFFF, 0x0B4EA2, 0xEE1C25))
        "SI" -> stripes(listOf(0xFFFFFF, 0x005CE5, 0xED1C24))
        "ES" -> stripes(listOf(0xAA151B, 0xF1BF00, 0xAA151B), weights = listOf(1f, 2f, 1f))
        "SE" -> nordic(0x006AA7, 0xFECC00)
        "CH" -> swissCross(0xD52B1E)
        "TR" -> turkey()
        "UA" -> stripes(listOf(0x0057B7, 0xFFD700))
        "GB" -> unionJack()
        "VA" -> stripes(listOf(0xFFE000, 0xFFFFFF), vertical = true)
        else -> drawRect(fallback)
    }
}

/** The location pin from the app icon: white, outlined so it reads on white stripes, with a soft shadow. */
private fun DrawScope.pin() {
    val s = size.minDimension / 108f * 1.55f // the icon's 108-unit pin, scaled to the tile
    val pinPath = Path().apply {
        moveTo(54f, 30f)
        cubicTo(44.6f, 30f, 37f, 37.6f, 37f, 47f)
        cubicTo(37f, 59.8f, 54f, 78f, 54f, 78f)
        cubicTo(54f, 78f, 71f, 59.8f, 71f, 47f)
        cubicTo(71f, 37.6f, 63.4f, 30f, 54f, 30f)
        close()
    }
    withTransform({
        translate(size.width / 2 - 54f * s, size.height / 2 - 54f * s)
        scale(s, s, Offset.Zero)
    }) {
        translate(1.5f, 2.5f) { drawPath(pinPath, Color.Black.copy(alpha = 0.28f)) }
        drawPath(pinPath, Color.White)
        drawPath(pinPath, Color(0xFF1B1E23), style = Stroke(width = 2.5f))
        drawCircle(Color(0xFF1B1E23), 6.5f, Offset(54f, 47f))
    }
}

/**
 * The city tile: the country's flag as background, the location pin on top.
 * Used wherever a city appears (My trips, diary, city header).
 */
@Composable
fun FlagTile(code: String, size: Dp, label: String? = null, pin: Boolean = true, fallback: Color = LocalPal.current.accent, modifier: Modifier = Modifier) {
    val edge = LocalPal.current.fg.copy(alpha = 0.12f)
    val m = if (label != null) modifier.semantics { contentDescription = label } else modifier
    Canvas(m.size(size)) {
        val r = size.minDimension * 0.24f
        val rr = RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))
        val clip = Path().apply { addRoundRect(rr) }
        clipPath(clip) { drawFlag(code, fallback) }
        drawPath(clip, edge, style = Stroke(width = 1.dp.toPx()))
        if (pin) pin()
    }
}

/** A plain flag (no pin), for picking a country. 3:2, rounded corners. */
@Composable
fun FlagRect(code: String, width: Dp, modifier: Modifier = Modifier) {
    val edge = LocalPal.current.fg.copy(alpha = 0.12f)
    val fallback = LocalPal.current.surface2
    Canvas(modifier.size(width, width * 2 / 3)) {
        val r = 4.dp.toPx()
        val rr = RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))
        val clip = Path().apply { addRoundRect(rr) }
        clipPath(clip) { drawFlag(code, fallback) }
        drawPath(clip, edge, style = Stroke(width = 1.dp.toPx()))
    }
}

/** Country code for a city (built guides save it; older ones are matched by country name). */
fun codeOf(city: City): String = city.countryCode.ifBlank { Europe.forCity(city)?.code.orEmpty() }
