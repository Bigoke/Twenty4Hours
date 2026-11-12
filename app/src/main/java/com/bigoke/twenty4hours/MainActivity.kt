package com.bigoke.twenty4hours

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.ui.AlarmsScreen
import com.bigoke.twenty4hours.ui.Glyph
import com.bigoke.twenty4hours.ui.GlyphIcon
import com.bigoke.twenty4hours.ui.OnAmber
import com.bigoke.twenty4hours.ui.Perms
import com.bigoke.twenty4hours.ui.SettingsScreen
import com.bigoke.twenty4hours.ui.StopwatchScreen
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.TimerScreen
import com.bigoke.twenty4hours.ui.Twenty4Theme
import com.bigoke.twenty4hours.ui.WorldScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_ALARMS = "alarms"
        const val TAB_TIMER = "timer"
        const val TAB_STOPWATCH = "stopwatch"
        const val TAB_WORLD = "world"
        const val TAB_NEW_ALARM = "new_alarm"
    }

    /** Tab requests from shortcuts / notifications, delivered to the composition. */
    private val tabRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        AlarmScheduler.rescheduleAll(this)
        enableEdgeToEdge()
        tabRequest.value = intent?.getStringExtra(EXTRA_TAB)
        setContent { Twenty4Theme { AppRoot(tabRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tabRequest.value = it }
    }
}

private enum class Tab(val title: String, val glyph: Glyph) {
    ALARMS("Alarms", Glyph.ALARM),
    TIMER("Timer", Glyph.TIMER),
    STOPWATCH("Stopwatch", Glyph.STOPWATCH),
    WORLD("World", Glyph.WORLD),
}

@Composable
private fun AppRoot(tabRequest: MutableStateFlow<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(Tab.ALARMS) }
    var showSettings by remember { mutableStateOf(false) }
    var alarmAdds by remember { mutableIntStateOf(0) }
    var cityAdds by remember { mutableIntStateOf(0) }

    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    // Shortcut / notification routing
    val req by tabRequest.collectAsState()
    LaunchedEffect(req) {
        when (req) {
            MainActivity.TAB_ALARMS -> tab = Tab.ALARMS
            MainActivity.TAB_TIMER -> tab = Tab.TIMER
            MainActivity.TAB_STOPWATCH -> tab = Tab.STOPWATCH
            MainActivity.TAB_WORLD -> tab = Tab.WORLD
            MainActivity.TAB_NEW_ALARM -> { tab = Tab.ALARMS; delay(300); alarmAdds++ }
        }
        if (req != null) tabRequest.value = null
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notifications(ctx)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!AlarmStore.setupShown && !Perms.allGood(ctx)) {
            delay(1500)
            showSettings = true
        }
        AlarmStore.setupShown = true
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && tab != Tab.ALARMS) { tab = Tab.ALARMS }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listPadding = PaddingValues(top = topInset, bottom = 140.dp)

    Box(Modifier.fillMaxSize().background(T24.Bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 * dir }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 8 * dir })
            },
            label = "tabs"
        ) { t ->
            when (t) {
                Tab.ALARMS -> AlarmsScreen(
                    now = now,
                    contentPadding = listPadding,
                    addRequests = alarmAdds,
                    onOpenSettings = { showSettings = true },
                    showUndo = { msg, undo ->
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val r = snackbar.showSnackbar(msg, actionLabel = "Undo", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) undo()
                        }
                    }
                )
                Tab.TIMER -> TimerScreen(listPadding)
                Tab.STOPWATCH -> StopwatchScreen(listPadding)
                Tab.WORLD -> WorldScreen(now, listPadding, cityAdds)
            }
        }

        // Floating add button (alarms + world)
        AnimatedVisibility(
            visible = tab == Tab.ALARMS || tab == Tab.WORLD,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 22.dp, bottom = 96.dp)
        ) {
            Box(
                Modifier
                    .shadow(16.dp, CircleShape, ambientColor = T24.Amber, spotColor = T24.Amber)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber)))
                    .clickable { if (tab == Tab.WORLD) cityAdds++ else alarmAdds++ },
                contentAlignment = Alignment.Center
            ) { GlyphIcon(Glyph.PLUS, OnAmber, 26.dp) }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = T24.SurfaceHigh,
                contentColor = T24.Text,
                actionColor = T24.AmberLight,
                shape = RoundedCornerShape(18.dp)
            )
        }

        TabBar(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(240)) { it } + fadeOut(),
        ) {
            SettingsScreen(onBack = { showSettings = false })
        }
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(20.dp, RoundedCornerShape(30.dp))
            .clip(RoundedCornerShape(30.dp))
            .background(T24.Surface.copy(alpha = 0.97f))
            .border(1.dp, T24.Line.copy(alpha = 0.7f), RoundedCornerShape(30.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.values().forEach { t ->
            val sel = t == current
            Row(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (sel) T24.SurfaceHigh else T24.Surface.copy(alpha = 0f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(t) }
                    .padding(horizontal = if (sel) 14.dp else 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(t.glyph, if (sel) T24.AmberLight else T24.Muted, 22.dp)
                AnimatedVisibility(sel) {
                    Row {
                        Spacer(Modifier.size(8.dp))
                        Text(t.title, color = T24.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
