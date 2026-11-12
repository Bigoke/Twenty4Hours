package com.bigoke.twenty4hours.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bigoke.twenty4hours.data.Accent
import com.bigoke.twenty4hours.data.AppSettings
import androidx.compose.ui.graphics.Color

object T24 {
    /** Current accent; reading the colours below inside composition/drawing tracks it. */
    var accent by mutableStateOf(Accent.AMBER)

    val Bg = Color(0xFF0E0D0C)
    val Surface = Color(0xFF171513)
    val SurfaceHigh = Color(0xFF211F1C)
    val Track = Color(0xFF1C1A18)
    val TrackEdge = Color(0xFF2C2926)
    val Line = Color(0xFF3A3633)
    val Cream = Color(0xFFF2D9B0)
    val CreamLight = Color(0xFFFFF1D9)
    val CreamDark = Color(0xFFD9B98A)
    val Amber: Color get() = Color(accent.main)
    val AmberLight: Color get() = Color(accent.light)
    val AmberDark: Color get() = Color(accent.dark)
    val Text = Color(0xFFEDEAE6)
    val Muted = Color(0xFF8C8681)
    val Faint = Color(0xFF5A5652)
}

@Composable
fun Twenty4Theme(content: @Composable () -> Unit) {
    val settings by AppSettings.flow.collectAsState()
    if (T24.accent != settings.accent) T24.accent = settings.accent
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T24.Amber,
            onPrimary = Color(0xFF1A0E02),
            secondary = T24.Cream,
            onSecondary = Color(0xFF1A140C),
            background = T24.Bg,
            onBackground = T24.Text,
            surface = T24.Surface,
            onSurface = T24.Text,
            surfaceVariant = T24.SurfaceHigh,
            onSurfaceVariant = T24.Muted,
            surfaceContainerHigh = T24.SurfaceHigh,
            surfaceContainerLow = T24.Surface,
            outline = T24.Line,
        ),
        typography = typography(),
        content = content
    )
}

/** Line heights follow each text's own size, so large system fonts don't stretch rows apart. */
private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.loose() = copy(lineHeight = TextUnit.Unspecified)
    return t.copy(
        bodyLarge = t.bodyLarge.loose(), bodyMedium = t.bodyMedium.loose(), bodySmall = t.bodySmall.loose(),
        labelLarge = t.labelLarge.loose(), labelMedium = t.labelMedium.loose(), labelSmall = t.labelSmall.loose(),
        titleLarge = t.titleLarge.loose(), titleMedium = t.titleMedium.loose(), titleSmall = t.titleSmall.loose(),
    )
}
