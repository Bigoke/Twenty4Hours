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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmKind
import com.bigoke.twenty4hours.data.Challenge
import com.bigoke.twenty4hours.data.formatCountdown
import java.time.ZonedDateTime

private val QUICK_LABELS = listOf("Wake up", "Go to bed", "Take medication", "Leave home", "Pick up kids", "Workout", "Meeting", "Lunch")

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
    var days by remember { mutableStateOf(initial.days) }
    var rings by remember { mutableIntStateOf(initial.rings) }
    var interval by remember { mutableIntStateOf(initial.intervalMin) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMin) }
    var soundUri by remember { mutableStateOf(initial.soundUri) }
    var soundName by remember { mutableStateOf(initial.soundName) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var gradual by remember { mutableStateOf(initial.gradual) }
    var challenge by remember { mutableStateOf(initial.challenge) }

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

    fun build(): Alarm = initial.copy(
        hour = time.hour, minute = time.minute, label = label.trim(), days = days,
        rings = if (kind == AlarmKind.REMINDER) 1 else rings, intervalMin = interval, snoozeMin = snooze,
        kind = kind, soundUri = soundUri, soundName = soundName, vibrate = vibrate, gradual = gradual,
        challenge = if (kind == AlarmKind.REMINDER) Challenge.NONE else challenge,
        enabled = true,
        skipUntil = if (time.hour != initial.hour || time.minute != initial.minute || days != initial.days) 0L else initial.skipUntil,
    )

    val preview = build()
    val now = ZonedDateTime.now()

    run {
        Column(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isNew) (if (kind == AlarmKind.REMINDER) "New reminder" else "New alarm")
                        else (if (kind == AlarmKind.REMINDER) "Edit reminder" else "Edit alarm"),
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = T24.Text,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        "in " + formatCountdown(now, preview.nextTrigger(now)),
                        color = T24.AmberLight, fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Alarm", kind == AlarmKind.ALARM) { kind = AlarmKind.ALARM }
                    Chip("Reminder", kind == AlarmKind.REMINDER) { kind = AlarmKind.REMINDER }
                }
                Text(
                    if (kind == AlarmKind.REMINDER) "A single gentle notification sound — no ringing screen."
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
                    placeholder = { Text(if (kind == AlarmKind.REMINDER) "Take medication" else "Wake up") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T24.Amber,
                        unfocusedBorderColor = T24.Line,
                        focusedLabelColor = T24.Amber,
                        cursorColor = T24.Amber,
                    )
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
                Spacer(Modifier.height(22.dp))
                SectionTitle("Repeat")
                WrapRow {
                    Chip("Once", days.isEmpty()) { days = emptySet() }
                    Chip("Weekdays", days == setOf(1, 2, 3, 4, 5)) { days = setOf(1, 2, 3, 4, 5) }
                    Chip("Weekends", days == setOf(6, 7)) { days = setOf(6, 7) }
                    Chip("Daily", days.size == 7) { days = (1..7).toSet() }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                        val day = i + 1
                        val sel = day in days
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (sel) T24.Amber else T24.SurfaceHigh)
                                .clickable { days = if (sel) days - day else days + day },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(d, color = if (sel) OnAmber else T24.Text, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                AnimatedVisibility(kind == AlarmKind.ALARM) {
                    Column {
                        Spacer(Modifier.height(22.dp))
                        SectionTitle("Rings")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StepButton("−") { if (rings > 1) rings-- }
                            Text(
                                "$rings", fontSize = 26.sp, fontWeight = FontWeight.Light,
                                color = T24.Text, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center
                            )
                            StepButton("+") { if (rings < 10) rings++ }
                            Spacer(Modifier.width(16.dp))
                            Text(if (rings == 1) "ring" else "rings in a row", color = T24.Muted)
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
                        OptionRow(
                            "Math to stop",
                            "Solve a sum before the alarm stops — keeps you awake"
                        ) {
                            T24Switch(challenge == Challenge.MATH, {
                                challenge = if (it) Challenge.MATH else Challenge.NONE
                            })
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
    }
}
