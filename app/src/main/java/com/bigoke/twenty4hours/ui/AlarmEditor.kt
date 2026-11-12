package com.bigoke.twenty4hours.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.Alarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditor(
    initial: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableStateOf(initial.days) }
    var rings by remember { mutableIntStateOf(initial.rings) }
    var interval by remember { mutableIntStateOf(initial.intervalMin) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = T24.Surface,
        contentColor = T24.Text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                if (isNew) "New alarm" else "Edit alarm",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = T24.Text
            )
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = T24.SurfaceHigh,
                        clockDialSelectedContentColor = Color(0xFF1A0E02),
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
                placeholder = { Text("Wake up") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = T24.Amber,
                    unfocusedBorderColor = T24.Line,
                    focusedLabelColor = T24.Amber,
                    cursorColor = T24.Amber,
                )
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("Repeat")
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
                        Text(d, color = if (sel) Color(0xFF1A0E02) else T24.Text, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Text(
                if (days.isEmpty()) "Rings once" else Alarm(0, 0, 0, "", days = days).repeatText(),
                color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("Rings")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−") { if (rings > 1) rings-- }
                Text(
                    "$rings", fontSize = 26.sp, fontWeight = FontWeight.Light,
                    color = T24.Text, modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                StepButton("+") { if (rings < 10) rings++ }
                Spacer(Modifier.width(16.dp))
                Text(if (rings == 1) "ring" else "rings in a row", color = T24.Muted)
            }
            if (rings > 1) {
                Spacer(Modifier.height(14.dp))
                Text("Every", color = T24.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10, 15).forEach { m ->
                        Chip("$m min", interval == m) { interval = m }
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text("Snooze length", color = T24.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { m ->
                        Chip("$m min", interval == m) { interval = m }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, T24.Line, RoundedCornerShape(20.dp))
                            .clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center
                    ) { Text("Delete", color = T24.Muted, fontSize = 16.sp) }
                }
                Box(
                    Modifier
                        .weight(2f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                        .clickable {
                            onSave(
                                initial.copy(
                                    hour = time.hour,
                                    minute = time.minute,
                                    label = label.trim(),
                                    days = days,
                                    rings = rings,
                                    intervalMin = interval,
                                    enabled = true,
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Save", color = Color(0xFF1A0E02), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t.uppercase(), color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun StepButton(t: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(T24.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, color = T24.Cream, fontSize = 22.sp) }
}

@Composable
private fun Chip(t: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) T24.Amber else T24.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(t, color = if (selected) Color(0xFF1A0E02) else T24.Text, fontSize = 14.sp)
    }
}
