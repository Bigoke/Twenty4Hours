package com.bigoke.twenty4hours.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.data.TimerItem
import com.bigoke.twenty4hours.data.TimerState
import com.bigoke.twenty4hours.data.TimerStore
import java.time.ZonedDateTime

/**
 * Scheduling model:
 * - Each alarm has exactly one pending system alarm (request code = alarm id).
 * - That pending alarm carries the ring index and the cycle's base time, so a
 *   "4 rings every 5 min" alarm walks 0 → 1 → 2 → 3, each ring scheduling the next.
 * - When the cycle ends (last ring fires, or the user stops it) the alarm either
 *   moves to its next repeat day, or switches off if it was a one-time alarm.
 * - Each alarm also gets an "upcoming alarm" notification ahead of time.
 * - Timers get their own pending alarm (request code TIMER_BASE + id).
 */
object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_RING_INDEX = "ring_index"
    const val EXTRA_BASE_TIME = "base_time"
    const val EXTRA_KIND = "kind"
    const val EXTRA_TIMER_ID = "timer_id"

    const val KIND_ALARM = "alarm"
    const val KIND_TIMER = "timer"

    private const val UPCOMING_BASE = 1_000_000
    private const val TIMER_BASE = 2_000_000

    private fun manager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun firePending(ctx: Context, alarmId: Int, ringIndex: Int, baseTime: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.bigoke.twenty4hours.FIRE"
            putExtra(EXTRA_KIND, KIND_ALARM)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_RING_INDEX, ringIndex)
            putExtra(EXTRA_BASE_TIME, baseTime)
        }
        return PendingIntent.getBroadcast(
            ctx, alarmId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun upcomingPending(ctx: Context, alarmId: Int, triggerAt: Long): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_SHOW_UPCOMING
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_BASE_TIME, triggerAt)
        }
        return PendingIntent.getBroadcast(
            ctx, UPCOMING_BASE + alarmId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun timerPending(ctx: Context, timerId: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.bigoke.twenty4hours.TIMER"
            putExtra(EXTRA_KIND, KIND_TIMER)
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        return PendingIntent.getBroadcast(
            ctx, TIMER_BASE + timerId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun setExact(ctx: Context, at: Long, pi: PendingIntent, asAlarmClock: Boolean) {
        val am = manager(ctx)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            when {
                canExact && asAlarmClock -> am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending(ctx)), pi)
                canExact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    // ------------------------------------------------------------ alarms

    /** Schedule (or cancel, if disabled) the next occurrence of [alarm]. */
    fun schedule(ctx: Context, alarm: Alarm) {
        cancel(ctx, alarm.id)
        if (!alarm.active) return
        val next = alarm.nextTriggerOrNull(ZonedDateTime.now())
        if (next == null) {
            // A one-time date that has passed: switch it off.
            if (!alarm.repeating && alarm.enabled) AlarmStore.upsert(alarm.copy(enabled = false))
            return
        }
        val t = next.toInstant().toEpochMilli()
        // Smart wake: start softly before the set time, unless that moment has already passed.
        val early = t - alarm.wakeWindowMin * 60_000L
        val fireAt = if (!alarm.isReminder && alarm.wakeWindowMin > 0 && early > System.currentTimeMillis() + 5_000L) early else t
        scheduleRing(ctx, alarm.id, 0, t, fireAt)
        scheduleUpcoming(ctx, alarm, fireAt)
    }

    /** Schedule a specific ring of an alarm cycle. */
    fun scheduleRing(ctx: Context, alarmId: Int, ringIndex: Int, baseTime: Long, at: Long) {
        setExact(ctx, at, firePending(ctx, alarmId, ringIndex, baseTime), asAlarmClock = true)
    }

    private fun scheduleUpcoming(ctx: Context, alarm: Alarm, triggerAt: Long) {
        val lead = AppSettings.value.upcomingLeadMin
        if (alarm.isReminder || lead <= 0) return
        val showAt = maxOf(System.currentTimeMillis() + 2_000L, triggerAt - lead * 60_000L)
        if (showAt >= triggerAt) return
        manager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, showAt, upcomingPending(ctx, alarm.id, triggerAt))
    }

    fun cancel(ctx: Context, alarmId: Int) {
        val am = manager(ctx)
        am.cancel(firePending(ctx, alarmId, 0, 0L))
        am.cancel(upcomingPending(ctx, alarmId, 0L))
        Notifier.cancelUpcoming(ctx, alarmId)
    }

    /** End the current ring cycle: move a repeating alarm on, or switch a one-time alarm off. */
    fun finishCycle(ctx: Context, alarmId: Int) {
        Stores.init(ctx)
        val alarm = AlarmStore.get(alarmId) ?: run { cancel(ctx, alarmId); return }
        if (alarm.repeating && alarm.enabled) {
            // Drop a "skip next" that has now been used up
            val a = if (alarm.skipUntil in 1 until System.currentTimeMillis()) alarm.copy(skipUntil = 0L).also { AlarmStore.upsert(it) } else alarm
            schedule(ctx, a)
        } else {
            cancel(ctx, alarmId)
            if (alarm.enabled) AlarmStore.upsert(alarm.copy(enabled = false))
        }
    }

    /** Skip the next occurrence of a repeating alarm (or switch off a one-time alarm). */
    fun skipNext(ctx: Context, alarmId: Int) {
        Stores.init(ctx)
        val a = AlarmStore.get(alarmId) ?: return
        if (!a.repeating) {
            val off = a.copy(enabled = false, skipUntil = 0L)
            AlarmStore.upsert(off); schedule(ctx, off); return
        }
        val now = ZonedDateTime.now()
        val next = a.nextTrigger(now).toInstant().toEpochMilli()
        val u = a.copy(skipUntil = next + 1_000L, enabled = true)
        AlarmStore.upsert(u); schedule(ctx, u)
    }

    fun clearSkip(ctx: Context, alarmId: Int) {
        val a = AlarmStore.get(alarmId) ?: return
        val u = a.copy(skipUntil = 0L)
        AlarmStore.upsert(u); schedule(ctx, u)
    }

    fun rescheduleAll(ctx: Context) {
        Stores.init(ctx)
        AlarmStore.alarms.value.forEach { schedule(ctx, it) }
        TimerStore.timers.value.forEach { scheduleTimer(ctx, it) }
        Notifier.refreshOngoing(ctx)
    }

    // ------------------------------------------------------------ timers

    fun scheduleTimer(ctx: Context, t: TimerItem) {
        val pi = timerPending(ctx, t.id)
        manager(ctx).cancel(pi)
        if (t.state == TimerState.RUNNING) {
            setExact(ctx, maxOf(t.endAt, System.currentTimeMillis() + 500), pi, asAlarmClock = false)
        }
    }

    fun cancelTimer(ctx: Context, timerId: Int) {
        manager(ctx).cancel(timerPending(ctx, timerId))
    }
}
