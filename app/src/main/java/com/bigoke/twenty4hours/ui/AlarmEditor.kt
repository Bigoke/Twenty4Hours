package com.bigoke.twenty4hours.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.bigoke.twenty4hours.ring.RobotVoice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmKind
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.Challenge
import com.bigoke.twenty4hours.data.Holidays
import com.bigoke.twenty4hours.data.RepeatMode
import com.bigoke.twenty4hours.data.Role
import com.bigoke.twenty4hours.data.VoiceStyle
import com.bigoke.twenty4hours.data.formatCountdown
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val QUICK_LABELS = listOf("Wake up", "Go to bed", "Take medication", "Leave home", "Pick up kids", "Workout", "Meeting", "Lunch")
private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditor(
    initial: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (Alarm) -> Unit,
    onSkipToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = T24.Surface,
        contentColor = T24.Text,
    ) {
        AlarmEditorBody(initial, isNew, onSave, onDelete, onDuplicate, onSkipToggle, Modifier.verticalScroll(rememberScrollState()))
    }
}

/** The editor's contents, separate from the sheet so it can be previewed and screenshot-tested. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorBody(
    initial: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (Alarm) -> Unit,
    onSkipToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var kind by remember { mutableStateOf(initial.kind) }
    var label by remember { mutableStateOf(initial.label) }
    var mode by remember { mutableStateOf(initial.repeatMode) }
    var days by remember { mutableStateOf(initial.days) }
    var daysB by remember { mutableStateOf(initial.daysB) }
    var everyN by remember { mutableIntStateOf(initial.everyN) }
    var anchor by remember { mutableLongStateOf(initial.anchorDay) }
    var date by remember {
        mutableLongStateOf(if (initial.dateEpochDay >= 0) initial.dateEpochDay else LocalDate.now().plusDays(1).toEpochDay())
    }
    var skipHolidays by remember { mutableStateOf(initial.skipHolidays) }
    var group by remember { mutableStateOf(initial.group) }
    var role by remember { mutableStateOf(initial.role) }
    var rings by remember { mutableIntStateOf(initial.rings) }
    var interval by remember { mutableIntStateOf(initial.intervalMin) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMin) }
    var soundUri by remember { mutableStateOf(initial.soundUri) }
    var soundName by remember { mutableStateOf(initial.soundName) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var gradual by remember { mutableStateOf(initial.gradual) }
    var challenge by remember { mutableStateOf(initial.challenge) }
    var qr by remember { mutableStateOf(initial.qrCode) }
    var wakeWindow by remember { mutableIntStateOf(initial.wakeWindowMin) }
    var sunrise by remember { mutableStateOf(initial.sunrise) }
    var summary by remember { mutableStateOf(initial.morningSummary) }
    var robot by remember { mutableStateOf(initial.robotVoice) }
    var robotStyle by remember { mutableStateOf(initial.robotStyle) }
    var robotLevel by remember { mutableIntStateOf(initial.robotLevel) }
    // Preview engine for the robot voice, created on first use
    var voice by remember { mutableStateOf<RobotVoice?>(null) }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { voice?.release() } }

    var pickDateFor by remember { mutableStateOf<String?>(null) } // "date" or "anchor"
    var newGroup by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (uri == null || uri == Settings.System.DEFAULT_ALARM_ALERT_URI) {
                soundUri = null; soundName = null
            } else {
                soundUri = uri.toString()
                soundName = runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            }
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { qr = it } }
    val activityPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun build(): Alarm {
        val isRem = kind == AlarmKind.REMINDER
        val scheduleChanged = time.hour != initial.hour || time.minute != initial.minute || days != initial.days ||
            mode != initial.repeatMode
        return initial.copy(
            hour = time.hour, minute = time.minute, label = label.trim(), days = days,
            rings = if (isRem) 1 else rings, intervalMin = interval, snoozeMin = snooze,
            kind = kind, soundUri = soundUri, soundName = soundName, vibrate = vibrate, gradual = gradual,
            challenge = if (isRem || (challenge == Challenge.QR && qr == null)) Challenge.NONE else challenge,
            qrCode = qr,
            enabled = true,
            skipUntil = if (scheduleChanged) 0L else initial.skipUntil,
            repeatMode = mode, everyN = everyN, anchorDay = anchor, daysB = daysB, dateEpochDay = date,
            skipHolidays = skipHolidays, group = group.trim(),
            role = role,
            wakeWindowMin = if (isRem) 0 else wakeWindow,
            sunrise = !isRem && sunrise,
            morningSummary = !isRem && summary,
            robotVoice = !isRem && robot,
            robotStyle = robotStyle,
            robotLevel = robotLevel,
        )
    }

    val preview = build()
    val now = ZonedDateTime.now()
    val nextText = preview.nextTriggerOrNull(now)?.let { "in " + formatCountdown(now, it) } ?: "date has passed"
    val groups = remember { AlarmStore.alarms.value.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
        .let { if (group.isNotBlank() && group !in it) it + group else it }
    val isAlarm = kind == AlarmKind.ALARM

    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) (if (!isAlarm) "New reminder" else "New alarm")
                    else (if (!isAlarm) "Edit reminder" else "Edit alarm"),
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = T24.Text,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(nextText, color = T24.AmberLight, fontSize = 14.sp, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Alarm", isAlarm) { kind = AlarmKind.ALARM }
                Chip("Reminder", !isAlarm) { kind = AlarmKind.REMINDER }
            }
            Text(
                if (!isAlarm) "A single gentle notification sound — no ringing screen."
                else "Rings until you stop it, over the lock screen.",
                color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = T24.SurfaceHigh,
                        clockDialSelectedContentColor = OnAmber,
                        clockDialUnselectedContentColor = T24.Text,
                        selectorColor = T24.Amber,
                        timeSelectorSelectedContainerColor = T24.Amber.copy(alpha = 0.22f),
                        timeSelectorUnselectedContainerColor = T24.SurfaceHigh,
                        timeSelectorSelectedContentColor = T24.AmberLight,
                        timeSelectorUnselectedContentColor = T24.Text,
                    )
                )
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(40) },
                label = { Text("Label") },
                placeholder = { Text(if (!isAlarm) "Take medication" else "Wake up") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(QUICK_LABELS) { q -> Chip(q, label == q) { label = q } }
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            // ---------------------------------------------------------------- repeat
            Spacer(Modifier.height(22.dp))
            SectionTitle("Repeat")
            WrapRow {
                Chip("Days", mode == RepeatMode.WEEKLY) { mode = RepeatMode.WEEKLY }
                Chip("A date", mode == RepeatMode.DATE) { mode = RepeatMode.DATE }
                Chip("Every few days", mode == RepeatMode.EVERY_N) { mode = RepeatMode.EVERY_N }
                Chip("Week A / B", mode == RepeatMode.WEEK_AB) { mode = RepeatMode.WEEK_AB }
            }
            Spacer(Modifier.height(12.dp))
            when (mode) {
                RepeatMode.WEEKLY -> {
                    WrapRow {
                        Chip("Once", days.isEmpty()) { days = emptySet() }
                        Chip("Weekdays", days == setOf(1, 2, 3, 4, 5)) { days = setOf(1, 2, 3, 4, 5) }
                        Chip("Weekends", days == setOf(6, 7)) { days = setOf(6, 7) }
                        Chip("Daily", days.size == 7) { days = (1..7).toSet() }
                    }
                    Spacer(Modifier.height(12.dp))
                    DayCircles(days) { days = it }
                }
                RepeatMode.DATE -> {
                    OptionRow(
                        "On", LocalDate.ofEpochDay(date).format(DATE_FMT),
                        onClick = { pickDateFor = "date" }
                    ) { Text("Change", color = T24.AmberLight, fontSize = 14.sp) }
                }
                RepeatMode.EVERY_N -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", color = T24.Muted, fontSize = 15.sp)
                        Spacer(Modifier.width(12.dp))
                        StepButton("−") { if (everyN > 2) everyN-- }
                        Text(
                            "$everyN", fontSize = 24.sp, fontWeight = FontWeight.Light, color = T24.Text,
                            modifier = Modifier.width(48.dp), textAlign = TextAlign.Center
                        )
                        StepButton("+") { if (everyN < 30) everyN++ }
                        Spacer(Modifier.width(12.dp))
                        Text("days", color = T24.Muted, fontSize = 15.sp)
                    }
                    OptionRow(
                        "Starting", LocalDate.ofEpochDay(anchor).format(DATE_FMT),
                        onClick = { pickDateFor = "anchor" }
                    ) { Text("Change", color = T24.AmberLight, fontSize = 14.sp) }
                }
                RepeatMode.WEEK_AB -> {
                    val thisMonday = LocalDate.now().with(DayOfWeek.MONDAY)
                    val anchorMonday = LocalDate.ofEpochDay(anchor).with(DayOfWeek.MONDAY)
                    val thisWeekIsA = Math.floorMod((thisMonday.toEpochDay() - anchorMonday.toEpochDay()) / 7, 2L) == 0L
                    Text("Week A", color = T24.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    DayCircles(days) { days = it }
                    Spacer(Modifier.height(12.dp))
                    Text("Week B", color = T24.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    DayCircles(daysB) { daysB = it }
                    OptionRow("This week is", if (thisWeekIsA) "Week A" else "Week B", onClick = {
                        // flip which week counts as A
                        anchor = LocalDate.ofEpochDay(anchor).plusWeeks(1).toEpochDay()
                    }) { Text("Swap", color = T24.AmberLight, fontSize = 14.sp) }
                }
            }
            if (mode != RepeatMode.DATE && !(mode == RepeatMode.WEEKLY && days.isEmpty())) {
                val nextHol = Holidays.upcoming(LocalDate.now(), 1).firstOrNull()
                OptionRow(
                    "Skip public holidays",
                    nextHol?.let { "Next: ${it.second}, ${it.first.format(DateTimeFormatter.ofPattern("d MMM"))}" }
                ) { T24Switch(skipHolidays, { skipHolidays = it }) }
            }

            // ---------------------------------------------------------------- group & role
            Spacer(Modifier.height(18.dp))
            SectionTitle("Group")
            WrapRow {
                Chip("None", group.isBlank()) { group = "" }
                groups.forEach { g -> Chip(g, group == g) { group = g } }
                Chip("+ New", false) { newGroup = true }
            }
            Text(
                "Switch a whole group on or off from the Alarms screen — e.g. Work, School run.",
                color = T24.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (isAlarm) {
                OptionRow("Wake-up alarm", "Used for the sleep arc on the dial") {
                    T24Switch(role == Role.WAKE, { role = if (it) Role.WAKE else Role.NONE })
                }
            } else {
                OptionRow("Bedtime", "Starts the night band on the dial") {
                    T24Switch(role == Role.BEDTIME || (role == Role.NONE && label.contains("bed", true)), {
                        role = if (it) Role.BEDTIME else Role.NONE
                    })
                }
            }

            // ---------------------------------------------------------------- alarm-only
            AnimatedVisibility(isAlarm) {
                Column {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Rings")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepButton("−") { if (rings > 1) rings-- }
                        Text(
                            "$rings", fontSize = 26.sp, fontWeight = FontWeight.Light,
                            color = T24.Text, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center
                        )
                        StepButton("+") { if (rings < 10) rings++ }
                        Spacer(Modifier.width(16.dp))
                        Text(if (rings == 1) "ring" else "rings in a row", color = T24.Muted, maxLines = 1)
                    }
                    AnimatedVisibility(rings > 1) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Text("Gap between rings", color = T24.Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            ChoiceRow(listOf(1, 3, 5, 10, 15), interval, { "$it min" }) { interval = it }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Snooze length", color = T24.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(listOf(5, 9, 10, 15, 20), snooze, { "$it min" }) { snooze = it }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Wake up gently")
                    Text(
                        "Smart wake starts very softly before the set time and builds up to it.",
                        color = T24.Muted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(listOf(0, 10, 20, 30), wakeWindow, { if (it == 0) "Off" else "$it min early" }) { wakeWindow = it }
                    OptionRow("Sunrise screen", "The ring screen slowly brightens like dawn") {
                        T24Switch(sunrise, { sunrise = it })
                    }
                    OptionRow("Morning summary", "After you stop it: date, weather and your first event") {
                        T24Switch(summary, { summary = it })
                    }

                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Sound & feel")
                    OptionRow(
                        "Sound", soundName ?: "Default alarm sound",
                        onClick = {
                            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    soundUri?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
                                )
                            }
                            runCatching { picker.launch(i) }
                        }
                    ) { Text("Change", color = T24.AmberLight, fontSize = 14.sp) }
                    OptionRow("Vibrate", "Pulses along with the sound") { T24Switch(vibrate, { vibrate = it }) }
                    OptionRow("Gentle start", "Volume rises slowly") { T24Switch(gradual, { gradual = it }) }
                    OptionRow("Robot voice", "Says the label out loud in a droid voice while it rings") {
                        T24Switch(robot, { robot = it })
                    }
                    AnimatedVisibility(robot) {
                      Column {
                        Text("Style", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                        ChoiceRow(VoiceStyle.values().toList(), robotStyle, { it.title }) { robotStyle = it }
                        Text(
                            when (robotStyle) {
                                VoiceStyle.DROID -> "Small and chirpy, with beeps between words."
                                VoiceStyle.CLASSIC -> "Deep sci-fi robot with a metallic ring."
                                VoiceStyle.VOCODER -> "Smooth synth voice, like electronic music."
                            },
                            color = T24.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                        )
                        Text("Strength", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                        ChoiceRow(listOf(35, 60, 85), robotLevel, {
                            when (it) { 35 -> "Subtle"; 60 -> "Medium"; else -> "Strong" }
                        }) { robotLevel = it }
                        Spacer(Modifier.height(12.dp))
                        PillButton(if (previewing) "Rendering…" else "Hear it", height = 44.dp, enabled = !previewing) {
                            val v = voice ?: RobotVoice(ctx).also { voice = it }
                            previewing = true
                            val text = (label.trim().ifBlank { "Alarm" }) + ". It's ${time.hour}:${"%02d".format(time.minute)}."
                            v.render(text, robotStyle, robotLevel / 100f) { clip ->
                                previewing = false
                                if (clip != null) v.play(clip, alarm = false)
                                else android.widget.Toast.makeText(ctx, "Text-to-speech isn't available on this phone", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                      }
                    }

                    Spacer(Modifier.height(18.dp))
                    SectionTitle("To stop it")
                    ChoiceRow(Challenge.values().toList(), challenge, { it.title }) {
                        challenge = it
                        if (it == Challenge.STEPS && android.os.Build.VERSION.SDK_INT >= 29) {
                            runCatching { activityPerm.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) }
                        }
                    }
                    Text(
                        when (challenge) {
                            Challenge.NONE -> "Just slide the puck."
                            Challenge.MATH -> "Solve a sum first."
                            Challenge.QR -> if (qr == null) "Scan a code you'll have to get up to — a barcode on a box in the kitchen works."
                                else "Code saved. You'll need to scan the same code to stop."
                            Challenge.SHAKE -> "Shake the phone 30 times."
                            Challenge.STEPS -> "Walk 40 steps with the phone."
                        },
                        color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                    if (challenge == Challenge.QR) {
                        Spacer(Modifier.height(10.dp))
                        PillButton(if (qr == null) "Scan the code now" else "Scan a different code", height = 46.dp) {
                            scanner.launch(
                                ScanOptions().setPrompt("Scan the code you'll scan to stop this alarm")
                                    .setBeepEnabled(false).setOrientationLocked(false)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            if (!isNew) {
                WrapRow(Modifier.fillMaxWidth()) {
                    if (initial.enabled) {
                        PillButton(
                            when {
                                initial.isSkipping(now) -> "Don't skip"
                                initial.repeating -> "Skip next"
                                else -> "Turn off"
                            },
                            height = 44.dp, onClick = onSkipToggle
                        )
                    }
                    PillButton("Duplicate", height = 44.dp) { onDuplicate(build()) }
                    PillButton("Delete", height = 44.dp, onClick = onDelete)
                }
                Spacer(Modifier.height(12.dp))
            }
            PillButton("Save", Modifier.fillMaxWidth(), primary = true, height = 56.dp) { onSave(build()) }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------------------------------------------------------------- dialogs
    pickDateFor?.let { which ->
        val start = if (which == "date") date else anchor
        val st = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(start).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDateFor = null },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { ms ->
                        val d = Math.floorDiv(ms, 86_400_000L)
                        if (which == "date") date = d else anchor = d
                    }
                    pickDateFor = null
                }) { Text("OK", color = T24.AmberLight) }
            },
            dismissButton = { TextButton(onClick = { pickDateFor = null }) { Text("Cancel", color = T24.Muted) } },
            colors = DatePickerDefaults.colors(containerColor = T24.Surface),
        ) {
            DatePicker(
                state = st,
                colors = DatePickerDefaults.colors(
                    containerColor = T24.Surface,
                    selectedDayContainerColor = T24.Amber,
                    selectedDayContentColor = OnAmber,
                    todayDateBorderColor = T24.AmberLight,
                    todayContentColor = T24.AmberLight,
                )
            )
        }
    }

    if (newGroup) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGroup = false },
            containerColor = T24.Surface,
            title = { Text("New group") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(20) }, singleLine = true,
                    placeholder = { Text("Work, School run, Gym…") }, colors = fieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) group = text.trim(); newGroup = false }) {
                    Text("Add", color = T24.AmberLight)
                }
            },
            dismissButton = { TextButton(onClick = { newGroup = false }) { Text("Cancel", color = T24.Muted) } }
        )
    }
}

@Composable
private fun DayCircles(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DAY_LETTERS.forEachIndexed { i, d ->
            val day = i + 1
            val sel = day in selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (sel) T24.Amber else T24.SurfaceHigh)
                    .clickable { onChange(if (sel) selected - day else selected + day) },
                contentAlignment = Alignment.Center
            ) {
                Text(d, color = if (sel) OnAmber else T24.Text, fontWeight = FontWeight.Medium, fontSize = 15.dp.fixedSp())
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = T24.Amber,
    unfocusedBorderColor = T24.Line,
    focusedLabelColor = T24.Amber,
    cursorColor = T24.Amber,
)
