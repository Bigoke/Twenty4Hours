package com.bigoke.twenty4hours.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.SessionOps
import com.bigoke.twenty4hours.alarm.phaseTitle
import com.bigoke.twenty4hours.data.FocusConfig
import com.bigoke.twenty4hours.data.IntervalConfig
import com.bigoke.twenty4hours.data.PhaseKind
import com.bigoke.twenty4hours.data.SessionKind
import com.bigoke.twenty4hours.data.SessionState
import com.bigoke.twenty4hours.data.SessionStore
import com.bigoke.twenty4hours.data.formatHms

/** For screenshot tests: the Timer tab opened on a given pane. */
@Composable
internal fun TimerScreenForTest(mode: Int) = TimerScreen(PaddingValues(top = 24.dp, bottom = 140.dp), mode)

/** Timer tab: plain timers, Pomodoro focus, and workout intervals. */
@Composable
fun TimerScreen(contentPadding: PaddingValues, startMode: Int? = null) {
    val session by SessionStore.flow.collectAsState()
    var mode by rememberSaveable {
        mutableIntStateOf(startMode ?: if (session.active) (if (session.kind == SessionKind.FOCUS) 1 else 2) else 0)
    }
    val header: @Composable () -> Unit = {
        Column {
            ScreenHeader("Timer")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip("Timers", mode == 0) { mode = 0 }
                Chip("Focus", mode == 1) { mode = 1 }
                Chip("Intervals", mode == 2) { mode = 2 }
            }
        }
    }
    when (mode) {
        0 -> TimersPane(contentPadding, header)
        1 -> SessionPane(SessionKind.FOCUS, contentPadding, header)
        else -> SessionPane(SessionKind.INTERVAL, contentPadding, header)
    }
}

@Composable
private fun SessionPane(kind: SessionKind, contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val session by SessionStore.flow.collectAsState()
    val focus by SessionStore.focusConfig.collectAsState()
    val interval by SessionStore.intervalConfig.collectAsState()
    val mine = session.active && session.kind == kind
    val other = session.active && session.kind != kind

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mine && session.running) {
        now = System.currentTimeMillis()
        while (mine && session.running) { withFrameMillis { }; now = System.currentTimeMillis() }
    }

    val preview = remember(focus, interval, kind) {
        if (kind == SessionKind.FOCUS) SessionState.focus(focus) else SessionState.interval(interval)
    }
    val phases = if (mine) session.phases else preview
    val index = if (mine) session.index else -1
    val phase = if (mine) session.phase else null
    val remaining = if (mine) session.remainingMs(now) else phases.firstOrNull()?.ms ?: 0L
    val total = phases.sumOf { it.ms }.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding) {
        item { header() }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp).aspectRatio(1f)) {
                val side = maxWidth
                val k = side.value / 360f
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val s = size.minDimension
                    val r = s * 0.36f
                    val tw = s * 0.09f
                    drawFace(c, r, tw, ticks = 60, majorEvery = 5)
                    // Whole session as segments around the ring: done, current, to come
                    val gap = 1.4f
                    var start = 0f
                    phases.forEachIndexed { i, p ->
                        val sweep = p.ms.toFloat() / total * 360f
                        val color = when (p.kind) {
                            PhaseKind.WORK -> T24.Amber
                            PhaseKind.PREP -> T24.Muted
                            else -> T24.CreamDark
                        }
                        val alpha = when {
                            index < 0 -> 0.45f
                            i < index -> 0.9f
                            i == index -> 0.35f
                            else -> 0.2f
                        }
                        val ra = r + tw * 0.1f
                        drawArc(
                            color.copy(alpha = alpha), start - 90f + gap / 2, (sweep - gap).coerceAtLeast(0.6f), false,
                            Offset(c.x - ra, c.y - ra), Size(ra * 2, ra * 2), style = Stroke(tw * 0.42f, cap = StrokeCap.Butt)
                        )
                        if (i == index && p.ms > 0) {
                            val done = (1f - remaining.toFloat() / p.ms).coerceIn(0f, 1f)
                            drawGlowArc(c, ra, start + gap / 2, (sweep - gap) * done, core = 5.dp)
                            drawKnob(pointOn(c, ra, start + gap / 2 + (sweep - gap) * done), tw * 0.38f, amber = session.running)
                        }
                        start += sweep
                    }
                }
                Column(Modifier.align(Alignment.Center).width(side * 0.56f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        phase?.let { phaseTitle(kind, it.kind) } ?: if (kind == SessionKind.FOCUS) "Pomodoro" else "Intervals",
                        color = if (phase?.kind == PhaseKind.WORK) T24.AmberLight else T24.Muted,
                        fontSize = (16 * k).dp.fixedSp(), maxLines = 1
                    )
                    Text(
                        formatHms(remaining), color = T24.Text, fontWeight = FontWeight.Light, maxLines = 1,
                        fontSize = (if (remaining >= 3_600_000L) 44 * k else 56 * k).dp.fixedSp()
                    )
                    val rounds = phases.maxOfOrNull { it.round } ?: 0
                    Text(
                        if (phase != null && phase.round > 0) "Round ${phase.round} of $rounds"
                        else "$rounds rounds · ${formatHms(total)} total",
                        color = T24.Muted, fontSize = (14 * k).dp.fixedSp(), maxLines = 1
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(58.dp, primary = false, onClick = { if (mine) SessionOps.stop(ctx) }) {
                    GlyphIcon(Glyph.STOP, if (mine) T24.Cream else T24.Faint, 20.dp)
                }
                Spacer(Modifier.width(22.dp))
                RoundButton(78.dp, primary = true, onClick = {
                    when {
                        !mine -> SessionOps.start(ctx, kind)
                        session.running -> SessionOps.pause(ctx)
                        else -> SessionOps.resume(ctx)
                    }
                }) {
                    GlyphIcon(if (mine && session.running) Glyph.PAUSE else Glyph.PLAY, OnAmber, 30.dp)
                }
                Spacer(Modifier.width(22.dp))
                RoundButton(58.dp, primary = false, onClick = { if (mine) SessionOps.skip(ctx) }) {
                    Text("Skip", color = if (mine) T24.Cream else T24.Faint, fontSize = 13.dp.fixedSp(), fontWeight = FontWeight.Medium)
                }
            }
        }
        if (other) {
            item {
                Text(
                    "A ${if (session.kind == SessionKind.FOCUS) "focus" else "interval"} session is running — starting this one will replace it.",
                    color = T24.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                if (kind == SessionKind.FOCUS) FocusSettings(focus, enabled = !mine) else IntervalSettings(interval, enabled = !mine)
            }
        }
    }
}

