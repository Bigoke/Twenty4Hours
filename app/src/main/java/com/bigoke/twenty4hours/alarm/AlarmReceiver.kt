package com.bigoke.twenty4hours.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.ring.AlarmService

/** Fired by AlarmManager for alarms, reminders and timers. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Stores.init(context)
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: AlarmScheduler.KIND_ALARM

        if (kind == AlarmScheduler.KIND_ALARM) {
            val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, Int.MIN_VALUE)
            val alarm = AlarmStore.get(id)
            Notifier.cancelUpcoming(context, id)
            if (alarm != null && alarm.isReminder) {
                Notifier.showReminder(context, alarm)
                AlarmScheduler.finishCycle(context, id)
                return
            }
        }

        val svc = Intent(context, AlarmService::class.java).apply {
            action = if (kind == AlarmScheduler.KIND_TIMER) AlarmService.ACTION_RING_TIMER else AlarmService.ACTION_RING
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, svc)
    }
}

/** Re-arms every alarm and timer after a reboot, app update or clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
        com.bigoke.twenty4hours.widget.WidgetUpdater.update(context)
    }
}

/** Notification buttons and the upcoming-alarm heads-up. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SHOW_UPCOMING = "com.bigoke.twenty4hours.SHOW_UPCOMING"
        const val ACTION_DISMISS_UPCOMING = "com.bigoke.twenty4hours.DISMISS_UPCOMING"
        const val ACTION_TIMER_PAUSE = "com.bigoke.twenty4hours.TIMER_PAUSE"
        const val ACTION_TIMER_RESUME = "com.bigoke.twenty4hours.TIMER_RESUME"
        const val ACTION_TIMER_ADD = "com.bigoke.twenty4hours.TIMER_ADD"
        const val ACTION_SW_PAUSE = "com.bigoke.twenty4hours.SW_PAUSE"
        const val ACTION_SW_RESUME = "com.bigoke.twenty4hours.SW_RESUME"
        const val ACTION_SW_LAP = "com.bigoke.twenty4hours.SW_LAP"
        const val ACTION_SW_RESET = "com.bigoke.twenty4hours.SW_RESET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Stores.init(context)
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
        when (intent.action) {
            ACTION_SHOW_UPCOMING -> {
                val a = AlarmStore.get(id) ?: return
                val at = intent.getLongExtra(AlarmScheduler.EXTRA_BASE_TIME, 0L)
                if (a.enabled && at > System.currentTimeMillis()) Notifier.showUpcoming(context, a, at)
            }
            ACTION_DISMISS_UPCOMING -> {
                Notifier.cancelUpcoming(context, id)
                AlarmScheduler.skipNext(context, id)
            }
            ACTION_TIMER_PAUSE -> TimerOps.pause(context, id)
            ACTION_TIMER_RESUME -> TimerOps.start(context, id)
            ACTION_TIMER_ADD -> TimerOps.addMs(context, id, 60_000L)
            ACTION_SW_PAUSE -> StopwatchOps.pause(context)
            ACTION_SW_RESUME -> StopwatchOps.start(context)
            ACTION_SW_LAP -> StopwatchOps.lap(context)
            ACTION_SW_RESET -> StopwatchOps.reset(context)
        }
    }
}
