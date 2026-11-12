package com.bigoke.twenty4hours.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.TimerOps
import com.bigoke.twenty4hours.data.TimerItem
import com.bigoke.twenty4hours.data.TimerState
import com.bigoke.twenty4hours.data.TimerStore
import com.bigoke.twenty4hours.data.formatHms
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private val PRESETS = listOf(1, 3, 5, 10, 15, 20, 25, 30, 45, 60, 90)

@Composable
internal fun TimersPane(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val timers by TimerStore.timers.collectAsState()
    val selectedId by TimerStore.selected.collectAsState()
    val timer = timers.firstOrNull { it.id == selectedId } ?: timers.firstOrNull() ?: return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = timers.any { it.state == TimerState.RUNNING }
    LaunchedEffect(anyRunning) {
        now = System.currentTimeMillis()
        while (anyRunning) {
            withFrameMillis { }
            now = System.currentTimeMillis()
        }
    }

    var renaming by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = contentPadding
    ) {
        item { header() }
        item {
            TimerFace(
                timer = timer,
                now = now,
                onSetMinutes = { totalMin ->
                    TimerOps.setDuration(ctx, timer.id, totalMin * 60_000L)
                },
                onLabelTap = { renaming = true },
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        item {
            // Controls
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(58.dp, primary = false, onClick = { TimerOps.reset(ctx, timer.id) }) {
                    GlyphIcon(Glyph.RESET, T24.Cream, 22.dp)
                }
                Spacer(Modifier.width(22.dp))
                RoundButton(78.dp, primary = true, onClick = {
                    if (timer.state == TimerState.RUNNING) TimerOps.pause(ctx, timer.id)
                    else TimerOps.start(ctx, timer.id)
                }) {
                    GlyphIcon(if (timer.state == TimerState.RUNNING) Glyph.PAUSE else Glyph.PLAY, OnAmber, 30.dp)
                }
                Spacer(Modifier.width(22.dp))
                RoundButton(58.dp, primary = false, onClick = { TimerOps.addMs(ctx, timer.id, 60_000L) }) {
                    Text("+1:00", color = T24.Cream, fontSize = 13.dp.fixedSp(), fontWeight = FontWeight.Medium)
                }
            }
        }
        if (timer.state == TimerState.IDLE) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Hours", color = T24.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val h = (timer.durationMs / 3_600_000L).toInt()
                    val m = ((timer.durationMs / 60_000L) % 60).toInt()
                    StepButton("−") { if (h > 0) TimerOps.setDuration(ctx, timer.id, ((h - 1) * 60L + m).coerceAtLeast(1) * 60_000L) }
                    Text("$h", color = T24.Text, fontSize = 22.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    StepButton("+") { if (h < 23) TimerOps.setDuration(ctx, timer.id, ((h + 1) * 60L + m) * 60_000L) }
                }
            }
        }
        item { Column {
            SectionTitle("Quick start", Modifier.padding(start = 24.dp, top = 16.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(PRESETS) { p ->
                    Chip(if (p >= 60) "${p / 60}h${if (p % 60 > 0) " ${p % 60}m" else ""}" else "$p min", false) {
                        if (timer.state == TimerState.IDLE) {
                            TimerOps.setDuration(ctx, timer.id, p * 60_000L)
                            TimerOps.start(ctx, timer.id)
                        } else {
                            val t = TimerStore.add(p * 60_000L)
                            TimerOps.start(ctx, t.id)
                        }
                    }
                }
            }
        } }
        item { Column {
            SectionTitle("Saved", Modifier.padding(start = 24.dp, top = 20.dp))
            PresetRow(timer)
        } }
        item { Column {
            SectionTitle("Your timers", Modifier.padding(start = 24.dp, top = 22.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(timers, key = { it.id }) { t ->
                    TimerCard(t, now, selected = t.id == timer.id) { TimerStore.select(t.id) }
                }
                item {
                    Box(
                        Modifier
                            .size(width = 100.dp, height = 112.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, T24.Line, RoundedCornerShape(22.dp))
                            .clickable { TimerStore.add(5 * 60_000L) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            GlyphIcon(Glyph.PLUS, T24.Cream, 22.dp)
                            Spacer(Modifier.height(6.dp))
                            Text("New", color = T24.Muted, fontSize = 13.dp.fixedSp())
                        }
                    }
                }
            }
        } }
        if (timers.size > 1) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { TimerOps.delete(ctx, timer.id) }) {
                        Text("Delete this timer", color = T24.Muted)
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember(timer.id) { mutableStateOf(timer.label) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = T24.Surface,
            title = { Text("Timer name") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                    placeholder = { Text("Pasta, laundry…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T24.Amber, unfocusedBorderColor = T24.Line, cursorColor = T24.Amber
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { TimerOps.rename(ctx, timer.id, text.trim()); renaming = false }) {
                    Text("Save", color = T24.Amber)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Cancel", color = T24.Muted) }
            }
        )
    }
}

@Composable
private fun TimerFace(
    timer: TimerItem,
    now: Long,
    onSetMinutes: (Int) -> Unit,
    onLabelTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val idle = timer.state == TimerState.IDLE
    val remaining = timer.remaining(now)
    val frac = if (idle) {
        val mins = (timer.durationMs / 60_000L) % 60
        if (mins == 0L && timer.durationMs >= 3_600_000L) 1f else mins / 60f
    } else (remaining.toFloat() / timer.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shownFrac = if (idle) animatedFloat(frac) else frac

    val timerState = rememberUpdatedState(timer)
    val onSet = rememberUpdatedState(onSetMinutes)

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        val side = maxWidth
        val k = side.value / 360f
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val t = timerState.value
                        if (t.state != TimerState.IDLE) return@awaitEachGesture
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val s = minOf(size.width, size.height).toFloat()
                        val r = s * 0.36f
                        val tw = s * 0.09f
                        val d = (down.position - c).getDistance()
                        if (d < r - tw * 1.3f || d > r + tw * 1.6f) return@awaitEachGesture
                        down.consume()
                        val hours = (t.durationMs / 3_600_000L).toInt()
                        var last = -1
                        fun apply(p: Offset) {
                            var m = (angleOf(c, p) / 6f).roundToInt() % 60
                            if (m == 0 && hours == 0) m = 60
                            if (m != last) {
                                last = m
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSet.value((hours * 60 + m).coerceAtLeast(1))
                            }
                        }
                        apply(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) break
                            apply(ch.position)
                        }
                    }
                }
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val s = size.minDimension
            val r = s * 0.36f
            val tw = s * 0.09f
            drawFace(c, r, tw, ticks = 60, majorEvery = 5)
            val sweep = shownFrac * 360f
            drawGlowArc(c, r + tw * 0.1f, 0f, sweep, core = 5.dp)
            val running = timer.state == TimerState.RUNNING
            drawKnob(pointOn(c, r + tw * 0.1f, sweep), tw * 0.42f, amber = running || idle)
        }

        Column(
            Modifier.align(Alignment.Center).width(side * 0.56f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                timer.displayLabel, color = T24.Muted, fontSize = (15 * k).dp.fixedSp(), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLabelTap).padding(4.dp)
            )
            Text(
                formatHms(remaining),
                color = T24.Text,
                fontSize = (if (remaining >= 3_600_000L) 44 * k else 56 * k).dp.fixedSp(),
                fontWeight = FontWeight.Light, maxLines = 1
            )
            val status = when (timer.state) {
                TimerState.RUNNING -> Instant.ofEpochMilli(timer.endAt).atZone(ZoneId.systemDefault())
                    .let { "Ends %02d:%02d".format(it.hour, it.minute) }
                TimerState.PAUSED -> "Paused"
                TimerState.IDLE -> "Drag the knob to set"
            }
            val col by animateColorAsState(
                if (timer.state == TimerState.RUNNING) T24.AmberLight else T24.Muted, label = "st"
            )
            Text(status, color = col, fontSize = (14 * k).dp.fixedSp(), maxLines = 1)
        }
    }
}

