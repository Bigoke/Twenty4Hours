package com.bigoke.twenty4hours.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.R
import com.bigoke.twenty4hours.data.Prefs
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.ring.AlarmService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class Trip(val name: String, val lat: Double, val lon: Double, val radiusM: Int)

/** The active "wake me before my stop" trip, plus the live distance. */
object TripStore {
    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip.asStateFlow()
    /** metres to go; null until the first fix */
    val distance = MutableStateFlow<Float?>(null)

    fun load() {
        _trip.value = try {
            Prefs.sp.getString("trip", null)?.let {
                val o = JSONObject(it)
                Trip(o.getString("n"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("r"))
            }
        } catch (e: Exception) { null }
    }

    fun set(t: Trip?) {
        Prefs.sp.edit().apply {
            if (t == null) remove("trip")
            else putString("trip", JSONObject().put("n", t.name).put("lat", t.lat).put("lon", t.lon).put("r", t.radiusM).toString())
        }.apply()
        _trip.value = t
        if (t == null) distance.value = null
    }

    fun start(ctx: Context, t: Trip) {
        set(t)
        ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java))
    }

    fun stop(ctx: Context) {
        set(null)
        ctx.stopService(Intent(ctx, TripService::class.java))
    }
}

fun formatDistance(m: Float): String = if (m >= 1000) "%.1f km".format(m / 1000f) else "${m.toInt()} m"

/** Follows GPS while a trip is active and rings when you get close to the destination. */
class TripService : Service(), LocationListener {
    companion object {
        private const val CH = "trips"
        private const val NOTIF_ID = 3200
        const val ACTION_STOP = "com.bigoke.twenty4hours.TRIP_STOP"
    }

    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Stores.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "On the way", NotificationManager.IMPORTANCE_LOW))
        }
        if (intent?.action == ACTION_STOP) { TripStore.stop(this); goForeground(build()); stopSelf(); return START_NOT_STICKY }
        if (TripStore.trip.value == null) TripStore.load()
        goForeground(build())
        if (TripStore.trip.value == null) { stopSelf(); return START_NOT_STICKY }
        if (lm == null) {
            lm = getSystemService(LocationManager::class.java)
            try {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                    if (lm?.isProviderEnabled(p) == true) lm?.requestLocationUpdates(p, 5_000L, 10f, this, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                TripStore.stop(this); stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val t = TripStore.trip.value ?: run { stopSelf(); return }
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, t.lat, t.lon, out)
        TripStore.distance.value = out[0]
        if (out[0] <= t.radiusM) {
            // Arrived: ring like an alarm, then end the trip
            ContextCompat.startForegroundService(
                this, Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_RING_TRIP)
                    .putExtra(AlarmService.EXTRA_TRIP_NAME, t.name)
            )
            TripStore.set(null)
            stopSelf()
        } else {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun build(): Notification {
        val t = TripStore.trip.value
        val d = TripStore.distance.value
        val open = PendingIntent.getActivity(
            this, 810, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TRIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 811, Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(Notifier.AMBER)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(if (t != null) "On the way to ${t.name}" else "On the way")
            .setContentText(
                when {
                    t == null -> "Finishing"
                    d == null -> "Finding your location…"
                    else -> "${formatDistance(d)} to go · rings within ${formatDistance(t.radiusM.toFloat())}"
                }
            )
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); return } catch (_: Exception) {}
        }
        try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        runCatching { lm?.removeUpdates(this) }
        super.onDestroy()
    }
}
