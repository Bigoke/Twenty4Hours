package com.bigoke.twenty4hours.ring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.CalEvent
import com.bigoke.twenty4hours.data.CalendarRepo
import com.bigoke.twenty4hours.data.Holidays
import com.bigoke.twenty4hours.data.Places
import com.bigoke.twenty4hours.data.Weather
import com.bigoke.twenty4hours.data.WeatherRepo
import com.bigoke.twenty4hours.ui.OnAmber
import com.bigoke.twenty4hours.ui.PillButton
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.drawFace
import com.bigoke.twenty4hours.ui.drawGlowArc
import com.bigoke.twenty4hours.ui.drawKnob
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/** Barcode scanner that also shows over the lock screen (for the "scan to stop" challenge). */
class LockScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        super.onCreate(savedInstanceState)
    }
}

/** Shared frame for the move-your-body challenges: title, big progress ring, count, hint. */
@Composable
private fun ProgressChallenge(title: String, count: Int, goal: Int, hint: String, onCancel: () -> Unit) {
    val frac by animateFloatAsState((count.toFloat() / goal).coerceIn(0f, 1f), label = "p")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text(title, color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension * 0.38f
                val tw = size.minDimension * 0.08f
                drawFace(c, r, tw, ticks = goal.coerceAtMost(60), majorEvery = 5)
                drawGlowArc(c, r + tw * 0.1f, 0f, frac * 360f, core = 5.dp)
                drawKnob(pointOnRing(c, r + tw * 0.1f, frac * 360f), tw * 0.42f, amber = true)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(goal - count).coerceAtLeast(0)}", color = T24.Text, fontSize = 64.sp, fontWeight = FontWeight.Light)
                Text("to go", color = T24.Muted, fontSize = 15.sp)
            }
        }
        Text(hint, color = T24.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Back", color = T24.Muted, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(10.dp)
        )
    }
}

private fun pointOnRing(c: Offset, r: Float, deg: Float) = com.bigoke.twenty4hours.ui.pointOn(c, r, deg)

@Composable
private fun rememberSensorCount(sensorType: Int, onEvent: (FloatArray, Long) -> Boolean): Int {
    val ctx = LocalContext.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(sensorType) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(sensorType)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { if (onEvent(e.values, e.timestamp)) count++ }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    return count
}

@Composable
internal fun ShakeChallenge(onDone: () -> Unit, onCancel: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val goal = 30
    val last = remember { longArrayOf(0L) }
    val count = rememberSensorCount(Sensor.TYPE_ACCELEROMETER) { v, ts ->
        val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) - SensorManager.GRAVITY_EARTH
        if (g > 11f && ts - last[0] > 220_000_000L) { last[0] = ts; true } else false
    }
    LaunchedEffect(count) {
        if (count > 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (count >= goal) onDone()
    }
    ProgressChallenge("SHAKE TO STOP", count, goal, "Shake the phone hard, like you mean it.", onCancel)
}

