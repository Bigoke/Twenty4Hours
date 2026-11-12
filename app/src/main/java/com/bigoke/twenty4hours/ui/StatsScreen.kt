package com.bigoke.twenty4hours.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.EventLog
import com.bigoke.twenty4hours.data.EventType
import com.bigoke.twenty4hours.data.LogEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private data class DayStat(val date: LocalDate, val wakeMin: Int?, val snoozes: Int, val lateMin: Int?, val sleepMin: Int?)

private fun buildDays(events: List<LogEvent>, days: Int): List<DayStat> {
    val zone = ZoneId.systemDefault()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun minOfDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
    val today = LocalDate.now()
    return (days - 1 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        val dayEvents = events.filter { date(it.at) == d }
        // first alarm stopped in the morning (before 14:00) counts as waking up
        val stop = dayEvents.filter { it.type == EventType.STOP && minOfDay(it.at) < 14 * 60 }.minByOrNull { it.at }
        val snoozes = dayEvents.count { it.type == EventType.SNOOZE && minOfDay(it.at) < 14 * 60 }
        val late = stop?.takeIf { it.scheduledAt > 0 }?.let { ((it.at - it.scheduledAt) / 60_000L).toInt().coerceAtLeast(0) }
        val bed = stop?.let { s ->
            events.filter { it.type == EventType.BEDTIME && it.at < s.at && s.at - it.at < 16 * 3_600_000L }.maxByOrNull { it.at }
        }
        val sleep = if (stop != null && bed != null) ((stop.at - bed.at) / 60_000L).toInt() else null
        DayStat(d, stop?.let { minOfDay(it.at) }, snoozes, late, sleep)
    }
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun dur(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val events by EventLog.events.collectAsState()
    val days = remember(events) { buildDays(events, 14) }
    val week = days.takeLast(7)
    var confirmClear by remember { mutableStateOf(false) }

    val wakes = week.mapNotNull { it.wakeMin }
    val avgWake = if (wakes.isNotEmpty()) wakes.average().toInt() else null
    val snoozesWeek = week.sumOf { it.snoozes }
    val lates = week.mapNotNull { it.lateMin }
    val avgLate = if (lates.isNotEmpty()) lates.average().toInt() else null
    val sleeps = week.mapNotNull { it.sleepMin }
    val avgSleep = if (sleeps.isNotEmpty()) sleeps.average().toInt() else null

    Box(Modifier.fillMaxSize().background(T24.Bg)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp).height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = T24.Text)
                    }
                    Text("Sleep & wake", color = T24.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    if (events.isEmpty()) {
                        Text(
                            "Nothing yet. Each time you stop or snooze an alarm, it's noted here — after a few mornings you'll see your pattern.",
                            color = T24.Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    SectionTitle("Last 7 days")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Up at", avgWake?.let { hm(it) } ?: "–", "average", Modifier.weight(1f))
                        StatTile("Snoozes", "$snoozesWeek", "this week", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Lag", avgLate?.let { dur(it) } ?: "–", "alarm → up", Modifier.weight(1f))
                        StatTile("Sleep", avgSleep?.let { dur(it) } ?: "–", "bedtime → up", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("Wake-up time · 14 days")
                    WakeChart(days)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Dots show when you stopped your first alarm; amber bars under each day are snoozes. " +
                            "Sleep uses your Bedtime reminder, so set one to see it.",
                        color = T24.Faint, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    if (events.isNotEmpty()) PillButton("Clear history", height = 46.dp) { confirmClear = true }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = T24.Surface,
            title = { Text("Clear history?") },
            text = { Text("This removes all wake-up and snooze records. Alarms aren't affected.") },
            confirmButton = { TextButton(onClick = { EventLog.clear(); confirmClear = false }) { Text("Clear", color = T24.AmberLight) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = T24.Muted) } }
        )
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(T24.Surface)
            .border(1.dp, T24.Line, RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Text(title.uppercase(), color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(value, color = T24.Text, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1)
        Text(sub, color = T24.Faint, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun WakeChart(days: List<DayStat>) {
    val measurer = rememberTextMeasurer()
    val wakes = days.mapNotNull { it.wakeMin }
    // Fit the time axis around the data (at least a 3-hour window)
    val lo = ((wakes.minOrNull() ?: 360) - 30).coerceAtLeast(0) / 30 * 30
    val hi = maxOf(((wakes.maxOrNull() ?: 480) + 30 + 29) / 30 * 30, lo + 180).coerceAtMost(1440)
    val labelStyle = TextStyle(color = T24.Faint, fontSize = 10.sp)
    val amber = T24.Amber
    val amberLight = T24.AmberLight
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(T24.Surface)
            .border(1.dp, T24.Line, RoundedCornerShape(20.dp))
    ) {
        val left = 44.dp.toPx()
        val right = 12.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = 40.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun y(m: Int) = top + h * (m - lo).toFloat() / (hi - lo)
        // grid lines every hour
        var g = (lo + 59) / 60 * 60
        while (g <= hi) {
            drawLine(T24.Line.copy(alpha = 0.6f), Offset(left, y(g)), Offset(size.width - right, y(g)), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = measurer.measure(hm(g), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(g) - t.size.height / 2f))
            g += 60
        }
        val step = w / days.size
        days.forEachIndexed { i, d ->
            val x = left + step * (i + 0.5f)
            d.wakeMin?.let { m ->
                drawCircle(amber.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = Offset(x, y(m)))
                drawCircle(amberLight, radius = 4.dp.toPx(), center = Offset(x, y(m)))
            }
            if (d.snoozes > 0) {
                val bh = (d.snoozes.coerceAtMost(5) * 3).dp.toPx()
                drawRoundRect(amber, Offset(x - 3.dp.toPx(), size.height - bottom + 6.dp.toPx()), Size(6.dp.toPx(), bh), CornerRadius(3f))
            }
            if (i % 2 == days.size % 2 || days.size <= 7) {
                val lbl = d.date.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.getDefault())
                val t = measurer.measure(lbl, labelStyle)
                drawText(t, topLeft = Offset(x - t.size.width / 2f, size.height - t.size.height - 4.dp.toPx()))
            }
        }
    }
}
