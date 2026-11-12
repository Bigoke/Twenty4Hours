package com.bigoke.twenty4hours.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.Twenty4Theme
import kotlinx.coroutines.delay
import java.time.LocalTime

class RingActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context) = Intent(ctx, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
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
}

@Composable
private fun RingScreen(info: RingInfo, onSnooze: () -> Unit, onStop: () -> Unit) {
    val now by produceState(LocalTime.now()) {
        while (true) { value = LocalTime.now(); delay(1000) }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    val moreRings = info.ringIndex + 1 < info.rings
    val snoozeText = if (moreRings) "Snooze · next ring in ${info.intervalMin} min"
    else "Snooze ${info.intervalMin} min"

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF241A10), T24.Bg), radius = 1400f))
            .safeDrawingPadding()
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Text("NOW", color = T24.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(
                "%02d:%02d".format(now.hour, now.minute),
                color = T24.Text, fontSize = 84.sp, fontWeight = FontWeight.Light
            )
            Text(info.label, color = T24.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            if (info.rings > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ring ${info.ringIndex + 1} of ${info.rings}",
                    color = T24.Muted, fontSize = 14.sp
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(220.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2
                    drawCircle(
                        Brush.radialGradient(
                            listOf(T24.Amber.copy(alpha = 0.55f * glow), Color.Transparent),
                            center = c, radius = r
                        ),
                        radius = r, center = c
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(T24.AmberLight, T24.Amber, T24.AmberDark),
                            center = c - Offset(r * 0.12f, r * 0.12f), radius = r * 0.42f
                        ),
                        radius = r * 0.34f, center = c
                    )
                    drawCircle(
                        Color.White.copy(alpha = 0.35f),
                        radius = r * 0.09f, center = c - Offset(r * 0.12f, r * 0.14f)
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.dp, T24.Line, RoundedCornerShape(22.dp))
                        .background(T24.Surface)
                        .clickable(onClick = onSnooze),
                    contentAlignment = Alignment.Center
                ) {
                    Text(snoozeText, color = T24.Cream, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Stop", color = Color(0xFF1A0E02), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