@Composable
internal fun StepsChallenge(onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val goal = 40
    val hasDetector = remember {
        val perm = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        perm && (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }
    val last = remember { longArrayOf(0L) }
    val above = remember { booleanArrayOf(false) }
    val count = if (hasDetector) rememberSensorCount(Sensor.TYPE_STEP_DETECTOR) { _, _ -> true }
    else rememberSensorCount(Sensor.TYPE_ACCELEROMETER) { v, ts ->
        // simple peak detector when the step sensor isn't available
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val wasAbove = above[0]
        above[0] = m > 11.8f
        if (above[0] && !wasAbove && ts - last[0] > 300_000_000L) { last[0] = ts; true } else false
    }
    LaunchedEffect(count) { if (count >= goal) onDone() }
    ProgressChallenge("WALK TO STOP", count, goal, "Get up and walk around with the phone in your hand.", onCancel)
}

@Composable
internal fun QrChallenge(expected: String?, onDone: () -> Unit, onCancel: () -> Unit, onFallback: () -> Unit) {
    var message by remember { mutableStateOf("Scan the code you saved for this alarm.") }
    var canFallback by remember { mutableStateOf(expected == null) }
    LaunchedEffect(Unit) { delay(90_000); canFallback = true }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val got = r.contents
        when {
            got == null -> message = "No code scanned. Try again."
            expected == null || got == expected -> onDone()
            else -> message = "That's a different code — find the one you saved."
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("SCAN TO STOP", color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp)) {
                val s = size.minDimension
                val corner = s * 0.22f
                val w = 6.dp.toPx()
                listOf(
                    Offset(0f, 0f) to Offset(1f, 1f), Offset(s, 0f) to Offset(-1f, 1f),
                    Offset(0f, s) to Offset(1f, -1f), Offset(s, s) to Offset(-1f, -1f),
                ).forEach { (o, d) ->
                    drawLine(T24.Amber, o, Offset(o.x + d.x * corner, o.y), w)
                    drawLine(T24.Amber, o, Offset(o.x, o.y + d.y * corner), w)
                }
                drawKnob(Offset(s / 2, s / 2), s * 0.12f, amber = true)
            }
        }
        Text(message, color = T24.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        PillButton("Open scanner", Modifier.fillMaxWidth(), primary = true, height = 58.dp) {
            scan.launch(
                ScanOptions().setCaptureActivity(LockScanActivity::class.java).setBeepEnabled(false)
                    .setOrientationLocked(false).setPrompt("Scan your stop code")
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Back", color = T24.Muted, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(10.dp)
            )
            if (canFallback) {
                Text(
                    "Can't find it? Solve a sum", color = T24.AmberLight, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onFallback).padding(10.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ morning summary

private fun greeting(h: Int) = when (h) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

@Composable
internal fun MorningSummary(info: RingInfo, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val now = LocalTime.now()
    var weather by remember { mutableStateOf<Weather?>(null) }
    var weatherTried by remember { mutableStateOf(false) }
    val place = remember { Places.current() }
    val events = remember { CalendarRepo.eventsOn(ctx, today) }
    val holiday = remember { Holidays.nameOf(today) }
    val laterToday = remember {
        val zNow = ZonedDateTime.now()
        AlarmStore.alarms.value.filter { it.active && it.id != info.id }
            .mapNotNull { a -> a.nextTriggerOrNull(zNow)?.takeIf { it.toLocalDate() == today }?.let { a to it } }
            .sortedBy { it.second }.take(3)
    }
    LaunchedEffect(place) {
        if (place != null) weather = withContext(Dispatchers.IO) { WeatherRepo.fetch(place) }
        weatherTried = true
    }

    val firstEvent = events.firstOrNull { !it.allDay && it.startMin >= now.hour * 60 } ?: events.firstOrNull()
    val speech = buildString {
        append(greeting(now.hour)).append(". It's ")
        append(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))).append(". ")
        holiday?.let { append("It's $it. ") }
        weather?.let { append("${it.description}, ${it.tempC} degrees, going up to ${it.highC}. ") }
        if (weather != null && weather!!.rainChance >= 40) append("${weather!!.rainChance} percent chance of rain. ")
        firstEvent?.let { append("First up: ${it.title}" + if (!it.allDay) " at ${fmtMin(it.startMin)}. " else ". ") }
        if (laterToday.isNotEmpty()) append("Next alarm: ${laterToday.first().first.displayLabel} at ${laterToday.first().first.timeText}.")
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) tts = engine }
        onDispose { engine?.stop(); engine?.shutdown() }
    }
    var spoken by remember { mutableStateOf(false) }
    LaunchedEffect(tts, weatherTried) {
        if (!spoken && tts != null && weatherTried && AppSettings.value.readSummaryAloud) {
            spoken = true
            tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "summary")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        Text(greeting(now.hour).uppercase(), color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
        Text(
            today.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())),
            color = T24.Text, fontSize = 40.sp, fontWeight = FontWeight.Light
        )
        Text(
            today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())) +
                (holiday?.let { " · $it" } ?: ""),
            color = if (holiday != null) T24.AmberLight else T24.Muted, fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))

        SummaryCard {
            Text("WEATHER" + (place?.name?.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" } ?: ""),
                color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            val w = weather
            when {
                w != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${w.tempC}°", color = T24.Text, fontSize = 44.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(w.description, color = T24.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("High ${w.highC}° · Low ${w.lowC}° · Rain ${w.rainChance}%", color = T24.Muted, fontSize = 14.sp)
                        }
                    }
                }
                place == null -> Text("Set your location in Settings to see the weather.", color = T24.Muted, fontSize = 14.sp)
                weatherTried -> Text("Couldn't load the weather right now.", color = T24.Muted, fontSize = 14.sp)
                else -> Text("Loading…", color = T24.Muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        SummaryCard {
            Text("TODAY", color = T24.Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            if (events.isEmpty() && laterToday.isEmpty()) {
                Text(
                    if (CalendarRepo.hasPermission(ctx)) "Nothing scheduled. Enjoy it." else "No alarms left today.",
                    color = T24.Muted, fontSize = 15.sp
                )
            }
            events.take(4).forEach { e -> SummaryLine(if (e.allDay) "All day" else fmtMin(e.startMin), e.title) }
            laterToday.forEach { (a, t) -> SummaryLine("%02d:%02d".format(t.hour, t.minute), a.displayLabel) }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Read it to me", Modifier.weight(1f), height = 54.dp, enabled = tts != null) {
                tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "summary")
            }
            PillButton("Done", Modifier.weight(1f), primary = true, height = 54.dp, onClick = onDone)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun fmtMin(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
private fun SummaryCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(T24.Surface)
            .border(1.dp, T24.Line, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) { content() }
}

@Composable
private fun SummaryLine(time: String, title: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = T24.AmberLight, fontSize = 15.sp, modifier = Modifier.width(72.dp))
        Text(title, color = T24.Text, fontSize = 16.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
