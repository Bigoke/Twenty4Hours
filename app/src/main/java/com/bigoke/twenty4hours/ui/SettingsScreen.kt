package com.bigoke.twenty4hours.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bigoke.twenty4hours.BuildConfig
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.Accent
import com.bigoke.twenty4hours.data.Backup
import com.bigoke.twenty4hours.data.CalendarRepo
import com.bigoke.twenty4hours.data.Holidays
import com.bigoke.twenty4hours.data.Places
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.bigoke.twenty4hours.data.VolumeAction
import com.bigoke.twenty4hours.ring.AlarmService

object Perms {
    fun notifications(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun fullScreen(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34)
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        else true

    fun exactAlarms(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        else true

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun allGood(ctx: Context) = notifications(ctx) && fullScreen(ctx) && exactAlarms(ctx) && battery(ctx)
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by AppSettings.flow.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) AppSettings.update { it.copy(calendarOnDial = true) }
        tick++
    }
    val scope = rememberCoroutineScope()
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { withContext(Dispatchers.IO) { Places.refreshFromDevice(ctx) } }
        tick++
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) Toast.makeText(ctx, if (Backup.export(ctx, uri)) "Backup saved" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = Backup.import(ctx, uri)
            if (ok) AlarmScheduler.rescheduleAll(ctx)
            Toast.makeText(ctx, if (ok) "Backup restored" else "That file isn't a Twenty4Hours backup", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick
    val notif = Perms.notifications(ctx)
    val full = Perms.fullScreen(ctx)
    val exact = Perms.exactAlarms(ctx)
    val batt = Perms.battery(ctx)
    val pkg = Uri.parse("package:${ctx.packageName}")

    Box(Modifier.fillMaxSize().background(T24.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp).height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = T24.Text)
                    }
                    Text("Settings", color = T24.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Make alarms reliable")
                    PermRow("Notifications", "Shows the ringing alarm and timers", notif) {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else ctx.open(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                    PermRow("Full-screen alarm", "Wakes the screen over the lock screen", full) {
                        if (Build.VERSION.SDK_INT >= 34)
                            ctx.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                    }
                    PermRow("Exact alarms", "Rings on the minute", exact) {
                        if (Build.VERSION.SDK_INT >= 31)
                            ctx.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                    }
                    PermRow("Battery: unrestricted", "Stops the system killing alarms overnight", batt) {
                        ctx.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                    }
                    PermRow("OnePlus background activity", "App info → Battery → allow background activity", null) {
                        ctx.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("New alarm defaults")
                    OptionRow("Rings in a row", "${s.defaultRings} ${if (s.defaultRings == 1) "ring" else "rings"}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StepButton("−") { AppSettings.update { it.copy(defaultRings = (it.defaultRings - 1).coerceAtLeast(1)) } }
                            Spacer(Modifier.width(8.dp))
                            StepButton("+") { AppSettings.update { it.copy(defaultRings = (it.defaultRings + 1).coerceAtMost(10)) } }
                        }
                    }
                    Text("Gap between rings", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    ChoiceRow(listOf(1, 3, 5, 10, 15), s.defaultInterval, { "$it min" }) { v ->
                        AppSettings.update { it.copy(defaultInterval = v) }
                    }
                    Text("Snooze length", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    ChoiceRow(listOf(5, 9, 10, 15, 20), s.defaultSnooze, { "$it min" }) { v ->
                        AppSettings.update { it.copy(defaultSnooze = v) }
                    }
                    OptionRow("Vibrate", "For new alarms") {
                        T24Switch(s.defaultVibrate, { v -> AppSettings.update { it.copy(defaultVibrate = v) } })
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("While ringing")
                    Text("Gentle start takes", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    ChoiceRow(listOf(5, 10, 20, 30, 60), s.gradualSec, { "${it}s" }) { v ->
                        AppSettings.update { it.copy(gradualSec = v) }
                    }
                    Text("Go quiet after", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    ChoiceRow(listOf(1, 3, 5, 10, 20), s.ringTimeoutMin, { "$it min" }) { v ->
                        AppSettings.update { it.copy(ringTimeoutMin = v) }
                    }
                    Text("Volume buttons", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    ChoiceRow(VolumeAction.values().toList(), s.volumeButtons, {
                        when (it) { VolumeAction.NOTHING -> "Nothing"; VolumeAction.SNOOZE -> "Snooze"; VolumeAction.STOP -> "Stop" }
                    }) { v -> AppSettings.update { it.copy(volumeButtons = v) } }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Upcoming alarm heads-up")
                    Text(
                        "A quiet notification before each alarm, with a button to dismiss it early.",
                        color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ChoiceRow(listOf(0, 15, 30, 60, 120), s.upcomingLeadMin, {
                        when (it) { 0 -> "Off"; 60 -> "1h"; 120 -> "2h"; else -> "${it}m" }
                    }) { v ->
                        AppSettings.update { it.copy(upcomingLeadMin = v) }
                        AlarmScheduler.rescheduleAll(ctx)
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Look")
                    ChoiceRow(Accent.values().toList(), s.accent, { it.title }) { v -> AppSettings.update { it.copy(accent = v) } }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("On the dial")
                    OptionRow("Calendar events", "Today's events as thin marks inside the ring") {
                        T24Switch(s.calendarOnDial && CalendarRepo.hasPermission(ctx), { on ->
                            if (on && !CalendarRepo.hasPermission(ctx)) calLauncher.launch(Manifest.permission.READ_CALENDAR)
                            else AppSettings.update { it.copy(calendarOnDial = on) }
                        })
                    }
                    OptionRow("Sunrise & sunset", "Daylight band and sun markers") {
                        T24Switch(s.sunOnDial, { v -> AppSettings.update { it.copy(sunOnDial = v) } })
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Location")
                    val place = Places.current()
                    OptionRow(
                        "Use my location",
                        when {
                            s.hasPlace -> "For sun times and weather · ${s.placeName.ifBlank { "located" }}"
                            place != null -> "Using your time zone (${place.name}) until location is allowed"
                            else -> "For sun times and weather"
                        }
                    ) {
                        T24Switch(s.useDeviceLocation && Places.hasLocationPermission(ctx), { on ->
                            if (on) {
                                AppSettings.update { it.copy(useDeviceLocation = true) }
                                locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            } else AppSettings.update { it.copy(useDeviceLocation = false, lat = Double.NaN, lon = Double.NaN, placeName = "") }
                        })
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Mornings & holidays")
                    OptionRow("Read the morning summary aloud", "Turn on “Morning summary” on an alarm to get it") {
                        T24Switch(s.readSummaryAloud, { v -> AppSettings.update { it.copy(readSummaryAloud = v) } })
                    }
                    OptionRow("Skip public holidays", "Default for new repeating alarms") {
                        T24Switch(s.skipHolidaysDefault, { v -> AppSettings.update { it.copy(skipHolidaysDefault = v) } })
                    }
                    Text(
                        "Next: " + Holidays.upcoming(LocalDate.now(), 3).joinToString(" · ") {
                            "${it.second} ${it.first.format(DateTimeFormatter.ofPattern("d MMM"))}"
                        },
                        color = T24.Faint, fontSize = 12.sp
                    )

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Backup")
                    Text(
                        "Save alarms, timers, cities, settings and history to a file — handy for a new phone.",
                        color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Export", Modifier.weight(1f)) {
                            exportLauncher.launch("twenty4hours-backup-${LocalDate.now()}.json")
                        }
                        PillButton("Restore", Modifier.weight(1f)) {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Voice & shortcuts")
                    Text(
                        "Say “Hey Google, set an alarm for 6:15” or “set a 10 minute timer” and pick Twenty4Hours. " +
                            "Add the Nap and Focus tiles from your Quick Settings edit screen, and the four home-screen widgets from Widgets.",
                        color = T24.Muted, fontSize = 13.sp
                    )

                    Spacer(Modifier.height(22.dp))
                    SectionTitle("Test")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Ring now", Modifier.weight(1f)) {
                            AlarmService.send(ctx, AlarmService.ACTION_TEST)
                        }
                        PillButton("Ring in 1 min", Modifier.weight(1f)) {
                            val at = System.currentTimeMillis() + 60_000L
                            AlarmScheduler.scheduleRing(ctx, AlarmService.TEST_ID, 0, at, at)
                            Toast.makeText(ctx, "Lock your phone — it rings in 1 minute", Toast.LENGTH_LONG).show()
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Twenty4Hours ${BuildConfig.VERSION_NAME}",
                        color = T24.Faint, fontSize = 12.sp
                    )
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, ok: Boolean?, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when (ok) {
                        true -> Color(0xFF7BC67E)
                        false -> T24.Amber
                        null -> T24.Faint
                    }
                )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = T24.Text)
            Text(sub, fontSize = 13.sp, color = T24.Muted)
        }
        Spacer(Modifier.width(10.dp))
        if (ok == true) {
            Text("Allowed", color = T24.Muted, fontSize = 13.sp)
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (ok == false) T24.Amber else T24.SurfaceHigh)
                    .clickable(onClick = onFix)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    if (ok == false) "Allow" else "Open",
                    color = if (ok == false) OnAmber else T24.Text,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
