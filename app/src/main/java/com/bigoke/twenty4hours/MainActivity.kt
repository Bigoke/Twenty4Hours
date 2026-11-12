package com.bigoke.twenty4hours

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.formatCountdown
import com.bigoke.twenty4hours.ui.AlarmEditor
import com.bigoke.twenty4hours.ui.AlarmRow
import com.bigoke.twenty4hours.ui.Dial
import com.bigoke.twenty4hours.ui.Perms
import com.bigoke.twenty4hours.ui.SetupSheet
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.Twenty4Theme
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmStore.init(this)
        AlarmScheduler.rescheduleAll(this)
        enableEdgeToEdge()
        setContent { Twenty4Theme { HomeScreen() } }
    }
}

private data class EditTarget(val alarm: Alarm, val isNew: Boolean)

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsState()
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showSetup by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notifications(ctx)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!AlarmStore.setupShown && !Perms.allGood(ctx)) {
            delay(1200)
            showSetup = true
        }
        AlarmStore.setupShown = true
    }

    // Next alarm + ordering (recomputed each minute is plenty; `now` ticks each second)
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

    fun save(a: Alarm, isNew: Boolean) {
        val toSave = if (isNew) a.copy(id = AlarmStore.newId()) else a
        AlarmStore.upsert(toSave)
        AlarmScheduler.schedule(ctx, toSave)
        if (toSave.enabled) {
            val n = ZonedDateTime.now()
            Toast.makeText(ctx, "Rings in " + formatCountdown(n, toSave.nextTrigger(n)), Toast.LENGTH_SHORT).show()
        }
    }

    fun newAlarmAt(minuteOfDay: Int) {
        editing = EditTarget(Alarm(0, minuteOfDay / 60, minuteOfDay % 60, ""), isNew = true)
    }

    Box(Modifier.fillMaxSize().background(T24.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Twenty4Hours",
                        color = T24.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSetup = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = T24.Muted)
                    }
                }
            }
            item {
                Dial(
                    alarms = alarms,
                    now = now,
                    next = next,
                    nextAt = nextAt,
                    onMove = { a, m ->
                        save(a.copy(hour = m / 60, minute = m % 60, enabled = true), isNew = false)
                    },
                    onTap = { a -> editing = EditTarget(a, isNew = false) },
                    onAddAt = { m -> newAlarmAt(m) },
                    modifier = Modifier.padding(horizontal = 4.dp)
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
                        Text(
                            "Drag pucks around the ring to move alarms.",
                            color = T24.Muted, fontSize = 14.sp
                        )
                    }
                }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
                items(sorted, key = { it.id }) { a ->
                    AlarmRow(
                        alarm = a,
                        isNext = a.id == next?.id,
                        countdown = triggers[a.id]?.let { formatCountdown(now, it) },
                        onClick = { editing = EditTarget(a, isNew = false) },
                        onToggle = { on ->
                            val u = a.copy(enabled = on)
                            AlarmStore.upsert(u)
                            AlarmScheduler.schedule(ctx, u)
                        }
                    )
                    HorizontalDivider(
                        Modifier.padding(start = 56.dp, end = 24.dp),
                        thickness = 0.5.dp, color = T24.Line.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Add button
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(24.dp)
                .shadow(16.dp, CircleShape, ambientColor = T24.Amber, spotColor = T24.Amber)
                .size(64.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                .clickable {
                    val n = ZonedDateTime.now().plusHours(1)
                    newAlarmAt(n.hour * 60)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add alarm", tint = Color(0xFF1A0E02), modifier = Modifier.size(30.dp))
        }
    }

    editing?.let { target ->
        AlarmEditor(
            initial = target.alarm,
            isNew = target.isNew,
            onSave = { a -> save(a, target.isNew); editing = null },
            onDelete = {
                AlarmScheduler.cancel(ctx, target.alarm.id)
                AlarmStore.delete(target.alarm.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
    if (showSetup) SetupSheet(onDismiss = { showSetup = false })
}
