package com.bigoke.twenty4hours.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import java.time.ZonedDateTime

/**
 * Scheduling model:
 * - Each alarm has exactly one pending system alarm (request code = alarm id).
 * - That pending alarm carries the ring index and the cycle's base time, so a
 *   "4 rings every 5 min" alarm walks 0 → 1 → 2 → 3, each ring scheduling the next.
 * - When the cycle ends (last ring fires, or the user stops it) the alarm either
 *   moves to its next repeat day, or switches off if it was a one-time alarm.
 */
object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_RING_INDEX = "ring_index"
    const val EXTRA_BASE_TIME = "base_time"

    private fun manager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun firePending(ctx: Context, alarmId: Int, ringIndex: Int, baseTime: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.bigoke.twenty4hours.FIRE"
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_RING_INDEX, ringIndex)
            putExtra(EXTRA_BASE_TIME, baseTime)
        }
        return PendingIntent.getBroadcast(
            ctx, alarmId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Schedule (or cancel, if disabled) the next occurrence of [alarm]. */
    fun schedule(ctx: Context, alarm: Alarm) {
        cancel(ctx, alarm.id)
        if (!alarm.enabled) return
        val t = alarm.nextTrigger(ZonedDateTime.now()).toInstant().toEpochMilli()
        scheduleRing(ctx, alarm.id, 0, t, t)
    }

    /** Schedule a specific ring of an alarm cycle. */
    fun scheduleRing(ctx: Context, alarmId: Int, ringIndex: Int, baseTime: Long, at: Long) {
        val am = manager(ctx)
        val pi = firePending(ctx, alarmId, ringIndex, baseTime)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending(ctx)), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, alarmId: Int) {
        manager(ctx).cancel(firePending(ctx, alarmId, 0, 0L))
    }

    /** End the current ring cycle: move a repeating alarm on, or switch a one-time alarm off. */
    fun finishCycle(ctx: Context, alarmId: Int) {
        AlarmStore.init(ctx)
        val alarm = AlarmStore.get(alarmId) ?: run { cancel(ctx, alarmId); return }
        if (alarm.repeating && alarm.enabled) {
            schedule(ctx, alarm)
        } else {
            cancel(ctx, alarmId)
            if (alarm.enabled) AlarmStore.upsert(alarm.copy(enabled = false))
        }
    }

    fun rescheduleAll(ctx: Context) {
        AlarmStore.init(ctx)
        AlarmStore.alarms.value.forEach { schedule(ctx, it) }
    }
}