@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Text(title, color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    content()
}

@Composable
private fun FocusSettings(c: FocusConfig, enabled: Boolean) {
    SectionTitle(if (enabled) "Session" else "Session (stop to change)")
    fun set(n: FocusConfig) { if (enabled) SessionStore.setFocus(n) }
    Setting("Focus") { ChoiceRow(listOf(15, 20, 25, 30, 45, 50, 60), c.focusMin, { "$it min" }) { set(c.copy(focusMin = it)) } }
    Setting("Short break") { ChoiceRow(listOf(3, 5, 10), c.breakMin, { "$it min" }) { set(c.copy(breakMin = it)) } }
    Setting("Long break") { ChoiceRow(listOf(10, 15, 20, 30), c.longBreakMin, { "$it min" }) { set(c.copy(longBreakMin = it)) } }
    Setting("Rounds") { ChoiceRow(listOf(1, 2, 3, 4, 5, 6, 8), c.rounds, { "$it" }) { set(c.copy(rounds = it)) } }
    Text(
        "A chime and a short spoken cue mark each change. Runs with the screen off.",
        color = T24.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)
    )
}

@Composable
private fun IntervalSettings(c: IntervalConfig, enabled: Boolean) {
    SectionTitle(if (enabled) "Workout" else "Workout (stop to change)")
    fun set(n: IntervalConfig) { if (enabled) SessionStore.setInterval(n) }
    Setting("Work") { ChoiceRow(listOf(20, 30, 40, 45, 60, 90, 120), c.workSec, { secs(it) }) { set(c.copy(workSec = it)) } }
    Setting("Rest") { ChoiceRow(listOf(0, 10, 15, 20, 30, 60), c.restSec, { if (it == 0) "None" else secs(it) }) { set(c.copy(restSec = it)) } }
    Setting("Rounds") { ChoiceRow(listOf(4, 6, 8, 10, 12, 16, 20), c.rounds, { "$it" }) { set(c.copy(rounds = it)) } }
    Setting("Get ready") { ChoiceRow(listOf(0, 5, 10, 15), c.prepSec, { if (it == 0) "None" else "${it}s" }) { set(c.copy(prepSec = it)) } }
    OptionRow("Voice countdown", "Speaks 3-2-1, “Work” and “Rest”") { T24Switch(c.voice, { set(c.copy(voice = it)) }) }
}

private fun secs(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60} min" else if (s > 60) "${s / 60}:${"%02d".format(s % 60)}" else "${s}s"
