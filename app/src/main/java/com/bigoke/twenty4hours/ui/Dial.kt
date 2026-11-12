package com.bigoke.twenty4hours.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.formatCountdown
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** All dial geometry in px, shared by drawing and touch handling. */
private class DialGeo(w: Float, h: Float, d: Density) {
    val c = Offset(w / 2f, h / 2f)
    val s = min(w, h)
    val r = s * 0.305f                     // track centre radius
    val trackW = s * 0.085f
    val ro = r + trackW * 0.95f            // thin outer line
    val rh = ro + with(d) { 12.dp.toPx() } // hour numbers
    val rl = rh + with(d) { 14.dp.toPx() } // alarm labels
    val puckL = trackW * 1.5f              // along the ring
    val puckW = trackW * 1.2f              // across the ring
    val innerR = r - trackW / 2f - with(d) { 10.dp.toPx() }

    fun deg(minute: Float) = minute / 1440f * 360f

    fun pointAt(minute: Float, radius: Float): Offset {
        val a = Math.toRadians(deg(minute).toDouble())
        return Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat())
    }

    fun minuteAt(p: Offset): Int {
        var d = Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble()))
        if (d < 0) d += 360.0
        return (d / 360.0 * 1440.0).roundToInt() % 1440
    }
}

private fun snap5(m: Int): Int = ((m / 5f).roundToInt() * 5) % 1440

@Composable
fun Dial(
    alarms: List<Alarm>,
    now: ZonedDateTime,
    next: Alarm?,
    nextAt: ZonedDateTime?,
    onMove: (Alarm, Int) -> Unit,
    onTap: (Alarm) -> Unit,
    onAddAt: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragMinute by remember { mutableIntStateOf(0) }

    val alarmsState = rememberUpdatedState(alarms)
    val onMoveState = rememberUpdatedState(onMove)
    val onTapState = rememberUpdatedState(onTap)
    val onAddState = rememberUpdatedState(onAddAt)

    val shown = alarms.map {
        if (it.id == dragId) it.copy(hour = dragMinute / 60, minute = dragMinute % 60) else it
    }
    val dragged = shown.firstOrNull { it.id == dragId }

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        val side = maxWidth
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = DialGeo(size.width.toFloat(), size.height.toFloat(), this)
                        val list = alarmsState.value
                        val hit = list.lastOrNull { a ->
                            (g.pointAt(a.minuteOfDay.toFloat(), g.r) - down.position).getDistance() <
                                g.trackW * 1.25f
                        }
                        if (hit != null) {
                            down.consume()
                            var moved = false
                            var cur = hit.minuteOfDay
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                if (!ch.pressed) break
                                if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    dragMinute = cur
                                    dragId = hit.id
                                }
                                if (moved) {
                                    val m = snap5(g.minuteAt(ch.position))
                                    if (m != cur) {
                                        cur = m
                                        dragMinute = m
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                            if (moved) onMoveState.value(hit, cur) else onTapState.value(hit)
                            dragId = null
                        } else {
                            val dist = (down.position - g.c).getDistance()
                            if (dist > g.r - g.trackW && dist < g.r + g.trackW * 1.4f) {
                                var timedOut = true
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    waitForUpOrCancellation()
                                    timedOut = false
                                }
                                if (timedOut) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAddState.value(snap5(g.minuteAt(down.position)))
                                    do {
                                        val e = awaitPointerEvent()
                                        e.changes.forEach { it.consume() }
                                    } while (e.changes.any { it.pressed })
                                }
                            }
                        }
                    }
                }
        ) {
            val g = DialGeo(size.width, size.height, this)
            drawFrame(g)
            drawArcToNext(g, now, nextAt)
            drawPucks(g, shown, next?.id, dragId)
            drawNowKnob(g, now)
            drawLabels(g, measurer, shown, now, next?.id)
        }

        // Centre card
        Column(
            Modifier.align(Alignment.Center).width(side * 0.46f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                dragged != null -> {
                    CenterSmall("MOVE")
                    CenterLabel(dragged.displayLabel)
                    CenterTime(dragged.timeText)
                    CenterSub("release to save", T24.Muted)
                }
                next != null && nextAt != null -> {
                    CenterSmall("NEXT")
                    CenterLabel(next.displayLabel)
                    CenterTime(next.timeText)
                    CenterSub("in " + formatCountdown(now, nextAt), T24.Text)
                    Spacer(Modifier.height(4.dp))
                    CenterSub(next.ringsText(), T24.Muted, 12)
                }
                else -> {
                    CenterSmall("NO ALARMS")
                    Spacer(Modifier.height(6.dp))
                    CenterSub("Tap +, or hold", T24.Muted, 13)
                    CenterSub("the ring to add one", T24.Muted, 13)
                }
            }
        }
    }
}

