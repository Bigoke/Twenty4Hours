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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.bigoke.twenty4hours.alarm.AlarmScheduler
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // read `tick` so these recompute on resume
    val t = tick
    val notif = Perms.notifications(ctx) || t < 0
    val full = Perms.fullScreen(ctx)
    val exact = Perms.exactAlarms(ctx)
    val batt = Perms.battery(ctx)
    val pkg = Uri.parse("package:${ctx.packageName}")

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
            Text("Make alarms reliable", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Android and OxygenOS can delay or silence alarms unless these are allowed.",
                color = T24.Muted, fontSize = 14.sp
            )
            Spacer(Modifier.height(18.dp))

            PermRow("Notifications", "Shows the ringing alarm", notif) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            PermRow("Full-screen alarm", "Wakes the screen over the lock screen", full) {
                if (Build.VERSION.SDK_INT >= 34)
                    ctx.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
            PermRow("Exact alarms", "Rings on the minute, not \"around\" it", exact) {
                if (Build.VERSION.SDK_INT >= 31)
                    ctx.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
            PermRow("Battery: unrestricted", "Stops the system killing alarms overnight", batt) {
                ctx.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
            PermRow(
                "OnePlus background activity",
                "App info → Battery → allow background activity / auto-launch",
                null
            ) {
                ctx.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }

            Spacer(Modifier.height(22.dp))
            Text("TEST", color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
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
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, ok: Boolean?, onFix: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
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
                    color = if (ok == false) Color(0xFF1A0E02) else T24.Text,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PillButton(t: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, T24.Line, RoundedCornerShape(18.dp))
            .background(T24.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, color = T24.Cream, fontSize = 15.sp) }
}
