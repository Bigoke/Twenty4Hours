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
) {
    val ctx = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsState()
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    fun newAt(minuteOfDay: Int, kind: AlarmKind = AlarmKind.ALARM, label: String = "", days: Set<Int> = emptySet()) {
        val s = AppSettings.value
        editing = EditTarget(
            Alarm(
                0, minuteOfDay / 60, minuteOfDay % 60, label, days = days, kind = kind,
                rings = s.defaultRings, intervalMin = s.defaultInterval, snoozeMin = s.defaultSnooze,
                vibrate = s.defaultVibrate,
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
    val triggers = remember(alarms, nowMinute) {
        alarms.filter { it.enabled }.associate { it.id to it.nextTrigger(nowMinute) }
    }
    val next = alarms.filter { it.enabled }.minByOrNull { triggers[it.id]!! }
    val nextAt = next?.let { triggers[it.id] }
    val sorted = remember(alarms, triggers) {
        alarms.sortedWith(
            compareBy<Alarm>({ !it.enabled }, { triggers[it.id]?.toEpochSecond() ?: Long.MAX_VALUE }, { it.minuteOfDay })
        )
    }

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
                IconButton(onClick = onOpenSettings) { GlyphIcon(Glyph.GEAR, T24.Muted, 22.dp) }
            }
        }
        item {
            val on = alarms.count { it.enabled }
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) +
                    if (alarms.isEmpty()) "" else " · $on of ${alarms.size} on",
                color = T24.Muted, fontSize = 14.sp,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
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
                modifier = Modifier.padding(horizontal = 4.dp)
            )
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
private fun QuickAdd(onNap: (Int) -> Unit, onBedtime: () -> Unit, onWake: () -> Unit, onReminder: () -> Unit) {
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
                    if (skipping) "Skipping next · " + alarm.daysText() else alarm.repeatText(),
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
