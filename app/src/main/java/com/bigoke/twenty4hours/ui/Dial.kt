package com.bigoke.twenty4hours.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableFloatStateOf
import com.bigoke.twenty4hours.data.CalEvent
import com.bigoke.twenty4hours.data.Role
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.formatCountdown
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.math.roundToInt

/** All dial geometry in px, shared by drawing and touch handling. */
private class DialGeo(w: Float, h: Float, d: Density) {
    val c = Offset(w / 2f, h / 2f)
    val s = min(w, h)
    // Ring kept a little smaller than the screen so alarm names have room around it
    val r = s * 0.268f
    val trackW = s * 0.08f
    val ro = r + trackW * 0.95f
    val rh = ro + with(d) { 11.dp.toPx() }
    val rl = ro + with(d) { 7.dp.toPx() }
    val puckL = trackW * 1.22f
    val puckW = trackW * 1.0f

    fun deg(minute: Float) = minute / 1440f * 360f
    fun pointAt(minute: Float, radius: Float) = pointOn(c, radius, deg(minute))
    fun minuteAt(p: Offset): Int = (angleOf(c, p) / 360f * 1440f).roundToInt() % 1440
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
    events: List<CalEvent> = emptyList(),
    sun: Pair<Int, Int>? = null,
) {
    val haptic = LocalHapticFeedback.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragMinute by remember { mutableIntStateOf(0) }

    // Arc grows in when the screen opens
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }

    val alarmsState = rememberUpdatedState(alarms)
    val onMoveState = rememberUpdatedState(onMove)
    val onTapState = rememberUpdatedState(onTap)
    val onAddState = rememberUpdatedState(onAddAt)

    val shown = alarms.map {
        if (it.id == dragId) it.copy(hour = dragMinute / 60, minute = dragMinute % 60) else it
    }
    val dragged = shown.firstOrNull { it.id == dragId }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
            .pointerInput(Unit) {
                // Two-finger pinch zooms the dial (checked before the puck dragging below)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            val nz = (zoom * ev.calculateZoom()).coerceIn(1f, 3f)
                            val p = ev.calculatePan()
                            val max = (nz - 1f) * size.width / 2f
                            pan = if (nz <= 1.01f) Offset.Zero
                            else Offset((pan.x + p.x).coerceIn(-max, max), (pan.y + p.y).coerceIn(-max, max))
                            zoom = nz
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        val side = maxWidth
        Box(
            Modifier.matchParentSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
            }
        ) {
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
                            (g.pointAt(a.minuteOfDay.toFloat(), g.r) - down.position).getDistance() < g.trackW * 1.25f
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
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
            drawFace(g.c, g.r, g.trackW)
            if (sun != null) drawSun(g, sun)
            sleepPair(alarms)?.let { (bed, wake) -> drawNight(g, bed.minuteOfDay, wake.minuteOfDay) }
            drawEvents(g, events)
            drawArcToNext(g, now, nextAt, intro.value)
            drawPucks(g, shown, next?.id, dragId, now)
            drawNowKnob(g, now)
            drawLabels(g, measurer, shown, now, next?.id)
        }

        // Centre card — sized from the dial, not the system font size, so it always fits inside the ring
        val k = side.value / 440f
        Column(
            Modifier.align(Alignment.Center).width(side * 0.38f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                dragged != null -> {
                    CenterSmall("MOVE", k)
                    CenterLabel(dragged.displayLabel, k)
                    CenterTime(dragged.timeText, k)
                    CenterSub("release to save", T24.Muted, 14f, k)
                }
                next != null && nextAt != null -> {
                    CenterSmall(if (next.isReminder) "NEXT REMINDER" else "NEXT", k)
                    CenterLabel(next.displayLabel, k)
                    CenterTime(next.timeText, k)
                    CenterSub("in " + formatCountdown(now, nextAt), T24.Text, 16f, k)
                    Spacer(Modifier.height((3 * k).dp))
                    CenterSub(next.ringsText(), T24.Muted, 12f, k)
                }
                else -> {
                    CenterSmall("NO ALARMS", k)
                    Spacer(Modifier.height((6 * k).dp))
                    CenterSub("Tap +, or hold", T24.Muted, 13f, k)
                    CenterSub("the ring to add one", T24.Muted, 13f, k)
                }
            }
        }
        }
        if (zoom > 1.01f) {
            Text(
                "Reset zoom", color = T24.AmberLight, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(T24.SurfaceHigh)
                    .clickable { zoom = 1f; pan = Offset.Zero }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** Bedtime reminder and the wake-up alarm after it, for the night band and sleep length. */
fun sleepPair(alarms: List<Alarm>): Pair<Alarm, Alarm>? {
    val bed = alarms.firstOrNull { it.active && it.isBedtime } ?: return null
    val wakes = alarms.filter { it.active && !it.isReminder }
    fun gap(a: Alarm) = Math.floorMod(a.minuteOfDay - bed.minuteOfDay, 1440)
    val wake = wakes.firstOrNull { it.role == Role.WAKE }
        ?: wakes.filter { gap(it) in 1..(16 * 60) }.minByOrNull { gap(it) }
        ?: return null
    return bed to wake
}

fun sleepMinutes(bed: Alarm, wake: Alarm) = Math.floorMod(wake.minuteOfDay - bed.minuteOfDay, 1440)

private fun DrawScope.drawNight(g: DialGeo, bedMin: Int, wakeMin: Int) {
    val sweep = g.deg(Math.floorMod(wakeMin - bedMin, 1440).toFloat())
    val ra = g.r
    drawArc(
        Color(0xFF2A3550).copy(alpha = 0.55f), g.deg(bedMin.toFloat()) - 90f, sweep, false,
        Offset(g.c.x - ra, g.c.y - ra), Size(ra * 2, ra * 2), style = Stroke(g.trackW * 0.92f)
    )
    // a few faint "stars" along the band
    val n = (sweep / 18f).toInt()
    for (i in 1 until n) {
        val d = g.deg(bedMin.toFloat()) + sweep * i / n
        val jitter = if (i % 2 == 0) 0.22f else -0.18f
        drawCircle(Color.White.copy(alpha = 0.35f), radius = 1.1.dp.toPx(), center = pointOn(g.c, g.r + g.trackW * jitter, d))
    }
}

private fun DrawScope.drawSun(g: DialGeo, sun: Pair<Int, Int>) {
    val (up, down) = sun
    val ra = g.ro
    drawArc(
        T24.CreamDark.copy(alpha = 0.35f), g.deg(up.toFloat()) - 90f, g.deg(Math.floorMod(down - up, 1440).toFloat()), false,
        Offset(g.c.x - ra, g.c.y - ra), Size(ra * 2, ra * 2), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
    )
    listOf(up, down).forEachIndexed { i, m ->
        val p = g.pointAt(m.toFloat(), ra)
        drawCircle(T24.Bg, radius = 5.dp.toPx(), center = p)
        if (i == 0) drawCircle(T24.AmberLight, radius = 3.5.dp.toPx(), center = p)
        else drawCircle(T24.AmberLight, radius = 3.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawEvents(g: DialGeo, events: List<CalEvent>) {
    val ra = g.r - g.trackW * 0.62f
    events.filter { !it.allDay && it.endMin > it.startMin }.forEach { e ->
        val col = Color(e.color).copy(alpha = 1f)
        drawArc(
            lerp(col, T24.Cream, 0.35f).copy(alpha = 0.8f),
            g.deg(e.startMin.toFloat()) - 90f, g.deg((e.endMin - e.startMin).toFloat()).coerceAtLeast(1.5f), false,
            Offset(g.c.x - ra, g.c.y - ra), Size(ra * 2, ra * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable private fun CenterSmall(t: String, k: Float) =
    Text(
        t, color = T24.Muted, fontSize = (11 * k).dp.fixedSp(), letterSpacing = (3 * k).dp.fixedSp(),
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )

@Composable private fun CenterLabel(t: String, k: Float) =
    Text(
        t, color = T24.Text, fontSize = (17 * k).dp.fixedSp(), fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
    )

@Composable private fun CenterTime(t: String, k: Float) =
    Text(
        t, color = T24.Text, fontSize = (50 * k).dp.fixedSp(), lineHeight = (56 * k).dp.fixedSp(),
        fontWeight = FontWeight.Light, maxLines = 1
    )

@Composable private fun CenterSub(t: String, c: Color, size: Float, k: Float) =
    Text(
        t, color = c, fontSize = (size * k).dp.fixedSp(), maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        fontWeight = if (c == T24.Text) FontWeight.Medium else FontWeight.Normal
    )

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawArcToNext(g: DialGeo, now: ZonedDateTime, nextAt: ZonedDateTime?, intro: Float) {
    if (nextAt == null) return
    val mins = Duration.between(now, nextAt).seconds / 60f
    if (mins <= 0f || mins > 1440f) return
    val nowMin = now.hour * 60 + now.minute + now.second / 60f
    drawGlowArc(g.c, g.r + g.trackW * 0.30f, g.deg(nowMin), g.deg(mins) * intro)
}

private fun DrawScope.drawPucks(g: DialGeo, alarms: List<Alarm>, nextId: Int?, dragId: Int?, now: ZonedDateTime) {
    val ordered = alarms.sortedBy {
        when (it.id) { dragId -> 2; nextId -> 1; else -> 0 }
    }
    for (a in ordered) {
        val m = a.minuteOfDay.toFloat()
        val p = g.pointAt(m, g.r)
        val isNext = a.id == nextId
        val alpha = when {
            !a.active -> 0.28f
            a.isSkipping(now) -> 0.5f
            else -> 1f
        }
        val scale = if (a.id == dragId) 1.15f else 1f

        if (isNext) {
            val gr = g.puckL * 1.3f
            drawCircle(
                Brush.radialGradient(listOf(T24.Amber.copy(alpha = 0.55f), Color.Transparent), center = p, radius = gr),
                radius = gr, center = p
            )
        }

        if (a.isReminder) {
            // reminders: smaller round pebble
            val rr = g.trackW * 0.46f * scale
            drawCircle(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f * alpha), Color.Transparent),
                    center = p + Offset(0f, 3.dp.toPx()), radius = rr * 1.6f),
                radius = rr * 1.6f, center = p + Offset(0f, 3.dp.toPx())
            )
            val (hi, lo) = if (isNext) T24.AmberLight to T24.AmberDark else T24.CreamLight to T24.CreamDark
            drawCircle(
                Brush.radialGradient(listOf(hi, lo), center = p - Offset(rr * 0.3f, rr * 0.4f), radius = rr * 1.4f),
                radius = rr, center = p, alpha = alpha
            )
            drawCircle(Color(0xFF1A140C).copy(alpha = 0.55f * alpha), radius = rr * 0.28f, center = p)
            continue
        }

        val l = g.puckL * scale
        val w = g.puckW * scale
        drawCircle(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f * alpha), Color.Transparent),
                center = p + Offset(0f, 3.dp.toPx()), radius = l * 0.62f),
            radius = l * 0.62f, center = p + Offset(0f, 3.dp.toPx())
        )
        val top = if (isNext) T24.AmberLight else T24.CreamLight
        val bottom = if (isNext) T24.AmberDark else T24.CreamDark
        rotate(g.deg(m), pivot = p) {
            val tl = Offset(p.x - l / 2f, p.y - w / 2f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(top, bottom), startY = tl.y, endY = tl.y + w),
                topLeft = tl, size = Size(l, w), cornerRadius = CornerRadius(w * 0.45f), alpha = alpha
            )
            // soft sheen on the upper half
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.32f * alpha), Color.Transparent),
                    startY = tl.y, endY = tl.y + w * 0.55f
                ),
                topLeft = tl + Offset(l * 0.1f, w * 0.06f),
                size = Size(l * 0.8f, w * 0.5f),
                cornerRadius = CornerRadius(w * 0.35f)
            )
            if (a.isSkipping(now) && a.enabled) {
                drawLine(
                    Color(0xFF1A140C).copy(alpha = 0.6f),
                    Offset(tl.x + l * 0.25f, tl.y + w * 0.8f), Offset(tl.x + l * 0.75f, tl.y + w * 0.2f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }

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
    drawKnob(p, g.trackW * 0.36f, amber = false)
    drawCircle(T24.Amber, radius = 2.4.dp.toPx(), center = p)
}

private data class Label(val minute: Float, val title: String, val sub: String, val titleColor: Color)

private fun DrawScope.drawLabels(
    g: DialGeo, measurer: TextMeasurer, alarms: List<Alarm>, now: ZonedDateTime, nextId: Int?
) {
    // Sizes follow the dial, not the system font scale, so labels never outgrow the space around it.
    val k = g.s / 400.dp.toPx()
    val nowMin = now.hour * 60 + now.minute + now.second / 60f
    val labels = buildList {
        add(Label(nowMin, "Now", "%02d:%02d".format(now.hour, now.minute), T24.Text))
        alarms.forEach { a ->
            val col = when {
                !a.active -> T24.Faint
                a.id == nextId -> T24.Text
                else -> T24.Text.copy(alpha = 0.85f)
            }
            val sub = if (a.enabled && a.isSkipping(now)) a.timeText + " · skip" else a.timeText
            add(Label(a.minuteOfDay.toFloat(), a.displayLabel, sub, col))
        }
    }.sortedBy { it.minute }

    val tStyleBase = TextStyle(fontSize = (12.5f * k).dp.toSp(), fontWeight = FontWeight.Medium, lineHeight = (15 * k).dp.toSp())
    val sStyle = TextStyle(color = T24.Muted, fontSize = (11.5f * k).dp.toSp(), lineHeight = (14 * k).dp.toSp())
    val edge = 4.dp.toPx()
    val maxLabel = 104.dp.toPx() * k

    val placed = mutableListOf<Rect>()
    val gap = 2.dp.toPx()
    for (lb in labels) {
        val theta = Math.toRadians(g.deg(lb.minute).toDouble())
        val sinT = kotlin.math.sin(theta).toFloat()
        val cosT = kotlin.math.cos(theta).toFloat()
        val p = g.pointAt(lb.minute, g.rl)

        // Room between the label's anchor and the screen edge, so long names end in "…" instead of clipping
        val room = when {
            sinT > 0.35f -> size.width - p.x - edge
            sinT < -0.35f -> p.x - edge
            else -> 2f * minOf(p.x, size.width - p.x) - 2 * edge
        }.coerceIn(24.dp.toPx(), maxLabel)

        val tr: TextLayoutResult = measurer.measure(
            lb.title, tStyleBase.copy(color = lb.titleColor),
            overflow = TextOverflow.Ellipsis, maxLines = 2,
            constraints = Constraints(maxWidth = room.toInt())
        )
        val sr: TextLayoutResult = measurer.measure(
            lb.sub, sStyle, maxLines = 1, constraints = Constraints(maxWidth = room.toInt())
        )
        val bw = maxOf(tr.size.width, sr.size.width).toFloat()
        val bh = (tr.size.height + sr.size.height).toFloat()

        var left = p.x - bw * (1f - sinT) / 2f
        var top = p.y - bh * (1f + cosT) / 2f
        left = left.coerceIn(edge, (size.width - bw - edge).coerceAtLeast(edge))
        top = top.coerceIn(0f, (size.height - bh).coerceAtLeast(0f))

        var rect = Rect(left, top, left + bw, top + bh)
        var tries = 0
        val dir = if (p.y >= g.c.y) 1f else -1f
        while (placed.any { it.inflate(gap).overlaps(rect) } && tries < 8) {
            rect = rect.translate(0f, dir * (bh * 0.5f))
            tries++
        }
        if (rect.top < 0f || rect.bottom > size.height) continue
        placed.add(rect)

        val align = (1f - sinT) / 2f
        drawText(tr, topLeft = Offset(rect.left + (bw - tr.size.width) * align, rect.top))
        drawText(sr, topLeft = Offset(rect.left + (bw - sr.size.width) * align, rect.top + tr.size.height))
    }

    // Hour numbers last, skipping any that would sit under a label
    val hourStyle = TextStyle(color = T24.Muted, fontSize = (11 * k).dp.toSp())
    for (h in 0 until 24 step 3) {
        val r = measurer.measure("%02d".format(h), hourStyle)
        val p = g.pointAt(h * 60f, g.rh)
        val rect = Rect(p.x - r.size.width / 2f, p.y - r.size.height / 2f, p.x + r.size.width / 2f, p.y + r.size.height / 2f)
        if (placed.any { it.inflate(gap).overlaps(rect) }) continue
        drawText(r, topLeft = rect.topLeft)
    }
}
