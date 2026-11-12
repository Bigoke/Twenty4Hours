package com.bigoke.twenty4hours.alarm

import android.content.Context
import com.bigoke.twenty4hours.data.StopwatchStore
import com.bigoke.twenty4hours.data.TimerItem
import com.bigoke.twenty4hours.data.TimerState
import com.bigoke.twenty4hours.data.TimerStore

/** Timer actions: update the store, the system alarm and the notification together. */
object TimerOps {
    private fun commit(ctx: Context, t: TimerItem) {
        TimerStore.upsert(t)
        AlarmScheduler.scheduleTimer(ctx, t)
        Notifier.refreshTimer(ctx)
    }

    fun start(ctx: Context, id: Int) {
        val t = TimerStore.get(id) ?: return
        val rem = t.remaining()
        if (rem <= 0L) return
        commit(ctx, t.copy(state = TimerState.RUNNING, endAt = System.currentTimeMillis() + rem))
    }

    fun pause(ctx: Context, id: Int) {
        val t = TimerStore.get(id) ?: return
        if (t.state != TimerState.RUNNING) return
        commit(ctx, t.copy(state = TimerState.PAUSED, remainingMs = t.remaining()))
    }

    fun reset(ctx: Context, id: Int) {
        val t = TimerStore.get(id) ?: return
        commit(ctx, t.copy(state = TimerState.IDLE, endAt = 0L, remainingMs = 0L))
    }

    fun addMs(ctx: Context, id: Int, ms: Long) {
        val t = TimerStore.get(id) ?: return
        val u = when (t.state) {
            TimerState.RUNNING -> t.copy(endAt = t.endAt + ms)
            TimerState.PAUSED -> t.copy(remainingMs = t.remainingMs + ms)
            TimerState.IDLE -> t.copy(durationMs = (t.durationMs + ms).coerceIn(1_000L, 24 * 3_600_000L))
        }
        commit(ctx, u)
    }

    fun setDuration(ctx: Context, id: Int, ms: Long) {
        val t = TimerStore.get(id) ?: return
        commit(ctx, t.copy(durationMs = ms.coerceIn(1_000L, 24 * 3_600_000L), state = TimerState.IDLE))
    }

    fun rename(ctx: Context, id: Int, label: String) {
        val t = TimerStore.get(id) ?: return
        commit(ctx, t.copy(label = label))
    }

    fun delete(ctx: Context, id: Int) {
        AlarmScheduler.cancelTimer(ctx, id)
        TimerStore.delete(id)
        Notifier.refreshTimer(ctx)
    }

    /** Called when a timer reaches zero: back to its full duration, ready to run again. */
    fun finished(ctx: Context, id: Int) {
        val t = TimerStore.get(id) ?: return
        TimerStore.upsert(t.copy(state = TimerState.IDLE, endAt = 0L, remainingMs = 0L))
        Notifier.refreshTimer(ctx)
    }

    /** "+1 min" from the ringing screen: run again for one minute. */
    fun restartFor(ctx: Context, id: Int, ms: Long) {
        val t = TimerStore.get(id) ?: return
        commit(ctx, t.copy(state = TimerState.RUNNING, endAt = System.currentTimeMillis() + ms))
    }
}

object StopwatchOps {
    fun start(ctx: Context) { StopwatchStore.start(); Notifier.refreshStopwatch(ctx) }
    fun pause(ctx: Context) { StopwatchStore.pause(); Notifier.refreshStopwatch(ctx) }
    fun lap(ctx: Context) { StopwatchStore.lap(); Notifier.refreshStopwatch(ctx) }
    fun reset(ctx: Context) { StopwatchStore.reset(); Notifier.refreshStopwatch(ctx) }
}
