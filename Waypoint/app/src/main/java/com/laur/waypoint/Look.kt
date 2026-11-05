package com.laur.waypoint

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Atlas & Route" palette. Every colour has one meaning:
 * accent = the country (from its flag) and interactivity, visited = visited/done, caution = tips and warnings.
 * Waypoint's own screens use Waypoint blue; inside a city the accent comes from that country's flag.
 */
data class Pal(
    val bg: Color, val surface: Color, val surface2: Color, val fg: Color, val muted: Color, val line: Color,
    val accent: Color, val accentSoft: Color, val accentFg: Color,
    val visited: Color, val visitedSoft: Color, val caution: Color, val cautionSoft: Color,
    val scrim: Color, val dark: Boolean,
    /** Accent for actions on the inverse (snackbar) surface. */
    val inverseAccent: Color = Color.Unspecified,
) {
    // Older names, kept so every screen reads the same tokens.
    val food get() = visited
    val foodSoft get() = visitedSoft
    val warn get() = caution
}

val LightPal = Pal(
    bg = Color(0xFFF1F3F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE7EAEF), fg = Color(0xFF15181D),
    muted = Color(0xFF5A6270), line = Color(0xFFDCE0E6),
    accent = Color(0xFF1E56B0), accentSoft = Color(0xFFE3EBF8), accentFg = Color(0xFFFFFFFF),
    visited = Color(0xFF1D7A4E), visitedSoft = Color(0xFFE2F3E9), caution = Color(0xFF985A00), cautionSoft = Color(0xFFFCEFD9),
    scrim = Color(0x8C000000), dark = false, inverseAccent = Color(0xFF8DB4FF),
)
val DarkPal = Pal(
    bg = Color(0xFF0F1114), surface = Color(0xFF1A1D22), surface2 = Color(0xFF23272D), fg = Color(0xFFECEEF1),
    muted = Color(0xFFA1A8B2), line = Color(0xFF2C3138),
    accent = Color(0xFF8DB4FF), accentSoft = Color(0xFF1C2A42), accentFg = Color(0xFF0A1526),
    visited = Color(0xFF5CC48F), visitedSoft = Color(0xFF15301F), caution = Color(0xFFF0B35A), cautionSoft = Color(0xFF33260F),
    scrim = Color(0xA6000000), dark = true, inverseAccent = Color(0xFF1E56B0),
)

val LocalPal = staticCompositionLocalOf { LightPal }

/** Spacing (4 dp grid) and corner radii. */
object Sp {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
    val gutter = 16.dp
}
object Rad {
    val s = 8.dp   // tags, small chips
    val m = 16.dp  // cards, grouped lists, fields
    val l = 28.dp  // sheets, hero
}

/** Type scale: three display steps (Barlow Semi Condensed) and five text steps (system Roboto). */
object Ty {
    val display = 34.sp; val title = 24.sp; val headline = 20.sp
    val bodyL = 17.sp; val body = 16.sp; val bodyS = 14.sp; val label = 13.sp; val caption = 12.sp
}

object Fonts {
    var display: FontFamily = FontFamily.SansSerif; private set
    var body: FontFamily = FontFamily.Default; private set

    // The display font files are fetched when the app is built. If they are missing, the system font is used.
    @SuppressLint("DiscouragedApi")
    fun init(ctx: Context) {
        fun id(n: String) = ctx.resources.getIdentifier(n, "font", ctx.packageName)
        val m = id("barlowsc_500"); val b = id("barlowsc_600")
        if (m != 0 && b != 0) display = FontFamily(Font(m, FontWeight.Medium), Font(b, FontWeight.SemiBold))
    }
}

// ---------- colour maths ----------

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

fun mixColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
)

/** Keeps the flag's hue, darkening it until white text on it and it on the page both reach 4.5:1. */
fun accentLight(flag: Color): Color {
    var c = flag
    var i = 0
    while ((contrast(Color.White, c) < 4.6f || contrast(c, LightPal.bg) < 4.5f) && i < 40) { i++; c = mixColor(flag, Color.Black, i * 0.025f) }
    return c
}

/** The flag's hue lightened for dark mode, until it reaches 4.5:1 on dark cards and dark text on it does too. */
fun accentDark(flag: Color): Color {
    var t = 0.45f
    var c = mixColor(flag, Color.White, t)
    while ((contrast(c, DarkPal.surface) < 4.6f || contrast(DarkPal.accentFg, c) < 4.6f) && t < 0.95f) { t += 0.03f; c = mixColor(flag, Color.White, t) }
    return c
}

/** The flag colour the accent is made from: the country's own colour (so older guides follow it too), else the guide's. */
fun flagAccent(city: City): Color =
    Europe.forCity(city)?.accent?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(city.accent.light)

fun palFor(city: City?, dark: Boolean): Pal {
    val base = if (dark) DarkPal else LightPal
    city ?: return base
    val flag = flagAccent(city)
    return if (dark) {
        val a = accentDark(flag)
        base.copy(accent = a, accentSoft = mixColor(flag, DarkPal.bg, 0.74f), accentFg = DarkPal.accentFg, inverseAccent = accentLight(flag))
    } else {
        val a = accentLight(flag)
        base.copy(accent = a, accentSoft = mixColor(a, Color.White, 0.88f), accentFg = Color.White, inverseAccent = accentDark(flag))
    }
}

/** The accent for one city in the current mode (for lists that show many cities). */
fun cityAccent(city: City, dark: Boolean): Color = flagAccent(city).let { if (dark) accentDark(it) else accentLight(it) }

/** Material components (dialogs, menus, sheets, date picker, bars) drawn in Waypoint's palette, not Material purple. */
fun schemeFor(p: Pal): ColorScheme {
    val elevated = if (p.dark) p.surface2 else p.surface
    return if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = p.accentFg, primaryContainer = p.accentSoft, onPrimaryContainer = p.fg,
        secondary = p.accent, onSecondary = p.accentFg, secondaryContainer = p.accentSoft, onSecondaryContainer = p.fg,
        tertiary = p.visited, background = p.bg, onBackground = p.fg, surface = p.surface, onSurface = p.fg,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted, outline = p.muted, outlineVariant = p.line,
        surfaceTint = Color.Transparent, error = p.caution, scrim = p.scrim,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = elevated, surfaceContainerHighest = p.surface2, inverseSurface = p.fg, inverseOnSurface = p.bg,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.accentFg, primaryContainer = p.accentSoft, onPrimaryContainer = p.fg,
        secondary = p.accent, onSecondary = p.accentFg, secondaryContainer = p.accentSoft, onSecondaryContainer = p.fg,
        tertiary = p.visited, background = p.bg, onBackground = p.fg, surface = p.surface, onSurface = p.fg,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted, outline = p.muted, outlineVariant = p.line,
        surfaceTint = Color.Transparent, error = p.caution, scrim = p.scrim,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = elevated, surfaceContainerHighest = p.surface2, inverseSurface = p.fg, inverseOnSurface = p.bg,
    )
}
