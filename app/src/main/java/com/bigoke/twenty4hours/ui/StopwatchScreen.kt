package com.bigoke.twenty4hours.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.StopwatchOps
import com.bigoke.twenty4hours.data.StopwatchStore
import com.bigoke.twenty4hours.data.formatStopwatch

@Composable
fun StopwatchScreen(contentPadding: PaddingValues) {
    val ctx = LocalContext.current
    val sw by StopwatchStore.flow.collectAsState()
    var nowEl by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sw.running) {
        nowEl = SystemClock.elapsedRealtime()
        while (sw.running) {
            withFrameMillis { }
            nowEl = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = sw.elapsed(nowEl)
    val lastLapTotal = sw.laps.lastOrNull() ?: 0L
    val currentLap = elapsed - lastLapTotal

    // lap durations, newest first
    val lapDurations = sw.laps.mapIndexed { i, total -> total - (if (i == 0) 0L else sw.laps[i - 1]) }
    val best = if (lapDurations.size >= 2) lapDurations.min() else null
    val worst = if (lapDurations.size >= 2) lapDurations.max() else null

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding) {
        item { ScreenHeader("Stopwatch") }
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
                    // seconds sweep
                    val secDeg = (elapsed % 60_000L) / 60_000f * 360f
                    drawGlowArc(c, r + tw * 0.1f, 0f, secDeg, core = 4.dp)
                    // minutes: small dots on the inner edge, one per completed minute (up to 60)
                    val minutes = (elapsed / 60_000L).toInt().coerceAtMost(60)
                    for (i in 0 until minutes) {
                        drawCircle(
                            T24.CreamDark.copy(alpha = 0.8f), radius = 1.6.dp.toPx(),
                            center = pointOn(c, r - tw * 0.95f, i * 6f)
                        )
                    }
                    // lap markers on the ring
                    sw.laps.takeLast(12).forEach { total ->
                        val d = (total % 60_000L) / 60_000f * 360f
                        drawCircle(T24.Cream.copy(alpha = 0.7f), radius = 2.5.dp.toPx(), center = pointOn(c, r + tw * 0.1f, d))
                    }
                    if (elapsed > 0 || sw.running) {
                        drawKnob(pointOn(c, r + tw * 0.1f, secDeg), tw * 0.4f, amber = sw.running)
                    }
                }
                Column(
                    Modifier.align(Alignment.Center).width(side * 0.6f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        formatStopwatch(elapsed),
                        color = T24.Text, fontSize = (if (elapsed >= 3_600_000L) 34 * k else 44 * k).dp.fixedSp(),
                        fontWeight = FontWeight.Light, maxLines = 1,
                        fontFamily = FontFamily.Default
                    )
                    if (sw.laps.isNotEmpty()) {
                        Text(
                            "Lap ${sw.laps.size + 1} · ${formatStopwatch(currentLap)}",
                            color = T24.AmberLight, fontSize = (14 * k).dp.fixedSp(), maxLines = 1
                        )
                    } else {
                        Text(
                            if (sw.running) "running" else if (elapsed > 0) "paused" else "tap play to start",
                            color = T24.Muted, fontSize = (14 * k).dp.fixedSp(), maxLines = 1
                        )
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(
                    58.dp, primary = false,
                    onClick = { if (sw.running) StopwatchOps.lap(ctx) else StopwatchOps.reset(ctx) }
                ) {
                    GlyphIcon(if (sw.running) Glyph.FLAG else Glyph.RESET, T24.Cream, 22.dp)
                }
                Spacer(Modifier.width(26.dp))
                RoundButton(
                    78.dp, primary = true,
                    onClick = { if (sw.running) StopwatchOps.pause(ctx) else StopwatchOps.start(ctx) }
                ) {
                    GlyphIcon(if (sw.running) Glyph.PAUSE else Glyph.PLAY, OnAmber, 30.dp)
                }
                Spacer(Modifier.width(26.dp))
                Box(Modifier.size(58.dp))
            }
        }
        if (sw.laps.isNotEmpty()) {
            item { SectionTitle("Laps", Modifier.padding(start = 24.dp)) }
            val n = lapDurations.size
            itemsIndexed(lapDurations.reversed()) { idx, d ->
                val lapNo = n - idx
                val total = sw.laps[lapNo - 1]
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(
                                when (d) {
                                    best -> T24.Amber
                                    worst -> T24.Faint
                                    else -> T24.CreamDark.copy(alpha = 0.5f)
                                }
                            )
                        )
                        Spacer(Modifier.width(14.dp))
                        Text("Lap $lapNo", color = T24.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            formatStopwatch(d),
                            color = when (d) { best -> T24.AmberLight; else -> T24.Text },
                            fontSize = 16.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(formatStopwatch(total), color = T24.Muted, fontSize = 14.sp)
                    }
                    HorizontalDivider(
                        Modifier.padding(horizontal = 24.dp),
                        thickness = 0.5.dp, color = T24.Line.copy(alpha = 0.5f)
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                    if (best != null && worst != null) {
                        Text(
                            "Fastest ${formatStopwatch(best)} · Slowest ${formatStopwatch(worst)} · Avg ${
                                formatStopwatch(lapDurations.sum() / lapDurations.size)
                            }",
                            color = T24.Muted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
