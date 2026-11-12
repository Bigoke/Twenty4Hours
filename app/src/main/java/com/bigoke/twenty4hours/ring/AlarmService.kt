package com.bigoke.twenty4hours.ring

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.R
import com.bigoke.twenty4hours.alarm.AlarmScheduler
import com.bigoke.twenty4hours.alarm.Notifier
import com.bigoke.twenty4hours.alarm.TimerOps
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.AppSettings
import com.bigoke.twenty4hours.data.Challenge
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.data.EventLog
import com.bigoke.twenty4hours.data.EventType
import com.bigoke.twenty4hours.data.LogEvent
import com.bigoke.twenty4hours.data.TimerStore
import com.bigoke.twenty4hours.data.formatHms
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime

enum class RingKind { ALARM, TIMER, TEST, TRIP }

data class RingInfo(
    val kind: RingKind,
    val id: Int,
    val label: String,
    val timeText: String,
    val ringIndex: Int = 0,
    val rings: Int = 1,
    val intervalMin: Int = 5,
    val snoozeMin: Int = 10,
    val baseTime: Long = System.currentTimeMillis(),
    val challenge: Challenge = Challenge.NONE,
    val startedAt: Long = System.currentTimeMillis(),
    /** how long the volume (and sunrise light) take to build up */
    val rampMs: Long = 0L,
    val sunrise: Boolean = false,
    val morningSummary: Boolean = false,
    val qrCode: String? = null,
    /** text spoken in the robot voice while ringing, if any */
    val speak: String? = null,
) {
    val moreRings: Boolean get() = ringIndex + 1 < rings
}

/** Shared between the service and the ring screen (same process). */
object RingState {
    val current = MutableStateFlow<RingInfo?>(null)
    /** Set after an alarm with a morning summary is stopped; the ring screen shows it, then clears it. */
    val summary = MutableStateFlow<RingInfo?>(null)
}

