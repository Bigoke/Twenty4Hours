package com.bigoke.twenty4hours.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val OnAmber = Color(0xFF1A0E02)

@Composable
fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(
        t.uppercase(), color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp,
        modifier = modifier.padding(bottom = 10.dp)
    )
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .then(
                if (primary) Modifier.background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                else Modifier.background(T24.SurfaceHigh).border(1.dp, T24.Line, shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> T24.Faint
                primary -> OnAmber
                else -> T24.Cream
            },
            fontSize = 16.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Round icon-ish button holding any content (usually a [GlyphIcon]). */
@Composable
fun RoundButton(
    size: Dp,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (primary) Modifier.background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                else Modifier.background(T24.SurfaceHigh).border(1.dp, T24.Line, CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) T24.Amber else T24.SurfaceHigh, label = "chip")
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (selected) OnAmber else T24.Text, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun StepButton(t: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(T24.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, color = T24.Cream, fontSize = 22.sp) }
}

@Composable
fun T24Switch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier.scale(0.85f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = T24.CreamLight,
            checkedTrackColor = T24.Amber,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = T24.Muted,
            uncheckedTrackColor = T24.SurfaceHigh,
            uncheckedBorderColor = T24.Line,
        )
    )
}

/** A settings-style row: title, subtitle, and something on the right. */
@Composable
fun OptionRow(
    title: String,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = T24.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = T24.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Small row of selectable values, e.g. 5 · 10 · 15 min. */
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { o -> Chip(label(o), o == selected) { onPick(o) } }
    }
}

// --------------------------------------------------------------- glyph icons

enum class Glyph { ALARM, TIMER, STOPWATCH, WORLD, PLAY, PAUSE, RESET, FLAG, PLUS, GEAR }

/** Hand-drawn line icons that match the dial (core Material icons lack clock glyphs). */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val sw = s * 0.09f
        when (glyph) {
            Glyph.ALARM -> {
                drawCircle(color, radius = s * 0.36f, center = c, style = Stroke(sw))
                drawRoundRect(
                    color, topLeft = Offset(c.x + s * 0.12f, c.y - s * 0.46f), size = Size(s * 0.26f, s * 0.2f),
                    cornerRadius = CornerRadius(s * 0.1f)
                )
                drawCircle(color, radius = s * 0.07f, center = Offset(c.x - s * 0.25f, c.y + s * 0.25f))
            }
            Glyph.TIMER -> {
                drawCircle(color.copy(alpha = 0.35f), radius = s * 0.36f, center = c, style = Stroke(sw))
                drawArc(
                    color, -90f, 250f, false, Offset(c.x - s * 0.36f, c.y - s * 0.36f), Size(s * 0.72f, s * 0.72f),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
                drawCircle(color, radius = s * 0.08f, center = c)
            }
            Glyph.STOPWATCH -> {
                drawCircle(color, radius = s * 0.33f, center = Offset(c.x, c.y + s * 0.06f), style = Stroke(sw))
                drawLine(color, Offset(c.x, c.y - s * 0.27f), Offset(c.x, c.y - s * 0.42f), sw, StrokeCap.Round)
                drawLine(color, Offset(c.x - s * 0.1f, c.y - s * 0.44f), Offset(c.x + s * 0.1f, c.y - s * 0.44f), sw, StrokeCap.Round)
                drawLine(color, Offset(c.x, c.y + s * 0.06f), Offset(c.x + s * 0.13f, c.y - s * 0.08f), sw, StrokeCap.Round)
            }
            Glyph.WORLD -> {
                drawCircle(color, radius = s * 0.38f, center = c, style = Stroke(sw))
                drawOval(
                    color, topLeft = Offset(c.x - s * 0.16f, c.y - s * 0.38f), size = Size(s * 0.32f, s * 0.76f),
                    style = Stroke(sw * 0.8f)
                )
                drawLine(color, Offset(c.x - s * 0.38f, c.y), Offset(c.x + s * 0.38f, c.y), sw * 0.8f)
            }
            Glyph.PLAY -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - s * 0.18f, c.y - s * 0.26f)
                    lineTo(c.x + s * 0.28f, c.y)
                    lineTo(c.x - s * 0.18f, c.y + s * 0.26f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.PAUSE -> {
                drawRoundRect(color, Offset(c.x - s * 0.22f, c.y - s * 0.26f), Size(s * 0.14f, s * 0.52f), CornerRadius(s * 0.04f))
                drawRoundRect(color, Offset(c.x + s * 0.08f, c.y - s * 0.26f), Size(s * 0.14f, s * 0.52f), CornerRadius(s * 0.04f))
            }
            Glyph.RESET -> {
                drawArc(
                    color, -60f, 290f, false, Offset(c.x - s * 0.3f, c.y - s * 0.3f), Size(s * 0.6f, s * 0.6f),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
                val tip = Offset(c.x + s * 0.15f, c.y - s * 0.26f)
                drawLine(color, tip, Offset(tip.x - s * 0.04f, tip.y - s * 0.15f), sw, StrokeCap.Round)
                drawLine(color, tip, Offset(tip.x - s * 0.17f, tip.y + s * 0.02f), sw, StrokeCap.Round)
            }
            Glyph.FLAG -> {
                drawLine(color, Offset(c.x - s * 0.22f, c.y - s * 0.34f), Offset(c.x - s * 0.22f, c.y + s * 0.36f), sw, StrokeCap.Round)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - s * 0.22f, c.y - s * 0.34f)
                    lineTo(c.x + s * 0.3f, c.y - s * 0.2f)
                    lineTo(c.x - s * 0.22f, c.y - s * 0.02f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.PLUS -> {
                drawLine(color, Offset(c.x - s * 0.28f, c.y), Offset(c.x + s * 0.28f, c.y), sw * 1.2f, StrokeCap.Round)
                drawLine(color, Offset(c.x, c.y - s * 0.28f), Offset(c.x, c.y + s * 0.28f), sw * 1.2f, StrokeCap.Round)
            }
            Glyph.GEAR -> {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val o = Offset(c.x + (s * 0.36f) * kotlin.math.cos(a).toFloat(), c.y + (s * 0.36f) * kotlin.math.sin(a).toFloat())
                    drawCircle(color, radius = s * 0.07f, center = o)
                }
                drawCircle(color, radius = s * 0.28f, center = c, style = Stroke(sw * 1.1f))
                drawCircle(color, radius = s * 0.09f, center = c)
            }
        }
    }
}

