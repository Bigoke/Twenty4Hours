package com.bigoke.twenty4hours.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.ring.AlarmService

/** Fired by AlarmManager; hands off to the foreground ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_RING
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, svc)
    }
}

/** Re-arms every alarm after a reboot, app update or clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
