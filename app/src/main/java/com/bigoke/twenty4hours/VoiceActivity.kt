package com.bigoke.twenty4hours

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.alarm.SessionOps
import com.bigoke.twenty4hours.alarm.TimerOps
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.SessionStore
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.data.TimerStore
import com.bigoke.twenty4hours.data.formatCountdown
import com.bigoke.twenty4hours.data.formatHms
import com.bigoke.twenty4hours.ring.AlarmService
import com.bigoke.twenty4hours.ring.RingState
import java.time.ZonedDateTime

/**
 * Handles Android's standard clock intents, so Google Assistant ("set an alarm for 6:15",
 * "set a 10 minute timer") and other apps can use Twenty4Hours. Shows no UI of its own.
 */
class VoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        val i = intent
        when (i?.action) {
            AlarmClock.ACTION_SET_ALARM -> setAlarm(i)
            AlarmClock.ACTION_SET_TIMER -> setTimer(i)
            AlarmClock.ACTION_SHOW_ALARMS -> open(MainActivity.TAB_ALARMS)
            AlarmClock.ACTION_SHOW_TIMERS -> open(MainActivity.TAB_TIMER)
            AlarmClock.ACTION_DISMISS_ALARM, AlarmClock.ACTION_DISMISS_TIMER ->
                if (RingState.current.value != null) AlarmService.send(this, AlarmService.ACTION_STOP)
            AlarmClock.ACTION_SNOOZE_ALARM ->
                if (RingState.current.value != null) AlarmService.send(this, AlarmService.ACTION_SNOOZE)
        }
        finish()
    }

    private fun open(tab: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun setAlarm(i: Intent) {
        if (!i.hasExtra(AlarmClock.EXTRA_HOUR)) { open(MainActivity.TAB_NEW_ALARM); return }
        val h = i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23)
        val m = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
        // Android's days use Calendar.SUNDAY = 1 … SATURDAY = 7; ours are Monday = 1 … Sunday = 7
        val days = i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.map { ((it + 5) % 7) + 1 }?.toSet() ?: emptySet()
        val s = AppSettings.value
        val a = Alarm(
            AlarmStore.newId(), h, m, i.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: "",
            days = days, rings = s.defaultRings, intervalMin = s.defaultInterval, snoozeMin = s.defaultSnooze,
            vibrate = i.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, s.defaultVibrate),
        )
        AlarmStore.upsert(a)
        AlarmScheduler.schedule(this, a)
        val n = ZonedDateTime.now()
        Toast.makeText(this, "Alarm set for ${a.timeText} — in ${formatCountdown(n, a.nextTrigger(n))}", Toast.LENGTH_LONG).show()
        if (!i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) open(MainActivity.TAB_ALARMS)
    }

    private fun setTimer(i: Intent) {
        val secs = i.getIntExtra(AlarmClock.EXTRA_LENGTH, -1)
        if (secs <= 0) { open(MainActivity.TAB_TIMER); return }
        val t = TimerStore.add(secs * 1000L, i.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: "")
        TimerOps.start(this, t.id)
        Toast.makeText(this, "Timer started: ${formatHms(secs * 1000L)}", Toast.LENGTH_SHORT).show()
        if (!i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) open(MainActivity.TAB_TIMER)
    }
}

/** Quick Settings tile: a 20-minute nap alarm in one tap. */
class NapTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Nap 20m"; updateTile() }
    }

    override fun onClick() {
        Stores.init(this)
        val t = ZonedDateTime.now().plusMinutes(20)
        val a = Alarm(AlarmStore.newId(), t.hour, t.minute, "Nap", snoozeMin = AppSettings.value.defaultSnooze)
        AlarmStore.upsert(a)
        AlarmScheduler.schedule(this, a)
        Toast.makeText(this, "Nap alarm set for ${a.timeText}", Toast.LENGTH_SHORT).show()
    }
}

/** Quick Settings tile: start (or stop) a Pomodoro focus session. */
class FocusTileService : TileService() {
    override fun onStartListening() {
        Stores.init(this)
        val on = SessionStore.value.active
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (on) "Focus on" else "Focus 25"
            updateTile()
        }
    }

    override fun onClick() {
        Stores.init(this)
        if (SessionStore.value.active) {
            SessionOps.stop(this)
            onStartListening()
            return
        }
        // Open the app on the Focus pane and start there (starting from the app is always allowed)
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_FOCUS_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 900, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
