package com.bigoke.twenty4hours.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object T24 {
    val Bg = Color(0xFF0E0D0C)
    val Surface = Color(0xFF171513)
    val SurfaceHigh = Color(0xFF211F1C)
    val Track = Color(0xFF1C1A18)
    val TrackEdge = Color(0xFF2C2926)
    val Line = Color(0xFF3A3633)
    val Cream = Color(0xFFF2D9B0)
    val CreamLight = Color(0xFFFFF1D9)
    val CreamDark = Color(0xFFD9B98A)
    val Amber = Color(0xFFFF8A1F)
    val AmberLight = Color(0xFFFFB25E)
    val AmberDark = Color(0xFFE56A0A)
    val Text = Color(0xFFEDEAE6)
    val Muted = Color(0xFF8C8681)
    val Faint = Color(0xFF5A5652)
}

@Composable
fun Twenty4Theme(content: @Composable () -> Unit) {
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
        content = content
    )
}