class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.bigoke.twenty4hours.RING"
        const val ACTION_RING_TIMER = "com.bigoke.twenty4hours.RING_TIMER"
        const val ACTION_SNOOZE = "com.bigoke.twenty4hours.SNOOZE"
        const val ACTION_STOP = "com.bigoke.twenty4hours.STOP"
        const val ACTION_RESTART = "com.bigoke.twenty4hours.RESTART"
        const val ACTION_TEST = "com.bigoke.twenty4hours.TEST"
        const val ACTION_RING_TRIP = "com.bigoke.twenty4hours.RING_TRIP"
        const val EXTRA_TRIP_NAME = "trip_name"
        const val TEST_ID = -1
        private const val NOTIF_ID = 2400

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AlarmService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampStart = 0L
    private var rampMs = 20_000L

    private val timeout = Runnable { silenceAndStop() }

    // ---- robot voice: speaks the label between bursts of the alarm sound ----
    private var robot: RobotVoice? = null
    private var ducked = false
    private val voiceLoop = object : Runnable {
        override fun run() {
            val info = RingState.current.value ?: return
            val text = info.speak ?: return
            val r = robot ?: RobotVoice(this@AlarmService).also { robot = it }
            r.render(text) { clip ->
                if (clip == null || RingState.current.value != info) return@render
                ducked = true
                applyVolume()
                r.play(clip, alarm = true)
                handler.postDelayed({ ducked = false; applyVolume() }, clip.durationMs + 300)
                handler.postDelayed(this, clip.durationMs + 18_000L)
            }
        }
    }
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            val f = rampLevel()
            try { val v = if (ducked) f * 0.12f else f; p.setVolume(v, v) } catch (_: Exception) {}
            if (f < 1f) handler.postDelayed(this, 400)
        }
    }

    private fun rampLevel(): Float = if (rampMs <= 0) 1f
        else ((System.currentTimeMillis() - rampStart).toFloat() / rampMs).coerceIn(0.06f, 1f)

    private fun applyVolume() {
        val f = rampLevel()
        val v = if (ducked) f * 0.12f else f
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Stores.init(this)
        Notifier.createChannels(this)
        // Every start must promote to foreground quickly.
        goForeground(buildNotification(RingState.current.value ?: placeholder()))

        when (intent?.action) {
            ACTION_RING -> ringAlarm(
                intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, Int.MIN_VALUE),
                intent.getIntExtra(AlarmScheduler.EXTRA_RING_INDEX, 0),
                intent.getLongExtra(AlarmScheduler.EXTRA_BASE_TIME, System.currentTimeMillis()),
            )
            ACTION_RING_TIMER -> ringTimer(intent.getIntExtra(AlarmScheduler.EXTRA_TIMER_ID, Int.MIN_VALUE))
            ACTION_TEST -> ringAlarm(TEST_ID, 0, System.currentTimeMillis())
            ACTION_RING_TRIP -> start(
                RingInfo(RingKind.TRIP, -2, "Nearly at " + (intent.getStringExtra(EXTRA_TRIP_NAME) ?: "your stop"), nowText()),
                null, true, 0L
            )
            ACTION_SNOOZE -> snooze()
            ACTION_RESTART -> restart()
            ACTION_STOP -> stopCycle()
            else -> if (RingState.current.value == null) silenceAndStop()
        }
        return START_NOT_STICKY
    }

    private fun nowText(): String = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

    private fun placeholder() = RingInfo(RingKind.TEST, TEST_ID, "Alarm", nowText())

    private fun ringAlarm(alarmId: Int, ringIndex: Int, baseTime: Long) {
        if (alarmId == TEST_ID) {
            start(RingInfo(RingKind.TEST, TEST_ID, "Test alarm", nowText()), null, true, AppSettings.value.gradualSec * 1000L)
            return
        }
        val alarm = AlarmStore.get(alarmId)
        if (alarm == null) { silenceAndStop(); return }

        // Smart wake: when fired early, let the sound build up until the set time.
        val untilSet = baseTime - System.currentTimeMillis()
        val ramp = when {
            ringIndex == 0 && alarm.wakeWindowMin > 0 && untilSet > 30_000L -> untilSet
            alarm.gradual -> AppSettings.value.gradualSec * 1000L
            else -> 0L
        }
        val info = RingInfo(
            kind = RingKind.ALARM, id = alarm.id, label = alarm.displayLabel, timeText = alarm.timeText,
            ringIndex = ringIndex, rings = alarm.rings, intervalMin = alarm.intervalMin,
            snoozeMin = alarm.snoozeMin, baseTime = baseTime, challenge = alarm.challenge,
            rampMs = ramp, sunrise = alarm.sunrise, morningSummary = alarm.morningSummary, qrCode = alarm.qrCode,
            speak = if (alarm.robotVoice) "${alarm.displayLabel}. It's ${alarm.hour}:${"%02d".format(alarm.minute)}." else null,
        )
        start(info, alarm.soundUri?.let { Uri.parse(it) }, alarm.vibrate, ramp)

        if (ringIndex + 1 < alarm.rings) {
            val next = baseTime + (ringIndex + 1) * alarm.intervalMin * 60_000L
            AlarmScheduler.scheduleRing(this, alarmId, ringIndex + 1, baseTime, next)
        } else {
            AlarmScheduler.finishCycle(this, alarmId)
        }
    }

    private fun ringTimer(timerId: Int) {
        val t = TimerStore.get(timerId)
        if (t == null) { silenceAndStop(); return }
        val info = RingInfo(
            kind = RingKind.TIMER, id = t.id, label = t.displayLabel,
            timeText = formatHms(t.durationMs), snoozeMin = 1,
        )
        TimerOps.finished(this, timerId)
        start(info, null, true, 0L)
    }

    private fun start(info: RingInfo, sound: Uri?, vibrate: Boolean, ramp: Long) {
        stopSoundAndVibration()
        RingState.summary.value = null
        RingState.current.value = info
        goForeground(buildNotification(info))
        acquireWakeLock(ramp)
        rampMs = ramp
        startSound(sound)
        ducked = false
        handler.removeCallbacks(voiceLoop)
        if (info.speak != null) handler.postDelayed(voiceLoop, 1_500L)
        // During a long smart-wake build-up, vibration waits until the sound is properly audible
        if (vibrate) {
            if (ramp > 60_000L) handler.postDelayed({ if (RingState.current.value == info) startVibration() }, ramp * 2 / 3)
            else startVibration()
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ramp + AppSettings.value.ringTimeoutMin * 60_000L)
        try {
            startActivity(RingActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Timer finished: run it again for its full length. */
    private fun restart() {
        val info = RingState.current.value
        if (info?.kind == RingKind.TIMER) {
            TimerStore.get(info.id)?.let { TimerOps.restartFor(this, it.id, it.durationMs) }
        }
        silenceAndStop()
    }

    private fun snooze() {
        val info = RingState.current.value
        if (info != null) {
            if (info.kind == RingKind.ALARM) {
                EventLog.add(LogEvent(EventType.SNOOZE, System.currentTimeMillis(), info.id, info.baseTime, info.label))
            }
            if (info.kind == RingKind.ALARM && !info.moreRings) {
                val at = System.currentTimeMillis() + info.snoozeMin * 60_000L
                AlarmScheduler.scheduleRing(this, info.id, info.rings - 1, at, at)
            } else if (info.kind == RingKind.TIMER) {
                TimerOps.restartFor(this, info.id, 60_000L)
            }
        }
        silenceAndStop()
    }

    private fun stopCycle() {
        val info = RingState.current.value
        if (info?.kind == RingKind.ALARM) {
            EventLog.add(LogEvent(EventType.STOP, System.currentTimeMillis(), info.id, info.baseTime, info.label))
            AlarmScheduler.finishCycle(this, info.id)
        }
        if (info != null && info.morningSummary) RingState.summary.value = info
        silenceAndStop()
    }

    private fun silenceAndStop() {
        handler.removeCallbacks(timeout)
        stopSoundAndVibration()
        RingState.current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        robot?.release()
        robot = null
        stopSoundAndVibration()
        RingState.current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- sound & vibration ----

    private fun defaultUri(): Uri? = try {
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    } catch (_: Exception) {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun startSound(preferred: Uri?) {
        if (!tryPlay(preferred ?: defaultUri())) {
            if (preferred != null) tryPlay(defaultUri())
        }
    }

    private fun tryPlay(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                val v = if (rampMs > 0) 0.06f else 1f
                setVolume(v, v)
                prepare()
                start()
            }
            rampStart = System.currentTimeMillis()
            handler.post(ramp)
            true
        } catch (_: Exception) {
            player?.release()
            player = null
            false
        }
    }

    private fun startVibration() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = v
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 1400), 0)
            @Suppress("DEPRECATION")
            v?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) {}
    }

    private fun stopSoundAndVibration() {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(voiceLoop)
        robot?.stop()
        ducked = false
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun acquireWakeLock(extraMs: Long = 0L) {
        wakeLock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Twenty4Hours:ring").apply {
            setReferenceCounted(false)
            acquire(extraMs + AppSettings.value.ringTimeoutMin * 60_000L + 10_000L)
        }
    }

    // ---- notification ----

    private fun servicePending(action: String, code: Int): PendingIntent {
        val i = Intent(this, AlarmService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(info: RingInfo): Notification {
        val full = PendingIntent.getActivity(
            this, 1, RingActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (info.kind) {
            RingKind.TIMER -> "Time's up · ${info.timeText}"
            else -> info.timeText + if (info.rings > 1) " · ring ${info.ringIndex + 1} of ${info.rings}" else ""
        }
        val snoozeLabel = when {
            info.kind == RingKind.TIMER -> "+1 min"
            info.moreRings -> "Silence"
            else -> "Snooze"
        }
        val b = NotificationCompat.Builder(this, Notifier.CH_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(Notifier.AMBER)
            .setContentTitle(info.label)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .apply { if (info.kind == RingKind.TIMER) addAction(0, "Restart", servicePending(ACTION_RESTART, 12)) }
            .addAction(0, snoozeLabel, servicePending(ACTION_SNOOZE, 10))
        // With a math challenge, stopping has to happen on the ring screen.
        if (info.challenge == Challenge.NONE) b.addAction(0, "Stop", servicePending(ACTION_STOP, 11))
        return b.build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                return
            } catch (_: Exception) {}
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                return
            } catch (_: Exception) {}
        }
        try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
    }
}