@Composable private fun CenterSmall(t: String) =
    Text(t, color = T24.Muted, fontSize = 11.sp, letterSpacing = 3.sp)

@Composable private fun CenterLabel(t: String) =
    Text(t, color = T24.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)

@Composable private fun CenterTime(t: String) =
    Text(t, color = T24.Text, fontSize = 50.sp, fontWeight = FontWeight.Light, maxLines = 1)

@Composable private fun CenterSub(t: String, c: Color, size: Int = 16) =
    Text(
        t, color = c, fontSize = size.sp, maxLines = 1, textAlign = TextAlign.Center,
        fontWeight = if (c == T24.Text) FontWeight.Medium else FontWeight.Normal
    )

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawFrame(g: DialGeo) {
    // thin outer line + hour ticks
    drawCircle(T24.Line, radius = g.ro, center = g.c, style = Stroke(1.dp.toPx()))
    for (h in 0 until 24) {
        val major = h % 3 == 0
        drawCircle(
            if (major) T24.Muted else T24.Faint,
            radius = if (major) 1.7.dp.toPx() else 1.dp.toPx(),
            center = g.pointAt(h * 60f, g.ro)
        )
    }
    // track
    drawCircle(T24.Track, radius = g.r, center = g.c, style = Stroke(g.trackW))
    drawCircle(T24.TrackEdge, radius = g.r + g.trackW / 2f, center = g.c, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.55f), radius = g.r - g.trackW / 2f, center = g.c, style = Stroke(2.dp.toPx()))
    // inner disc
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF221E1A), Color(0xFF110F0E)),
            center = g.c - Offset(0f, g.innerR * 0.35f),
            radius = g.innerR * 1.35f
        ),
        radius = g.innerR, center = g.c
    )
    drawCircle(Color(0xFF2B2825), radius = g.innerR, center = g.c, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawArcToNext(g: DialGeo, now: ZonedDateTime, nextAt: ZonedDateTime?) {
    if (nextAt == null) return
    val mins = Duration.between(now, nextAt).seconds / 60f
    if (mins <= 0f || mins > 1440f) return
    val nowMin = now.hour * 60 + now.minute + now.second / 60f
    val start = g.deg(nowMin) - 90f
    val sweep = g.deg(mins)
    val ra = g.r + g.trackW * 0.30f
    val tl = Offset(g.c.x - ra, g.c.y - ra)
    val sz = Size(ra * 2, ra * 2)

    // glow
    listOf(18.dp to 0.06f, 11.dp to 0.10f, 6.dp to 0.18f).forEach { (w, a) ->
        drawArc(
            T24.Amber.copy(alpha = a), start, sweep, false, tl, sz,
            style = Stroke(w.toPx(), cap = StrokeCap.Round)
        )
    }
    // core, brightening toward the alarm
    rotate(start, pivot = g.c) {
        val end = (sweep / 360f).coerceIn(0.001f, 1f)
        drawArc(
            brush = Brush.sweepGradient(
                0f to T24.AmberDark.copy(alpha = 0.6f),
                end to T24.AmberLight,
                center = g.c
            ),
            startAngle = 0f, sweepAngle = sweep, useCenter = false,
            topLeft = tl, size = sz,
            style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawPucks(g: DialGeo, alarms: List<Alarm>, nextId: Int?, dragId: Int?) {
    // next alarm and dragged alarm on top
    val ordered = alarms.sortedBy {
        when (it.id) { dragId -> 2; nextId -> 1; else -> 0 }
    }
    for (a in ordered) {
        val m = a.minuteOfDay.toFloat()
        val p = g.pointAt(m, g.r)
        val isNext = a.id == nextId
        val alpha = if (a.enabled) 1f else 0.3f
        val scale = if (a.id == dragId) 1.15f else 1f
        val l = g.puckL * scale
        val w = g.puckW * scale

        if (isNext) {
            drawCircle(
                Brush.radialGradient(
                    listOf(T24.Amber.copy(alpha = 0.55f), Color.Transparent),
                    center = p, radius = l * 1.3f
                ),
                radius = l * 1.3f, center = p
            )
        }
        // soft drop shadow (screen-space, falls downward)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Black.copy(alpha = 0.55f * alpha), Color.Transparent),
                center = p + Offset(0f, 4.dp.toPx()), radius = l * 0.75f
            ),
            radius = l * 0.75f, center = p + Offset(0f, 4.dp.toPx())
        )

        val top = if (isNext) T24.AmberLight else T24.CreamLight
        val bottom = if (isNext) T24.AmberDark else T24.CreamDark
        rotate(g.deg(m), pivot = p) {
            val tl = Offset(p.x - l / 2f, p.y - w / 2f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(top, bottom), startY = tl.y, endY = tl.y + w),
                topLeft = tl, size = Size(l, w),
                cornerRadius = CornerRadius(w * 0.45f), alpha = alpha
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.38f * alpha),
                topLeft = tl + Offset(l * 0.2f, w * 0.12f),
                size = Size(l * 0.6f, w * 0.2f),
                cornerRadius = CornerRadius(w * 0.1f)
            )
        }

        // trailing dots = extra rings
        if (isNext && a.rings > 1) {
            for (k in 1 until min(a.rings, 8)) {
                val along = l / 2f + k * 6.dp.toPx()
                val dDeg = Math.toDegrees((along / g.r).toDouble()).toFloat()
                val dm = dDeg / 360f * 1440f
                drawCircle(
                    T24.AmberLight.copy(alpha = 0.9f - k * 0.08f),
                    radius = 2.2.dp.toPx(),
                    center = g.pointAt(m + dm, g.r + g.trackW * 0.05f)
                )
            }
        }
    }
}

