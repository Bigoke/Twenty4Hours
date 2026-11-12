package com.bigoke.twenty4hours.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.R
import com.bigoke.twenty4hours.data.PhaseKind
import com.bigoke.twenty4hours.data.SessionKind
import com.bigoke.twenty4hours.data.SessionState
import com.bigoke.twenty4hours.data.SessionStore
import com.bigoke.twenty4hours.data.Stores
import java.util.Locale

/** Labels for a phase, shared with the UI. */
fun phaseTitle(kind: SessionKind, p: PhaseKind): String = when (kind) {
    SessionKind.FOCUS -> when (p) {
        PhaseKind.WORK -> "Focus"; PhaseKind.REST -> "Short break"; PhaseKind.LONG_BREAK -> "Long break"; PhaseKind.PREP -> "Get ready"
    }
    SessionKind.INTERVAL -> when (p) {
        PhaseKind.WORK -> "Work"; PhaseKind.REST -> "Rest"; PhaseKind.LONG_BREAK -> "Cool down"; PhaseKind.PREP -> "Get ready"
    }
}

/** Start/pause/skip/stop a Pomodoro or interval session. */
object SessionOps {
    fun start(ctx: Context, kind: SessionKind) {
        val phases = when (kind) {
            SessionKind.FOCUS -> SessionState.focus(SessionStore.focusConfig.value)
            SessionKind.INTERVAL -> SessionState.interval(SessionStore.intervalConfig.value)
        }
        if (phases.isEmpty()) return
        SessionStore.set(SessionState(kind, phases, 0, true, System.currentTimeMillis() + phases[0].ms, 0L))
        send(ctx, SessionService.ACTION_ANNOUNCE)
    }

    fun pause(ctx: Context) {
        val s = SessionStore.value
        if (!s.running) return
        SessionStore.set(s.copy(running = false, remaining = s.remainingMs()))
        send(ctx, SessionService.ACTION_REFRESH)
    }

    fun resume(ctx: Context) {
        val s = SessionStore.value
        if (s.running || !s.active) return
        SessionStore.set(s.copy(running = true, phaseEnd = System.currentTimeMillis() + s.remaining))
        send(ctx, SessionService.ACTION_REFRESH)
    }

    fun skip(ctx: Context) = send(ctx, SessionService.ACTION_SKIP)

    fun stop(ctx: Context) {
        SessionStore.set(SessionState())
        send(ctx, SessionService.ACTION_STOP)
    }

    private fun send(ctx: Context, action: String) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java).setAction(action))
    }
}

/**
 * Runs focus/interval sessions in the background: advances phases on time, beeps, speaks the
 * countdown for intervals, and keeps a live notification.
 */
class SessionService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_ANNOUNCE = "com.bigoke.twenty4hours.SESSION_ANNOUNCE"
        const val ACTION_REFRESH = "com.bigoke.twenty4hours.SESSION_REFRESH"
        const val ACTION_SKIP = "com.bigoke.twenty4hours.SESSION_SKIP"
        const val ACTION_STOP = "com.bigoke.twenty4hours.SESSION_STOP"
        const val ACTION_PAUSE = "com.bigoke.twenty4hours.SESSION_PAUSE"
        const val ACTION_RESUME = "com.bigoke.twenty4hours.SESSION_RESUME"
        private const val CH = "sessions"
        private const val NOTIF_ID = 3100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSpokenSecond = -1L
    private var pendingSpeech: String? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Stores.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Focus & intervals", NotificationManager.IMPORTANCE_LOW))
        }
        tts = TextToSpeech(this, this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 85) }.getOrNull()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Twenty4Hours:session").apply {
                setReferenceCounted(false); acquire(4 * 3_600_000L)
            }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
            pendingSpeech?.let { say(it) }
            pendingSpeech = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(build())
        when (intent?.action) {
            ACTION_ANNOUNCE -> announce()
            ACTION_SKIP -> advance()
            ACTION_PAUSE -> SessionOps.pause(this)
            ACTION_RESUME -> SessionOps.resume(this)
            ACTION_STOP -> { finish(false); return START_NOT_STICKY }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        refresh()
        return START_NOT_STICKY
    }

    private fun step() {
        val s = SessionStore.value
        if (!s.active) { finish(false); return }
        if (!s.running) return
        val rem = s.remainingMs()
        if (rem <= 0L) { advance(); return }
        // Spoken / beeped 3-2-1 before each change
        val sec = (rem + 999) / 1000
        if (sec in 1..3 && sec != lastSpokenSecond) {
            lastSpokenSecond = sec
            if (s.kind == SessionKind.INTERVAL && SessionStore.intervalConfig.value.voice) say("$sec")
            else beep(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    private fun advance() {
        val s = SessionStore.value
        if (!s.active) { finish(false); return }
        val next = s.index + 1
        if (next >= s.phases.size) { finish(true); return }
        SessionStore.set(s.copy(index = next, running = true, phaseEnd = System.currentTimeMillis() + s.phases[next].ms, remaining = 0L))
        lastSpokenSecond = -1
        announce()
        refresh()
    }

    private fun announce() {
        val s = SessionStore.value
        val p = s.phase ?: return
        val title = phaseTitle(s.kind, p.kind)
        beep(ToneGenerator.TONE_PROP_ACK, 300)
        vibrate(longArrayOf(0, 180, 120, 180))
        val voice = s.kind == SessionKind.INTERVAL && SessionStore.intervalConfig.value.voice
        if (voice) {
            say(if (p.kind == PhaseKind.WORK) "$title. Round ${p.round} of ${s.totalRounds}" else title)
        } else if (s.kind == SessionKind.FOCUS) {
            say(title)
        }
    }

    private fun finish(completed: Boolean) {
        handler.removeCallbacks(tick)
        if (completed) {
            beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            vibrate(longArrayOf(0, 400, 200, 400, 200, 600))
            say("Session complete. Well done.")
            SessionStore.set(SessionState())
        }
        // let the final words finish before tearing down
        com.bigoke.twenty4hours.widget.WidgetUpdater.updateTimers(this)
        handler.postDelayed({
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, if (completed) 3500L else 0L)
    }

    private fun say(text: String) {
        if (!ttsReady) { pendingSpeech = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s${System.nanoTime()}")
    }

    private fun beep(type: Int, ms: Int) {
        runCatching { tone?.startTone(type, ms) }
    }

    private fun vibrate(pattern: LongArray) {
        @Suppress("DEPRECATION")
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        runCatching { v?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun refresh() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
        com.bigoke.twenty4hours.widget.WidgetUpdater.updateTimers(this)
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, SessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun build(): Notification {
        val s = SessionStore.value
        val p = s.phase
        val open = PendingIntent.getActivity(
            this, 710, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TIMER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(Notifier.AMBER)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        if (p == null) return b.setContentTitle("Session").build()
        val title = phaseTitle(s.kind, p.kind)
        b.setContentTitle(title)
            .setContentText(if (p.round > 0) "Round ${p.round} of ${s.totalRounds}" else if (s.kind == SessionKind.FOCUS) "Focus" else "Intervals")
        if (s.running) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.phaseEnd).setShowWhen(true)
                .addAction(0, "Pause", pi(ACTION_PAUSE, 721))
        } else {
            b.setShowWhen(false).addAction(0, "Resume", pi(ACTION_RESUME, 722))
        }
        b.addAction(0, "Skip", pi(ACTION_SKIP, 723)).addAction(0, "Stop", pi(ACTION_STOP, 724))
        return b.build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            try { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED); return } catch (_: Exception) {}
            try { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); return } catch (_: Exception) {}
        }
        try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tone?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