@Composable
private fun TimerCard(t: TimerItem, now: Long, selected: Boolean, onClick: () -> Unit) {
    val rem = t.remaining(now)
    val frac = (rem.toFloat() / t.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .width(100.dp)
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(if (selected) T24.SurfaceHigh else T24.Surface)
            .border(1.dp, if (selected) T24.Amber.copy(alpha = 0.6f) else T24.Line, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(42.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.38f
            drawCircle(T24.Track, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
            if (t.state != TimerState.IDLE) {
                drawArc(
                    if (t.state == TimerState.RUNNING) T24.Amber else T24.CreamDark,
                    -90f, frac * 360f, false,
                    Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(formatHms(rem), color = T24.Text, fontSize = 15.dp.fixedSp(), maxLines = 1)
        Text(
            if (t.label.isBlank()) when (t.state) {
                TimerState.RUNNING -> "running"; TimerState.PAUSED -> "paused"; TimerState.IDLE -> "ready"
            } else t.label,
            color = T24.Muted, fontSize = 11.dp.fixedSp(), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ScreenHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = T24.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        trailing()
    }
}


/** Saved named timers (Soft egg 6:30 …): tap to start one, or save the current timer. */
@Composable
private fun PresetRow(current: TimerItem) {
    val ctx = LocalContext.current
    val presets by com.bigoke.twenty4hours.data.PresetStore.presets.collectAsState()
    var managing by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(presets, key = { it.id }) { p ->
            Chip((if (managing) "✕  " else "") + p.name + " · " + formatHms(p.ms), managing) {
                if (managing) com.bigoke.twenty4hours.data.PresetStore.remove(p.id)
                else {
                    val t = TimerStore.add(p.ms, p.name)
                    TimerOps.start(ctx, t.id)
                }
            }
        }
        item {
            Chip("+ Save current", false) {
                com.bigoke.twenty4hours.data.PresetStore.add(current.label, current.durationMs)
            }
        }
        item { Chip(if (managing) "Done" else "Edit", false) { managing = !managing } }
    }
}
