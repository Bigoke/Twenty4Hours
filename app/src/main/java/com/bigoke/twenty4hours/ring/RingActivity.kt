package com.bigoke.twenty4hours.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.Challenge
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.data.VolumeAction
import com.bigoke.twenty4hours.ui.OnAmber
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.Twenty4Theme
import com.bigoke.twenty4hours.ui.drawFace
import com.bigoke.twenty4hours.ui.drawKnob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class RingActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context) = Intent(ctx, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            Twenty4Theme {
                val info by RingState.current.collectAsState()
                LaunchedEffect(info) { if (info == null) finish() }
                info?.let {
                    RingScreen(
                        info = it,
                        onSnooze = { AlarmService.send(this, AlarmService.ACTION_SNOOZE) },
                        onStop = { AlarmService.send(this, AlarmService.ACTION_STOP) },
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val info = RingState.current.value ?: return super.onKeyDown(keyCode, event)
            when (AppSettings.value.volumeButtons) {
                VolumeAction.NOTHING -> return super.onKeyDown(keyCode, event)
                VolumeAction.SNOOZE -> AlarmService.send(this, AlarmService.ACTION_SNOOZE)
                VolumeAction.STOP ->
                    if (info.challenge == Challenge.NONE) AlarmService.send(this, AlarmService.ACTION_STOP)
                    else AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun RingScreen(info: RingInfo, onSnooze: () -> Unit, onStop: () -> Unit) {
    val now by produceState(LocalTime.now()) {
        while (true) { value = LocalTime.now(); delay(1000) }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    var solving by remember { mutableStateOf(false) }

    val isTimer = info.kind == RingKind.TIMER
    val snoozeText = when {
        isTimer -> "+1 min"
        info.moreRings -> "Silence"
        else -> "Snooze ${info.snoozeMin}m"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A1C0E), T24.Bg), radius = 1500f))
            .safeDrawingPadding()
    ) {
        AnimatedContent(solving, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { s ->
            if (s) {
                MathChallenge(onSolved = onStop, onCancel = { solving = false })
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(36.dp))
                    Text(
                        if (isTimer) "TIME'S UP" else "NOW",
                        color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp
                    )
                    Text(
                        if (isTimer) info.timeText else "%02d:%02d".format(now.hour, now.minute),
                        color = T24.Text, fontSize = 84.sp, fontWeight = FontWeight.Light
                    )
                    Text(info.label, color = T24.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    if (info.rings > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ring ${info.ringIndex + 1} of ${info.rings}" +
                                if (info.moreRings) " · next in ${info.intervalMin} min" else "",
                            color = T24.Muted, fontSize = 14.sp
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxWidth(0.82f).aspectRatio(1f)) {
                            val c = Offset(size.width / 2, size.height / 2)
                            val r = size.minDimension * 0.38f
                            val tw = size.minDimension * 0.07f
                            drawFace(c, r, tw, outerLine = true)
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(T24.Amber.copy(alpha = 0.5f * glow), Color.Transparent),
                                    center = c, radius = r * 0.8f
                                ),
                                radius = r * 0.8f, center = c
                            )
                            drawKnob(c, r * 0.24f + r * 0.03f * glow, amber = true)
                        }
                    }

                    SlideToAct(
                        leftText = snoozeText,
                        rightText = if (info.challenge == Challenge.MATH) "Solve to stop" else "Stop",
                        onLeft = onSnooze,
                        onRight = { if (info.challenge == Challenge.MATH) solving = true else onStop() },
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Slide the puck · volume keys ${
                        when (AppSettings.value.volumeButtons) {
                            VolumeAction.NOTHING -> "do nothing"; VolumeAction.SNOOZE -> "snooze"; VolumeAction.STOP -> "stop"
                        }
                    }", color = T24.Faint, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SlideToAct(leftText: String, rightText: String, onLeft: () -> Unit, onRight: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(T24.Surface)
            .border(1.dp, T24.Line, RoundedCornerShape(40.dp))
    ) {
        val puck = 64.dp
        val maxPx = with(density) { ((maxWidth - puck) / 2 - 8.dp).toPx() }
        val frac = (offset.value / maxPx).coerceIn(-1f, 1f)

        Row(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹  $leftText", color = T24.Cream, fontSize = 15.sp,
                modifier = Modifier.alpha(0.55f + 0.45f * (-frac).coerceAtLeast(0f)))
            Text("$rightText  ›", color = T24.AmberLight, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(0.55f + 0.45f * frac.coerceAtLeast(0f)))
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(puck)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offset.value
                            scope.launch {
                                when {
                                    v > maxPx * 0.7f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); offset.animateTo(maxPx); onRight(); offset.snapTo(0f) }
                                    v < -maxPx * 0.7f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); offset.animateTo(-maxPx); onLeft(); offset.snapTo(0f) }
                                    else -> offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        onHorizontalDrag = { ch, dx ->
                            ch.consume()
                            scope.launch { offset.snapTo((offset.value + dx).coerceIn(-maxPx, maxPx)) }
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                drawKnob(c, size.minDimension * 0.36f, amber = abs(frac) < 0.05f || frac > 0)
            }
        }
    }
}

@Composable
private fun MathChallenge(onSolved: () -> Unit, onCancel: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var problem by remember { mutableStateOf(newProblem()) }
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("SOLVE TO STOP", color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp))
        Text(problem.first, color = T24.Text, fontSize = 48.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(10.dp))
        Text(
            entry.ifEmpty { "?" },
            color = if (wrong) T24.Amber else T24.AmberLight, fontSize = 40.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    val primary = k == "OK"
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (primary) Modifier.background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                                else Modifier.background(T24.Surface).border(1.dp, T24.Line, RoundedCornerShape(20.dp))
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                wrong = false
                                when (k) {
                                    "⌫" -> entry = entry.dropLast(1)
                                    "OK" -> if (entry.toIntOrNull() == problem.second) onSolved() else {
                                        wrong = true; entry = ""; problem = newProblem()
                                    }
                                    else -> if (entry.length < 5) entry += k
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(k, color = if (primary) OnAmber else T24.Text, fontSize = 24.sp,
                            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (wrong) "Not quite — here's a new one" else "Back",
            color = if (wrong) T24.Amber else T24.Muted, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(10.dp)
        )
    }
}

private fun newProblem(): Pair<String, Int> = if (Random.nextBoolean()) {
    val a = Random.nextInt(13, 90); val b = Random.nextInt(13, 90)
    "$a + $b" to a + b
} else {
    val a = Random.nextInt(4, 13); val b = Random.nextInt(6, 13)
    "$a × $b" to a * b
}
