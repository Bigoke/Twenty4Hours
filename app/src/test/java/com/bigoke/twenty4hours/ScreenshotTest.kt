package com.bigoke.twenty4hours

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmKind
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.Challenge
import com.bigoke.twenty4hours.data.Prefs
import com.bigoke.twenty4hours.data.StopwatchData
import com.bigoke.twenty4hours.data.StopwatchStore
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.data.TimerItem
import com.bigoke.twenty4hours.data.TimerState
import com.bigoke.twenty4hours.data.TimerStore
import com.bigoke.twenty4hours.data.WorldStore
import com.bigoke.twenty4hours.ring.MathChallenge
import com.bigoke.twenty4hours.ring.RingInfo
import com.bigoke.twenty4hours.ring.RingKind
import com.bigoke.twenty4hours.ring.RingScreen
import com.bigoke.twenty4hours.ui.AlarmEditorBody
import com.bigoke.twenty4hours.ui.SettingsScreen
import com.bigoke.twenty4hours.ui.T24
import com.bigoke.twenty4hours.ui.Twenty4Theme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen at phone size (and with large text) into app/build/screens,
 * so layout problems — overlaps, clipped or overlong text — can be seen without a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Stores.init(ctx)
        Prefs.sp.edit().putBoolean("setup_shown", true).apply()
        AlarmStore.alarms.value.forEach { AlarmStore.delete(it.id) }
        listOf(
            Alarm(1, 7, 0, "Wake up", rings = 4, intervalMin = 5, days = setOf(1, 2, 3, 4, 5)),
            Alarm(2, 9, 0, "Take medication", kind = AlarmKind.REMINDER, days = (1..7).toSet()),
            Alarm(3, 9, 30, "Leave home", days = setOf(1, 2, 3, 4, 5)),
            Alarm(4, 15, 40, "Pick up the kids from school on Thursday", challenge = Challenge.MATH),
            Alarm(5, 23, 0, "Go to bed", kind = AlarmKind.REMINDER, days = (1..7).toSet()),
            Alarm(6, 18, 15, "Gym", enabled = false, group = "Fitness"),
        ).forEach { AlarmStore.upsert(it) }
        AlarmStore.upsert(AlarmStore.get(1)!!.copy(role = com.bigoke.twenty4hours.data.Role.WAKE, group = "Work", wakeWindowMin = 20, robotVoice = true))
        AlarmStore.upsert(AlarmStore.get(3)!!.copy(group = "Work"))
        com.bigoke.twenty4hours.data.AppSettings.update { it.copy(lat = -29.12, lon = 26.21, placeName = "Bloemfontein") }
        val day = 86_400_000L
        val base0 = System.currentTimeMillis() - 7 * day
        com.bigoke.twenty4hours.data.EventLog.clear()
        for (d in 0 until 7) {
            val morning = java.time.LocalDate.now().minusDays((6 - d).toLong()).atTime(7, 0).atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val snoozes = d % 3
            com.bigoke.twenty4hours.data.EventLog.add(com.bigoke.twenty4hours.data.LogEvent(
                com.bigoke.twenty4hours.data.EventType.BEDTIME, morning - 8 * 3_600_000L - d * 600_000L, 5, 0, "Go to bed"))
            repeat(snoozes) {
                com.bigoke.twenty4hours.data.EventLog.add(com.bigoke.twenty4hours.data.LogEvent(
                    com.bigoke.twenty4hours.data.EventType.SNOOZE, morning + it * 600_000L, 1, morning, "Wake up"))
            }
            com.bigoke.twenty4hours.data.EventLog.add(com.bigoke.twenty4hours.data.LogEvent(
                com.bigoke.twenty4hours.data.EventType.STOP, morning + snoozes * 600_000L + 120_000L, 1, morning, "Wake up"))
        }
        check(base0 > 0)

        TimerStore.timers.value.forEach { TimerStore.delete(it.id) }
        val t1 = TimerStore.timers.value.first()
        TimerStore.upsert(t1.copy(label = "Sourdough second proof", durationMs = 45 * 60_000L,
            state = TimerState.PAUSED, remainingMs = 31 * 60_000L + 12_000L))
        TimerStore.upsert(TimerItem(901, "", 5 * 60_000L))
        TimerStore.upsert(TimerItem(902, "Laundry", 75 * 60_000L, TimerState.PAUSED, remainingMs = 63 * 60_000L))
        TimerStore.select(t1.id)

        val base = SystemClock.elapsedRealtime()
        StopwatchStore.set(
            StopwatchData(
                running = false, startedAt = base, accumulated = 3_723_450L,
                laps = listOf(61_230L, 125_400L, 190_020L, 3_600_000L)
            )
        )

        WorldStore.zones.value.forEach { WorldStore.remove(it) }
        listOf("Europe/London", "America/Argentina/ComodRivadavia", "Asia/Ho_Chi_Minh", "Pacific/Port_Moresby", "America/New_York")
            .forEach { WorldStore.add(it) }
    }

    private fun shot(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                Twenty4Theme {
                    Box(Modifier.fillMaxSize().background(T24.Bg)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Composable
    private fun app(tab: Tab) {
        AppRoot(MutableStateFlow(null), initialTab = tab, firstRunChecks = false)
    }

    private val pad = PaddingValues(top = 24.dp, bottom = 140.dp)

    // ---- full app, one per tab ----
    @Test fun a1_alarms() = shot("a1_alarms") { app(Tab.ALARMS) }
    @Test fun a2_alarms_large() = shot("a2_alarms_large", 1.3f) { app(Tab.ALARMS) }
    @Test @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun a3_alarms_full() = shot("a3_alarms_full") { app(Tab.ALARMS) }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun a4_alarms_narrow() = shot("a4_alarms_narrow") { app(Tab.ALARMS) }

    @Test fun b1_timer() = shot("b1_timer") { app(Tab.TIMER) }
    @Test fun b2_timer_large() = shot("b2_timer_large", 1.3f) { app(Tab.TIMER) }
    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun b3_timer_full() = shot("b3_timer_full") { app(Tab.TIMER) }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun c1_stopwatch() = shot("c1_stopwatch") { app(Tab.STOPWATCH) }
    @Test fun c2_stopwatch_large() = shot("c2_stopwatch_large", 1.3f) { app(Tab.STOPWATCH) }

    @Test @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun d1_world() = shot("d1_world") { app(Tab.WORLD) }
    @Test fun d2_world_large() = shot("d2_world_large", 1.3f) { app(Tab.WORLD) }

    // ---- editor ----
    @Test @Config(qualifiers = "w411dp-h3600dp-xxhdpi")
    fun e1_editor_existing() = shot("e1_editor_existing") {
        AlarmEditorBody(AlarmStore.get(1)!!, false, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = "w411dp-h4200dp-xxhdpi")
    fun e2_editor_large() = shot("e2_editor_large", 1.3f) {
        AlarmEditorBody(AlarmStore.get(4)!!, false, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = "w360dp-h2400dp-xxhdpi")
    fun e3_editor_narrow_reminder() = shot("e3_editor_narrow_reminder") {
        AlarmEditorBody(AlarmStore.get(2)!!, false, {}, {}, {}, {})
    }

    // ---- settings ----
    @Test @Config(qualifiers = "w411dp-h3800dp-xxhdpi")
    fun f1_settings() = shot("f1_settings") { SettingsScreen(onBack = {}) }
    @Test @Config(qualifiers = "w411dp-h4600dp-xxhdpi")
    fun f2_settings_large() = shot("f2_settings_large", 1.3f) { SettingsScreen(onBack = {}) }

    // ---- ringing ----
    private val ring = RingInfo(
        RingKind.ALARM, 1, "Pick up the kids from school on Thursday", "15:40",
        ringIndex = 1, rings = 4, intervalMin = 5, snoozeMin = 10, challenge = Challenge.MATH,
    )
    @Test fun g1_ring() = shot("g1_ring") { RingScreen(ring, {}, {}) }
    @Test fun g2_ring_large() = shot("g2_ring_large", 1.3f) { RingScreen(ring, {}, {}) }
    @Test fun g3_ring_timer() = shot("g3_ring_timer") {
        RingScreen(RingInfo(RingKind.TIMER, 1, "Sourdough second proof", "45:00", snoozeMin = 1), {}, {})
    }
    @Test fun g4_math() = shot("g4_math") { MathChallenge({}, {}) }
    @Test fun g6_shake() = shot("g6_shake") { com.bigoke.twenty4hours.ring.ShakeChallenge({}, {}) }
    @Test fun g7_qr() = shot("g7_qr") { com.bigoke.twenty4hours.ring.QrChallenge("abc", {}, {}, {}) }
    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun g8_summary() = shot("g8_summary") { com.bigoke.twenty4hours.ring.MorningSummary(ring, {}) }
    @Test fun g9_sunrise() = shot("g9_sunrise") {
        RingScreen(ring.copy(challenge = Challenge.NONE, sunrise = true, rampMs = 1_000L, startedAt = System.currentTimeMillis() - 500L), {}, {})
    }

    // ---- new screens ----
    @Test @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun h1_focus() = shot("h1_focus") {
        androidx.compose.runtime.LaunchedEffect(Unit) { }
        com.bigoke.twenty4hours.ui.TimerScreenForTest(1)
    }
    @Test @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun h2_intervals() = shot("h2_intervals") { com.bigoke.twenty4hours.ui.TimerScreenForTest(2) }
    @Test @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun h3_stats() = shot("h3_stats") { com.bigoke.twenty4hours.ui.StatsScreen(onBack = {}) }
    @Test fun h4_trip() = shot("h4_trip") { com.bigoke.twenty4hours.ui.TripScreen(onBack = {}) }
    @Test @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun h5_timers_presets() = shot("h5_timers_presets") { com.bigoke.twenty4hours.ui.TimerScreenForTest(0) }
    @Test @Config(qualifiers = "w360dp-h1800dp-xxhdpi")
    fun h6_focus_large() = shot("h6_focus_large", 1.3f) { com.bigoke.twenty4hours.ui.TimerScreenForTest(1) }
    @Test @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun g5_ring_narrow() = shot("g5_ring_narrow") {
        RingScreen(ring.copy(challenge = Challenge.NONE, ringIndex = 3), {}, {})
    }
}
