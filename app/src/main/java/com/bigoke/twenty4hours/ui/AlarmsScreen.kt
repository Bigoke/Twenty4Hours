package com.bigoke.twenty4hours.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.bigoke.twenty4hours.data.CalEvent
import com.bigoke.twenty4hours.data.CalendarRepo
import com.bigoke.twenty4hours.data.Holidays
import com.bigoke.twenty4hours.data.Places
import com.bigoke.twenty4hours.data.Role
import com.bigoke.twenty4hours.data.Sun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmKind
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.formatCountdown
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class EditTarget(val alarm: Alarm, val isNew: Boolean)

@Composable
fun AlarmsScreen(
    now: ZonedDateTime,
    contentPadding: PaddingValues,
    addRequests: Int,
    onOpenSettings: () -> Unit,
    showUndo: (String, () -> Unit) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenTrip: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsState()
    val settings by AppSettings.flow.collectAsState()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var pickingHoliday by remember { mutableStateOf(false) }

    // Calendar events and sun times for the dial (refreshed when the date changes)
    val today = now.toLocalDate()
    val events by produceState(emptyList<CalEvent>(), today, settings.calendarOnDial) {
        value = if (settings.calendarOnDial) withContext(Dispatchers.IO) { CalendarRepo.eventsOn(ctx, today) } else emptyList()
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Places.refreshFromDevice(ctx) } }
    val sun = remember(today, settings.lat, settings.lon, settings.sunOnDial) {
        if (!settings.sunOnDial) null else Places.current()?.let { Sun.times(today, it) }
    }

    fun newAt(minuteOfDay: Int, kind: AlarmKind = AlarmKind.ALARM, label: String = "", days: Set<Int> = emptySet()) {
        val s = AppSettings.value
        editing = EditTarget(
            Alarm(
                0, minuteOfDay / 60, minuteOfDay % 60, label, days = days, kind = kind,
                rings = s.defaultRings, intervalMin = s.defaultInterval, snoozeMin = s.defaultSnooze,
                vibrate = s.defaultVibrate, skipHolidays = s.skipHolidaysDefault,
                role = when {
                    label == "Go to bed" -> Role.BEDTIME
                    label == "Wake up" -> Role.WAKE
                    else -> Role.NONE
                },
            ),
            isNew = true
        )
    }

    var lastAdd by remember { mutableStateOf(addRequests) }
    LaunchedEffect(addRequests) {
        if (addRequests != lastAdd) {
            lastAdd = addRequests
            val n = ZonedDateTime.now().plusHours(1)
            newAt(n.hour * 60)
        }
    }

    val nowMinute = now.withSecond(0).withNano(0)
    val triggers = remember(alarms, nowMinute, settings.pausedUntil, settings.pausedGroups) {
        alarms.filter { it.active }.mapNotNull { a -> a.nextTriggerOrNull(nowMinute)?.let { a.id to it } }.toMap()
    }
    val next = alarms.filter { it.id in triggers }.minByOrNull { triggers[it.id]!! }
    val nextAt = next?.let { triggers[it.id] }
    val sorted = remember(alarms, triggers) {
        alarms.sortedWith(
            compareBy<Alarm>({ !it.active }, { triggers[it.id]?.toEpochSecond() ?: Long.MAX_VALUE }, { it.minuteOfDay })
        )
    }
    val groups = remember(alarms) { alarms.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
    val sleep = remember(alarms, settings.pausedGroups) { sleepPair(alarms) }
    val paused = settings.pausedUntil > System.currentTimeMillis()

    fun save(a: Alarm, isNew: Boolean, toast: Boolean = true) {
        val toSave = if (isNew) a.copy(id = AlarmStore.newId()) else a
        AlarmStore.upsert(toSave)
        AlarmScheduler.schedule(ctx, toSave)
        if (toast && toSave.enabled) {
            val n = ZonedDateTime.now()
            Toast.makeText(
                ctx, (if (toSave.isReminder) "Reminder in " else "Rings in ") + formatCountdown(n, toSave.nextTrigger(n)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun delete(a: Alarm) {
        AlarmScheduler.cancel(ctx, a.id)
        AlarmStore.delete(a.id)
        showUndo("${a.displayLabel} deleted") { save(a, isNew = false, toast = false) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            ScreenHeader("Twenty4Hours") {
                IconButton(onClick = onOpenStats) { GlyphIcon(Glyph.CHART, T24.Muted, 22.dp) }
                IconButton(onClick = onOpenSettings) { GlyphIcon(Glyph.GEAR, T24.Muted, 22.dp) }
            }
        }
        item {
            val on = alarms.count { it.active }
            val holiday = Holidays.nameOf(today)
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
                Text(
                    now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) +
                        (holiday?.let { " · $it" } ?: "") +
                        if (alarms.isEmpty()) "" else " · $on of ${alarms.size} on",
                    color = T24.Muted, fontSize = 14.sp
                )
                val extras = listOfNotNull(
                    sun?.let { "Sun %02d:%02d–%02d:%02d".format(it.first / 60, it.first % 60, it.second / 60, it.second % 60) },
                    sleep?.let { (b, w) -> val m = sleepMinutes(b, w); "Sleep window ${m / 60}h" + if (m % 60 > 0) " ${m % 60}m" else "" },
                )
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), color = T24.Faint, fontSize = 13.sp)
            }
        }
        if (paused) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp)).background(T24.SurfaceHigh).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Holiday mode", color = T24.AmberLight, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "No alarms until " + java.time.Instant.ofEpochMilli(settings.pausedUntil).atZone(now.zone)
                                .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())),
                            color = T24.Muted, fontSize = 13.sp
                        )
                    }
                    Chip("Resume", false) {
                        AppSettings.update { it.copy(pausedUntil = 0L) }
                        AlarmScheduler.rescheduleAll(ctx)
                    }
                }
            }
        }
        item {
            Dial(
                alarms = alarms,
                now = now,
                next = next,
                nextAt = nextAt,
                onMove = { a, m -> save(a.copy(hour = m / 60, minute = m % 60, enabled = true, skipUntil = 0L), isNew = false) },
                onTap = { a -> editing = EditTarget(a, isNew = false) },
                onAddAt = { m -> newAt(m) },
                modifier = Modifier.padding(horizontal = 4.dp),
                events = events,
                sun = sun,
            )
        }
        if (groups.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    items(groups) { g ->
                        val on = g !in settings.pausedGroups
                        Chip(if (on) "$g · on" else "$g · paused", on) {
                            AppSettings.update { st ->
                                st.copy(pausedGroups = if (on) st.pausedGroups + g else st.pausedGroups - g)
                            }
                            AlarmScheduler.rescheduleAll(ctx)
                        }
                    }
                }
            }
        }
        item {
            QuickAdd(
                onNap = { mins ->
                    val t = ZonedDateTime.now().plusMinutes(mins.toLong())
                    save(
                        Alarm(0, t.hour, t.minute, "Nap", snoozeMin = AppSettings.value.defaultSnooze), isNew = true
                    )
                },
                onBedtime = { newAt(23 * 60, AlarmKind.REMINDER, "Go to bed", (1..7).toSet()) },
                onWake = { newAt(7 * 60, AlarmKind.ALARM, "Wake up", setOf(1, 2, 3, 4, 5)) },
                onReminder = {
                    val n = ZonedDateTime.now().plusHours(1)
                    newAt(n.hour * 60, AlarmKind.REMINDER)
                },
                onTrip = onOpenTrip,
                onHoliday = { pickingHoliday = true },
            )
        }
        if (alarms.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Your day is empty", color = T24.Text, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Hold the ring or tap + to add an alarm.", color = T24.Muted, fontSize = 14.sp)
                }
            }
        } else {
            item { Spacer(Modifier.height(4.dp)) }
            items(sorted, key = { it.id }) { a ->
                SwipeRow(onDelete = { delete(a) }, modifier = Modifier.animateItem()) {
                    AlarmRow(
                        alarm = a,
                        now = now,
                        isNext = a.id == next?.id,
                        countdown = triggers[a.id]?.let { formatCountdown(now, it) },
                        onClick = { editing = EditTarget(a, isNew = false) },
                        onToggle = { on -> save(a.copy(enabled = on, skipUntil = 0L), isNew = false, toast = on) }
                    )
                }
            }
            item {
                Text(
                    "Swipe left to delete · drag pucks on the dial to move",
                    color = T24.Faint, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    editing?.let { target ->
        AlarmEditor(
            initial = target.alarm,
            isNew = target.isNew,
            onSave = { a -> save(a, target.isNew); editing = null },
            onDelete = { delete(target.alarm); editing = null },
            onDuplicate = { a -> save(a.copy(label = a.label), isNew = true); editing = null },
            onSkipToggle = {
                val cur = AlarmStore.get(target.alarm.id)
                if (cur != null) {
                    if (cur.isSkipping(ZonedDateTime.now())) AlarmScheduler.clearSkip(ctx, cur.id)
                    else AlarmScheduler.skipNext(ctx, cur.id)
                }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (pickingHoliday) HolidayModeDialog(
        onDismiss = { pickingHoliday = false },
        onPick = { untilMs ->
            AppSettings.update { it.copy(pausedUntil = untilMs) }
            AlarmScheduler.rescheduleAll(ctx)
            pickingHoliday = false
        }
    )
}

/** Pick the last day off; alarms resume the morning after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidayModeDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val st = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = java.time.LocalDate.now().plusDays(6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                st.selectedDateMillis?.let { ms ->
                    val last = java.time.LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L))
                    onPick(last.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                }
            }) { Text("Pause alarms", color = T24.AmberLight) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = T24.Muted) } },
        colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = T24.Surface),
    ) {
        androidx.compose.material3.DatePicker(
            state = st,
            title = { Text("Last day off", modifier = Modifier.padding(start = 24.dp, top = 16.dp), color = T24.Muted) },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = T24.Surface, selectedDayContainerColor = T24.Amber, selectedDayContentColor = OnAmber,
                todayDateBorderColor = T24.AmberLight, todayContentColor = T24.AmberLight,
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val del = androidx.compose.runtime.rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) { del.value(); true } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(T24.Bg, T24.AmberDark.copy(alpha = 0.55f))))
                    .padding(end = 28.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Text("Delete", color = T24.CreamLight, fontWeight = FontWeight.Medium) }
        }
    ) {
        Box(Modifier.background(T24.Bg)) { content() }
    }
}

@Composable
private fun QuickAdd(
    onNap: (Int) -> Unit, onBedtime: () -> Unit, onWake: () -> Unit, onReminder: () -> Unit,
    onTrip: () -> Unit, onHoliday: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    ) {
        item { Chip("Nap 20m", false) { onNap(20) } }
        item { Chip("Nap 45m", false) { onNap(45) } }
        item { Chip("Nap 90m", false) { onNap(90) } }
        item { Chip("Wake up", false, onClick = onWake) }
        item { Chip("Bedtime", false, onClick = onBedtime) }
        item { Chip("Reminder", false, onClick = onReminder) }
        item { Chip("On the way", false, onClick = onTrip) }
        item { Chip("Holiday mode", false, onClick = onHoliday) }
    }
}

@Composable
fun AlarmRow(
    alarm: Alarm,
    now: ZonedDateTime,
    isNext: Boolean,
    countdown: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val on = alarm.enabled
    val skipping = on && alarm.isSkipping(now)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotBrush = when {
                !on -> Brush.linearGradient(listOf(T24.Faint, T24.Faint))
                isNext -> Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber))
                else -> Brush.verticalGradient(listOf(T24.CreamLight, T24.CreamDark))
            }
            Box(
                Modifier
                    .size(width = if (alarm.isReminder) 12.dp else 16.dp, height = 12.dp)
                    .clip(if (alarm.isReminder) CircleShape else RoundedCornerShape(6.dp))
                    .background(dotBrush)
            )
            Spacer(Modifier.width(18.dp))
            Text(
                alarm.timeText,
                color = if (on) T24.Text else T24.Faint,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.widthIn(min = 72.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    alarm.displayLabel,
                    color = if (on) T24.Text else T24.Faint,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        on && !alarm.active -> "${alarm.group} paused · " + alarm.daysText()
                        skipping -> "Skipping next · " + alarm.daysText()
                        else -> alarm.repeatText()
                    },
                    color = if (skipping) T24.AmberLight.copy(alpha = 0.8f) else T24.Muted,
                    fontSize = 13.sp, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (on && countdown != null) countdown else "Off",
                    color = if (on) T24.Muted else T24.Faint,
                    fontSize = 13.sp
                )
                T24Switch(on, onToggle)
            }
        }
        HorizontalDivider(
            Modifier.padding(start = 58.dp, end = 24.dp),
            thickness = 0.5.dp, color = T24.Line.copy(alpha = 0.5f)
        )
    }
}
