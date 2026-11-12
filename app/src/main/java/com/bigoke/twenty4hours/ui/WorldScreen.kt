package com.bigoke.twenty4hours.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.WorldStore
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val REGIONS = setOf("Africa", "America", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific", "Antarctica")

private val ALL_ZONES: List<String> by lazy {
    ZoneId.getAvailableZoneIds()
        .filter { id -> id.contains('/') && id.substringBefore('/') in REGIONS }
        .sortedBy { cityOf(it) }
}

fun cityOf(zone: String) = zone.substringAfterLast('/').replace('_', ' ')
fun regionOf(zone: String) = zone.substringBeforeLast('/').replace('/', ' · ').replace('_', ' ')

@Composable
fun WorldScreen(now: ZonedDateTime, contentPadding: PaddingValues, addRequests: Int) {
    val zones by WorldStore.zones.collectAsState()
    var shiftHours by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var lastAddRequest by remember { mutableStateOf(addRequests) }
    LaunchedEffect(addRequests) {
        if (addRequests != lastAddRequest) { lastAddRequest = addRequests; adding = true }
    }

    val shift = (shiftHours * 2).roundToInt() / 2f
    val shown = now.plusMinutes((shift * 60).toLong())
    val local = ZoneId.systemDefault()

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding) {
        item { ScreenHeader("World") }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(cityOf(local.id), color = T24.Muted, fontSize = 15.sp)
                Text(
                    "%02d:%02d".format(shown.hour, shown.minute),
                    color = T24.Text, fontSize = 64.sp, fontWeight = FontWeight.Light
                )
                Text(
                    shown.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                    color = T24.Muted, fontSize = 15.sp
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Time travel", color = T24.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    Text(
                        if (shift == 0f) "now" else (if (shift > 0) "+" else "−") + fmtHours(abs(shift)),
                        color = if (shift == 0f) T24.Muted else T24.AmberLight, fontSize = 14.sp,
                        modifier = Modifier.clickable { shiftHours = 0f }.padding(4.dp)
                    )
                }
                Slider(
                    value = shiftHours,
                    onValueChange = { shiftHours = it },
                    valueRange = -12f..12f,
                    colors = SliderDefaults.colors(
                        thumbColor = T24.CreamLight,
                        activeTrackColor = T24.Amber,
                        inactiveTrackColor = T24.SurfaceHigh,
                    )
                )
                Text("Slide to plan a call across time zones. Tap the value to reset.",
                    color = T24.Faint, fontSize = 12.sp)
            }
        }
        if (zones.isEmpty()) {
            item {
                Text(
                    "Add cities with the + button.",
                    color = T24.Muted, fontSize = 15.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        items(zones.filter { z -> runCatching { ZoneId.of(z) }.isSuccess }, key = { it }) { z ->
            val t = shown.withZoneSameInstant(ZoneId.of(z))
            Column {
                CityRow(z, t, shown) { expanded = if (expanded == z) null else z }
                AnimatedVisibility(expanded == z) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 92.dp, end = 24.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val i = zones.indexOf(z)
                        if (i > 0) Chip("Move up", false) { WorldStore.move(i, i - 1) }
                        if (i < zones.size - 1) Chip("Move down", false) { WorldStore.move(i, i + 1) }
                        Chip("Remove", false) { WorldStore.remove(z); expanded = null }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(start = 92.dp, end = 24.dp),
                    thickness = 0.5.dp, color = T24.Line.copy(alpha = 0.5f)
                )
            }
        }
    }

    if (adding) AddCitySheet(now, onPick = { WorldStore.add(it); adding = false }, onDismiss = { adding = false })
}

private fun fmtHours(h: Float): String {
    val whole = h.toInt()
    val half = (h - whole) >= 0.5f
    return if (half) "${whole}h30" else "${whole}h"
}

@Composable
private fun CityRow(zone: String, t: ZonedDateTime, ref: ZonedDateTime, onClick: () -> Unit) {
    val diffMin = Duration.ofSeconds((t.offset.totalSeconds - ref.offset.totalSeconds).toLong()).toMinutes()
    val diffText = when {
        diffMin == 0L -> "Same time"
        else -> (if (diffMin > 0) "+" else "−") + (abs(diffMin) / 60).toString() + "h" +
            (if (abs(diffMin) % 60 != 0L) " ${abs(diffMin) % 60}m" else "")
    }
    val dayDiff = t.toLocalDate().toEpochDay() - ref.toLocalDate().toEpochDay()
    val dayText = when (dayDiff) { 0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"; else -> "" }
    val isDay = t.hour in 6..17

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniDayDial(t.hour * 60 + t.minute, Modifier.size(52.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(cityOf(zone), color = T24.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("$dayText · $diffText", color = T24.Muted, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "%02d:%02d".format(t.hour, t.minute),
                color = if (isDay) T24.Text else T24.Text.copy(alpha = 0.75f),
                fontSize = 30.sp, fontWeight = FontWeight.Light
            )
            Text(
                t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " · " + (if (isDay) "day" else "night"),
                color = T24.Faint, fontSize = 11.sp
            )
        }
    }
}

/** A tiny 24h dial: daylight band 06–18 and a knob at the local time. */
@Composable
fun MiniDayDial(minuteOfDay: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.38f
        val w = size.minDimension * 0.13f
        drawCircle(T24.Track, radius = r, center = c, style = Stroke(w))
        drawArc(
            T24.CreamDark.copy(alpha = 0.22f), 90f - 90f, 180f, false,
            Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Butt)
        )
        val deg = minuteOfDay / 1440f * 360f
        val day = minuteOfDay in 360 until 1080
        drawKnob(pointOn(c, r, deg), w * 0.62f, amber = day)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCitySheet(now: ZonedDateTime, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    val results = remember(q) {
        val s = q.trim().lowercase()
        (if (s.isEmpty()) ALL_ZONES else ALL_ZONES.filter {
            cityOf(it).lowercase().contains(s) || it.lowercase().contains(s)
        }).take(120)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = T24.Surface, contentColor = T24.Text) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            Text("Add a city", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search, e.g. Lisbon") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = T24.Amber, unfocusedBorderColor = T24.Line, cursorColor = T24.Amber
                )
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(results, key = { it }) { z ->
                    val t = runCatching { now.withZoneSameInstant(ZoneId.of(z)) }.getOrNull()
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(z) }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cityOf(z), color = T24.Text, fontSize = 16.sp)
                            Text(regionOf(z), color = T24.Muted, fontSize = 12.sp)
                        }
                        if (t != null) Text("%02d:%02d".format(t.hour, t.minute), color = T24.Muted, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