// --------------------------------------------------------------- dial helpers

/** Shared look for every round face in the app: outer line, dark track, inner disc. */
fun DrawScope.drawFace(c: Offset, r: Float, trackW: Float, ticks: Int = 24, majorEvery: Int = 3, outerLine: Boolean = true) {
    val ro = r + trackW * 0.95f
    if (outerLine) {
        drawCircle(T24.Line, radius = ro, center = c, style = Stroke(1.dp.toPx()))
        for (i in 0 until ticks) {
            val major = i % majorEvery == 0
            val a = Math.toRadians(i * 360.0 / ticks)
            drawCircle(
                if (major) T24.Muted else T24.Faint,
                radius = if (major) 1.7.dp.toPx() else 1.dp.toPx(),
                center = Offset(c.x + ro * kotlin.math.sin(a).toFloat(), c.y - ro * kotlin.math.cos(a).toFloat())
            )
        }
    }
    drawCircle(T24.Track, radius = r, center = c, style = Stroke(trackW))
    drawCircle(T24.TrackEdge, radius = r + trackW / 2f, center = c, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.55f), radius = r - trackW / 2f, center = c, style = Stroke(2.dp.toPx()))
    val inner = r - trackW / 2f - 10.dp.toPx()
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF221E1A), Color(0xFF110F0E)),
            center = c - Offset(0f, inner * 0.35f), radius = inner * 1.35f
        ),
        radius = inner, center = c
    )
    drawCircle(Color(0xFF2B2825), radius = inner, center = c, style = Stroke(1.dp.toPx()))
}

/** Glowing amber arc on a face. Angles in degrees, 0 = 12 o'clock, clockwise. */
fun DrawScope.drawGlowArc(c: Offset, radius: Float, fromDeg: Float, sweepDeg: Float, core: Dp = 3.5.dp) {
    if (sweepDeg <= 0.05f) return
    val start = fromDeg - 90f
    val tl = Offset(c.x - radius, c.y - radius)
    val sz = Size(radius * 2, radius * 2)
    listOf(18.dp to 0.06f, 11.dp to 0.10f, 6.dp to 0.18f).forEach { (w, a) ->
        drawArc(T24.Amber.copy(alpha = a), start, sweepDeg, false, tl, sz, style = Stroke(w.toPx(), cap = StrokeCap.Round))
    }
    androidx.compose.ui.graphics.drawscope.rotate(start, pivot = c) {
        val end = (sweepDeg / 360f).coerceIn(0.001f, 1f)
        drawArc(
            brush = Brush.sweepGradient(0f to T24.AmberDark.copy(alpha = 0.6f), end to T24.AmberLight, center = c),
            startAngle = 0f, sweepAngle = sweepDeg, useCenter = false, topLeft = tl, size = sz,
            style = Stroke(core.toPx(), cap = StrokeCap.Round)
        )
    }
}

/** The soft 3D knob used for "now", timer ends and the stopwatch hand. */
fun DrawScope.drawKnob(p: Offset, radius: Float, amber: Boolean) {
    if (amber) {
        drawCircle(
            Brush.radialGradient(listOf(T24.Amber.copy(alpha = 0.55f), Color.Transparent), center = p, radius = radius * 2.6f),
            radius = radius * 2.6f, center = p
        )
    }
    drawCircle(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), center = p + Offset(0f, 3.dp.toPx()), radius = radius * 1.6f),
        radius = radius * 1.6f, center = p + Offset(0f, 3.dp.toPx())
    )
    val (hi, lo) = if (amber) T24.AmberLight to T24.AmberDark else T24.CreamLight to T24.CreamDark
    drawCircle(
        Brush.radialGradient(listOf(hi, lo), center = p - Offset(radius * 0.3f, radius * 0.4f), radius = radius * 1.4f),
        radius = radius, center = p
    )
    drawCircle(Color.White.copy(alpha = 0.3f), radius = radius * 0.28f, center = p - Offset(radius * 0.3f, radius * 0.35f))
}

fun pointOn(c: Offset, radius: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + radius * kotlin.math.sin(a).toFloat(), c.y - radius * kotlin.math.cos(a).toFloat())
}

/** Clock-face angle (0 = top, clockwise, 0..360) of point p around c. */
fun angleOf(c: Offset, p: Offset): Float {
    var d = Math.toDegrees(kotlin.math.atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble()))
    if (d < 0) d += 360.0
    return d.toFloat()
}

/** Remember-able spring for smooth number motion. */
@Composable
fun animatedFloat(target: Float): Float {
    val v by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = 120f), label = "af")
    return v
}

@Composable
fun rememberNoRipple() = remember { MutableInteractionSource() }