private fun DrawScope.drawNowKnob(g: DialGeo, now: ZonedDateTime) {
    val m = now.hour * 60 + now.minute + now.second / 60f
    val p = g.pointAt(m, g.r)
    drawLine(
        T24.Text, g.pointAt(m, g.ro - 3.dp.toPx()), g.pointAt(m, g.ro + 9.dp.toPx()),
        strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
            center = p + Offset(0f, 3.dp.toPx()), radius = g.trackW * 0.6f
        ),
        radius = g.trackW * 0.6f, center = p + Offset(0f, 3.dp.toPx())
    )
    drawCircle(
        Brush.radialGradient(listOf(T24.CreamLight, T24.CreamDark), center = p - Offset(2f, 3f), radius = g.trackW * 0.45f),
        radius = g.trackW * 0.36f, center = p
    )
    drawCircle(T24.Amber, radius = 2.4.dp.toPx(), center = p)
}

private data class Label(val minute: Float, val title: String, val sub: String, val titleColor: Color)

private fun DrawScope.drawLabels(
    g: DialGeo, measurer: TextMeasurer, alarms: List<Alarm>, now: ZonedDateTime, nextId: Int?
) {
    val hourStyle = TextStyle(color = T24.Muted, fontSize = 11.sp)
    for (h in 0 until 24 step 3) {
        val r = measurer.measure("%02d".format(h), hourStyle)
        val p = g.pointAt(h * 60f, g.rh)
        drawText(r, topLeft = Offset(p.x - r.size.width / 2f, p.y - r.size.height / 2f))
    }

    val nowMin = now.hour * 60 + now.minute + now.second / 60f
    val labels = buildList {
        add(Label(nowMin, "Now", "%02d:%02d".format(now.hour, now.minute), T24.Text))
        alarms.forEach { a ->
            val t = a.displayLabel.let { if (it.length > 16) it.take(15) + "…" else it }
            val col = when {
                !a.enabled -> T24.Faint
                a.id == nextId -> T24.Text
                else -> T24.Text.copy(alpha = 0.85f)
            }
            add(Label(a.minuteOfDay.toFloat(), t, a.timeText, col))
        }
    }.sortedBy { it.minute }

    val placed = mutableListOf<Rect>()
    val gap = 2.dp.toPx()
    for (lb in labels) {
        val tStyle = TextStyle(color = lb.titleColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        val sStyle = TextStyle(color = T24.Muted, fontSize = 12.sp)
        val tr: TextLayoutResult = measurer.measure(lb.title, tStyle, maxLines = 1)
        val sr: TextLayoutResult = measurer.measure(lb.sub, sStyle, maxLines = 1)
        val bw = maxOf(tr.size.width, sr.size.width).toFloat()
        val bh = (tr.size.height + sr.size.height).toFloat()

        val theta = Math.toRadians(g.deg(lb.minute).toDouble())
        val sinT = sin(theta).toFloat()
        val cosT = cos(theta).toFloat()
        val p = g.pointAt(lb.minute, g.rl)
        var left = p.x - bw * (1f - sinT) / 2f
        var top = p.y - bh * (1f + cosT) / 2f
        left = left.coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
        top = top.coerceIn(0f, (size.height - bh).coerceAtLeast(0f))

        var rect = Rect(left, top, left + bw, top + bh)
        var tries = 0
        val dir = if (p.y >= g.c.y) 1f else -1f
        while (placed.any { it.inflate(gap).overlaps(rect) } && tries < 6) {
            rect = rect.translate(0f, dir * (bh * 0.55f))
            tries++
        }
        if (rect.top < 0f || rect.bottom > size.height) continue
        placed.add(rect)

        val align = (1f - sinT) / 2f   // 0 = left-aligned, 1 = right-aligned
        drawText(tr, topLeft = Offset(rect.left + (bw - tr.size.width) * align, rect.top))
        drawText(sr, topLeft = Offset(rect.left + (bw - sr.size.width) * align, rect.top + tr.size.height))
    }
}
