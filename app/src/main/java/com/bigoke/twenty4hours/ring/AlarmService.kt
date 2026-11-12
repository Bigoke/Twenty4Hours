package com.bigoke.twenty4hours.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime

data class RingInfo(
    val alarmId: Int,
    val label: String,
    val ringIndex: Int,
    val rings: Int,
    val intervalMin: Int,
    val baseTime: Long,
)

/** Shared between the service and the ring screen (same process). */
object RingState {
    val current = MutableStateFlow<RingInfo?>(null)
}

class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.bigoke.twenty4hours.RING"
        const val ACTION_SNOOZE = "com.bigoke.twenty4hours.SNOOZE"
        const val ACTION_STOP = "com.bigoke.twenty4hours.STOP"
        const val ACTION_TEST = "com.bigoke.twenty4hours.TEST"
        const val TEST_ID = -1
        const val CHANNEL_ID = "alarms_ringing"
        private const val NOTIF_ID = 2400
        private const val RING_TIMEOUT_MS = 60_000L
        private const val RAMP_MS = 20_000L

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

    private val timeout = Runnable { silenceAndStop() }
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            val f = ((System.currentTimeMillis() - rampStart).toFloat() / RAMP_MS).coerceIn(0.08f, 1f)
            try { p.setVolume(f, f) } catch (_: Exception) {}
            if (f < 1f) handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlarmStore.init(this)
        createChannel()
        // Every start must promote to foreground quickly.
        goForeground(buildNotification(RingState.current.value ?: placeholderInfo()))

        when (intent?.action) {
            ACTION_RING -> ring(
                intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, Int.MIN_VALUE),
                intent.getIntExtra(AlarmScheduler.EXTRA_RING_INDEX, 0),
                intent.getLongExtra(AlarmScheduler.EXTRA_BASE_TIME, System.currentTimeMillis()),
            )
            ACTION_TEST -> ring(TEST_ID, 0, System.currentTimeMillis())
            ACTION_SNOOZE -> snooze()
            ACTION_STOP -> stopCycle()
            else -> if (RingState.current.value == null) silenceAndStop()
        }
        return START_NOT_STICKY
    }

    private fun placeholderInfo() = RingInfo(TEST_ID, "Alarm", 0, 1, 5, System.currentTimeMillis())

    private fun ring(alarmId: Int, ringIndex: Int, baseTime: Long) {
        val alarm: Alarm? = if (alarmId == TEST_ID) {
            val now = LocalTime.now()
            Alarm(TEST_ID, now.hour, now.minute, "Test alarm")
        } else AlarmStore.get(alarmId)

        if (alarm == null) { silenceAndStop(); return }

        val info = RingInfo(alarm.id, alarm.displayLabel, ringIndex, alarm.rings, alarm.intervalMin, baseTime)
        stopSoundAndVibration()
        RingState.current.value = info
        goForeground(buildNotification(info))
        acquireWakeLock()
        startSound()
        startVibration()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_TIMEOUT_MS)

        if (alarmId != TEST_ID) {
            if (ringIndex + 1 < alarm.rings) {
                val next = baseTime + (ringIndex + 1) * alarm.intervalMin * 60_000L
                AlarmScheduler.scheduleRing(this, alarmId, ringIndex + 1, baseTime, next)
            } else {
                AlarmScheduler.finishCycle(this, alarmId)
            }
        }

        // When the screen is on and unlocked, the full-screen intent shows as a heads-up;
        // also try to bring the ring screen up directly.
        try {
            startActivity(RingActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun snooze() {
        val info = RingState.current.value
        if (info != null && info.alarmId != TEST_ID && info.ringIndex + 1 >= info.rings) {
            // No more rings queued: add one more ring after the interval.
            val at = System.currentTimeMillis() + info.intervalMin * 60_000L
            AlarmScheduler.scheduleRing(this, info.alarmId, info.rings - 1, at, at)
        }
        silenceAndStop()
    }

    private fun stopCycle() {
        val info = RingState.current.value
        if (info != null && info.alarmId != TEST_ID) AlarmScheduler.finishCycle(this, info.alarmId)
        silenceAndStop()
    }

    private fun silenceAndStop() {
        handler.removeCallbacks(timeout)
        stopSoundAndVibration()
        RingState.current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSoundAndVibration()
        RingState.current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- sound & vibration ----

    private fun alarmUri(): Uri? = try {
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    } catch (_: Exception) {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun startSound() {
        val uri = alarmUri() ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                setVolume(0.08f, 0.08f)
                prepare()
                start()
            }
            rampStart = System.currentTimeMillis()
            handler.post(ramp)
        } catch (_: Exception) {
            player?.release()
            player = null
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
            v?.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        } catch (_: Exception) {}
    }

    private fun stopSoundAndVibration() {
        handler.removeCallbacks(ramp)
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Twenty4Hours:ring").apply {
            setReferenceCounted(false)
            acquire(RING_TIMEOUT_MS + 5_000)
        }
    }

    // ---- notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows while an alarm is ringing"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

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
        val now = LocalTime.now()
        val ringText = if (info.rings > 1) " · ring ${info.ringIndex + 1} of ${info.rings}" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(0xFFFF8A1F.toInt())
            .setContentTitle(info.label)
            .setContentText("%02d:%02d%s".format(now.hour, now.minute, ringText))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "Snooze", servicePending(ACTION_SNOOZE, 10))
            .addAction(0, "Stop", servicePending(ACTION_STOP, 11))
            .build()
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
