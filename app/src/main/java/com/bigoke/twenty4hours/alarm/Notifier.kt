package com.bigoke.twenty4hours.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.R
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.StopwatchStore
import com.bigoke.twenty4hours.data.TimerState
import com.bigoke.twenty4hours.data.TimerStore
import com.bigoke.twenty4hours.data.formatStopwatch
import java.time.Instant
import java.time.ZoneId

object Notifier {
    const val CH_RINGING = "alarms_ringing"
    const val CH_UPCOMING = "upcoming"
    const val CH_REMINDER = "reminders"
    const val CH_TIMER = "timers_running"
    const val CH_STOPWATCH = "stopwatch"

    private const val ID_TIMER = 3001
    private const val ID_STOPWATCH = 3002
    private const val UPCOMING_NOTIF_BASE = 10_000
    private const val REMINDER_NOTIF_BASE = 20_000

    const val AMBER = 0xFFFF8A1F.toInt()

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels(ctx: Context) {
        val nm = nm(ctx)
        val ringing = NotificationChannel(CH_RINGING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows while an alarm or timer is ringing"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val upcoming = NotificationChannel(CH_UPCOMING, "Upcoming alarms", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A heads-up before an alarm, with a button to dismiss it early"
        }
        val reminder = NotificationChannel(CH_REMINDER, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Gentle reminders from the dial"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
            )
        }
        val timer = NotificationChannel(CH_TIMER, "Running timers", NotificationManager.IMPORTANCE_LOW)
        val sw = NotificationChannel(CH_STOPWATCH, "Stopwatch", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannels(listOf(ringing, upcoming, reminder, timer, sw))
    }

    private fun openApp(ctx: Context, tab: String? = null, code: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (tab != null) putExtra(MainActivity.EXTRA_TAB, tab)
        }
        return PendingIntent.getActivity(ctx, 500 + code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, action: String, code: Int, id: Int = 0): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try { nm(ctx).notify(id, n) } catch (_: SecurityException) {}
    }

    // ---------------------------------------------------------- upcoming

    fun showUpcoming(ctx: Context, alarm: Alarm, triggerAt: Long) {
        val t = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault())
        val n = NotificationCompat.Builder(ctx, CH_UPCOMING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(AMBER)
            .setContentTitle("Upcoming: ${alarm.displayLabel}")
            .setContentText("Rings at %02d:%02d".format(t.hour, t.minute))
            .setWhen(triggerAt)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setTimeoutAfter((triggerAt - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .addAction(0, "Dismiss now",
                action(ctx, ActionReceiver.ACTION_DISMISS_UPCOMING, UPCOMING_NOTIF_BASE + alarm.id, alarm.id))
            .build()
        post(ctx, UPCOMING_NOTIF_BASE + alarm.id, n)
    }

    fun cancelUpcoming(ctx: Context, alarmId: Int) {
        nm(ctx).cancel(UPCOMING_NOTIF_BASE + alarmId)
    }

    // ---------------------------------------------------------- reminder

    fun showReminder(ctx: Context, alarm: Alarm) {
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(AMBER)
            .setContentTitle(alarm.displayLabel)
            .setContentText("Reminder · ${alarm.timeText}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, code = 1))
            .build()
        post(ctx, REMINDER_NOTIF_BASE + alarm.id, n)
    }

    // ---------------------------------------------------------- ongoing

    /** Keeps the "timer running" and "stopwatch" notifications in step with their state. */
    fun refreshOngoing(ctx: Context) {
        refreshTimer(ctx)
        refreshStopwatch(ctx)
    }

    fun refreshTimer(ctx: Context) {
        val now = System.currentTimeMillis()
        val running = TimerStore.timers.value.filter { it.state == TimerState.RUNNING && it.endAt > now }
            .sortedBy { it.endAt }
        val paused = TimerStore.timers.value.filter { it.state == TimerState.PAUSED }
        val first = running.firstOrNull()
        if (first == null && paused.isEmpty()) { nm(ctx).cancel(ID_TIMER); return }

        val b = NotificationCompat.Builder(ctx, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(AMBER)
            .setOngoing(first != null)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openApp(ctx, MainActivity.TAB_TIMER, 2))
        if (first != null) {
            b.setContentTitle(first.displayLabel)
                .setContentText(
                    if (running.size + paused.size > 1) "${running.size} running · ${paused.size} paused" else "Timer running"
                )
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(first.endAt)
                .setShowWhen(true)
                .addAction(0, "Pause", action(ctx, ActionReceiver.ACTION_TIMER_PAUSE, 601, first.id))
                .addAction(0, "+1 min", action(ctx, ActionReceiver.ACTION_TIMER_ADD, 602, first.id))
        } else {
            val p = paused.first()
            b.setContentTitle(p.displayLabel)
                .setContentText("Paused · " + com.bigoke.twenty4hours.data.formatHms(p.remainingMs))
                .setShowWhen(false)
                .addAction(0, "Resume", action(ctx, ActionReceiver.ACTION_TIMER_RESUME, 603, p.id))
        }
        post(ctx, ID_TIMER, b.build())
    }

    fun refreshStopwatch(ctx: Context) {
        val s = StopwatchStore.value
        if (!s.running && s.accumulated == 0L) { nm(ctx).cancel(ID_STOPWATCH); return }
        val b = NotificationCompat.Builder(ctx, CH_STOPWATCH)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(AMBER)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(s.running)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openApp(ctx, MainActivity.TAB_STOPWATCH, 3))
            .setContentTitle("Stopwatch")
        if (s.running) {
            val wallStart = System.currentTimeMillis() - s.elapsed(SystemClock.elapsedRealtime())
            b.setUsesChronometer(true).setWhen(wallStart).setShowWhen(true)
                .setContentText(if (s.laps.isNotEmpty()) "Lap ${s.laps.size + 1}" else "Running")
                .addAction(0, "Pause", action(ctx, ActionReceiver.ACTION_SW_PAUSE, 611))
                .addAction(0, "Lap", action(ctx, ActionReceiver.ACTION_SW_LAP, 612))
        } else {
            b.setShowWhen(false)
                .setContentText("Paused · " + formatStopwatch(s.accumulated))
                .addAction(0, "Resume", action(ctx, ActionReceiver.ACTION_SW_RESUME, 613))
                .addAction(0, "Reset", action(ctx, ActionReceiver.ACTION_SW_RESET, 614))
        }
        post(ctx, ID_STOPWATCH, b.build())
    }
}
