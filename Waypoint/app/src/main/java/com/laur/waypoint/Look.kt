package com.laur.waypoint

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Palette. Waypoint's own screens are blue; inside a city the accent comes from that city's flag. */
data class Pal(
    val bg: Color, val surface: Color, val fg: Color, val muted: Color, val line: Color,
    val accent: Color, val accentSoft: Color, val accentFg: Color,
    val food: Color, val foodSoft: Color, val warn: Color, val dark: Boolean,
)

val LightPal = Pal(
    bg = Color(0xFFF4F5F7), surface = Color(0xFFFFFFFF), fg = Color(0xFF1B1E23), muted = Color(0xFF646B75), line = Color(0xFFE0E3E8),
    accent = Color(0xFF1F5FBF), accentSoft = Color(0xFFE7EEFA), accentFg = Color(0xFFFFFFFF),
    food = Color(0xFF2C7A57), foodSoft = Color(0xFFE4F3EC), warn = Color(0xFFA86400), dark = false,
)
val DarkPal = Pal(
    bg = Color(0xFF131518), surface = Color(0xFF1D2025), fg = Color(0xFFEEF0F3), muted = Color(0xFF9EA5AE), line = Color(0xFF2E3238),
    accent = Color(0xFF6AA6FF), accentSoft = Color(0xFF1B2A40), accentFg = Color(0xFF0A1526),
    food = Color(0xFF5FC297), foodSoft = Color(0xFF16302A), warn = Color(0xFFF0AD4E), dark = true,
)

val LocalPal = staticCompositionLocalOf { LightPal }

object Fonts {
    var display: FontFamily = FontFamily.Serif; private set
    var body: FontFamily = FontFamily.SansSerif; private set

    // The font files are fetched when the app is built. If they are missing, system fonts are used.
    @SuppressLint("DiscouragedApi")
    fun init(ctx: Context) {
        fun id(n: String) = ctx.resources.getIdentifier(n, "font", ctx.packageName)
        val f = id("fraunces_600")
        if (f != 0) display = FontFamily(Font(f, FontWeight.SemiBold))
        val r = id("worksans_400"); val m = id("worksans_500"); val b = id("worksans_600")
        if (r != 0 && m != 0 && b != 0) body = FontFamily(Font(r, FontWeight.Normal), Font(m, FontWeight.Medium), Font(b, FontWeight.SemiBold))
    }
}

fun palFor(city: City?, dark: Boolean): Pal {
    val base = if (dark) DarkPal else LightPal
    val a = city?.accent ?: return base
    return if (dark) base.copy(accent = Color(a.dark), accentSoft = Color(a.darkSoft), accentFg = Color(a.darkFg))
    else base.copy(accent = Color(a.light), accentSoft = Color(a.lightSoft), accentFg = Color(0xFFFFFFFF))
}
