package com.bigoke.twenty4hours.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Shared SharedPreferences file for every store. */
object Prefs {
    private const val NAME = "twenty4hours"
    lateinit var sp: SharedPreferences
        private set
    lateinit var appContext: Context
        private set

    val ready: Boolean get() = ::sp.isInitialized

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        sp = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }
}

/** Initialise every store once per process. Safe to call repeatedly. */
object Stores {
    /** Called after alarms or timers change (used to refresh the home-screen widget). */
    var onAlarmsChanged: (() -> Unit)? = null
    /** Called after the world-clock city list changes. */
    var onWorldChanged: (() -> Unit)? = null

    /** Re-read every store from storage (after restoring a backup). */
    @Synchronized
    fun reload() {
        AppSettings.load()
        AlarmStore.load()
        TimerStore.load()
        StopwatchStore.load()
        WorldStore.load()
        EventLog.load()
        PresetStore.load()
        SessionStore.load()
        com.bigoke.twenty4hours.alarm.TripStore.load()
        onAlarmsChanged?.invoke()
        onWorldChanged?.invoke()
    }

    @Synchronized
    fun init(context: Context) {
        if (Prefs.ready) return
        Prefs.init(context)
        AppSettings.load()
        AlarmStore.load()
        TimerStore.load()
        StopwatchStore.load()
        WorldStore.load()
        EventLog.load()
        PresetStore.load()
        SessionStore.load()
        com.bigoke.twenty4hours.alarm.TripStore.load()
    }
}

/** Alarms and reminders, as JSON in SharedPreferences. */
object AlarmStore {
    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_SETUP_SHOWN = "setup_shown"

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    /** Kept for older call sites. */
    fun init(context: Context) = Stores.init(context)

    internal fun load() {
        val raw = Prefs.sp.getString(KEY_ALARMS, null)
        _alarms.value = try {
            if (raw == null) emptyList() else {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.sp.edit().putString(KEY_ALARMS, arr.toString()).apply()
        _alarms.value = list
        Stores.onAlarmsChanged?.invoke()
    }

    fun get(id: Int): Alarm? = _alarms.value.firstOrNull { it.id == id }

    @Synchronized
    fun newId(): Int {
        val id = Prefs.sp.getInt(KEY_NEXT_ID, 1)
        Prefs.sp.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    @Synchronized
    fun upsert(alarm: Alarm) {
        val list = _alarms.value.toMutableList()
        val i = list.indexOfFirst { it.id == alarm.id }
        if (i >= 0) list[i] = alarm else list.add(alarm)
        save(list)
    }

    @Synchronized
    fun delete(id: Int) {
        save(_alarms.value.filterNot { it.id == id })
    }

    var setupShown: Boolean
        get() = Prefs.sp.getBoolean(KEY_SETUP_SHOWN, false)
        set(v) { Prefs.sp.edit().putBoolean(KEY_SETUP_SHOWN, v).apply() }
}

/** App-wide defaults and behaviour. */
data class Settings(
    val defaultRings: Int = 1,
    val defaultInterval: Int = 5,
    val defaultSnooze: Int = 10,
    val defaultVibrate: Boolean = true,
    val gradualSec: Int = 20,
    val ringTimeoutMin: Int = 5,
    val upcomingLeadMin: Int = 60,
    val volumeButtons: VolumeAction = VolumeAction.SNOOZE,
    /** Holiday mode: no alarm rings before this time (epoch millis). 0 = off. */
    val pausedUntil: Long = 0L,
    /** Alarm groups that are switched off. */
    val pausedGroups: Set<String> = emptySet(),
    val accent: Accent = Accent.AMBER,
    /** Where sun times and weather come from. */
    val useDeviceLocation: Boolean = true,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val placeName: String = "",
    val calendarOnDial: Boolean = false,
    val sunOnDial: Boolean = true,
    val readSummaryAloud: Boolean = false,
    val skipHolidaysDefault: Boolean = false,
) {
    val hasPlace: Boolean get() = !lat.isNaN() && !lon.isNaN()
}

enum class VolumeAction { NOTHING, SNOOZE, STOP }

/** Accent colours (ARGB) — main, light, dark. */
enum class Accent(val title: String, val main: Long, val light: Long, val dark: Long) {
    AMBER("Amber", 0xFFFF8A1F, 0xFFFFB25E, 0xFFE56A0A),
    MINT("Mint", 0xFF2FC79A, 0xFF7FE3C3, 0xFF14936F),
    ICE("Ice", 0xFF4AA8FF, 0xFF93CBFF, 0xFF1E74C9),
    ROSE("Rose", 0xFFFF5C8A, 0xFFFF9AB6, 0xFFD13466),
    VIOLET("Violet", 0xFF9C7BFF, 0xFFC4B1FF, 0xFF6E4CE0),
}

object AppSettings {
    private val _s = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = _s.asStateFlow()
    val value: Settings get() = _s.value

    internal fun load() {
        val sp = Prefs.sp
        _s.value = Settings(
            defaultRings = sp.getInt("s_rings", 1),
            defaultInterval = sp.getInt("s_interval", 5),
            defaultSnooze = sp.getInt("s_snooze", 10),
            defaultVibrate = sp.getBoolean("s_vibrate", true),
            gradualSec = sp.getInt("s_gradual", 20),
            ringTimeoutMin = sp.getInt("s_timeout", 5),
            upcomingLeadMin = sp.getInt("s_upcoming", 60),
            volumeButtons = runCatching { VolumeAction.valueOf(sp.getString("s_volume", "SNOOZE")!!) }
                .getOrDefault(VolumeAction.SNOOZE),
            pausedUntil = sp.getLong("s_paused_until", 0L),
            pausedGroups = sp.getStringSet("s_paused_groups", emptySet())?.toSet() ?: emptySet(),
            accent = runCatching { Accent.valueOf(sp.getString("s_accent", "AMBER")!!) }.getOrDefault(Accent.AMBER),
            useDeviceLocation = sp.getBoolean("s_use_loc", true),
            lat = sp.getFloat("s_lat", Float.NaN).toDouble(),
            lon = sp.getFloat("s_lon", Float.NaN).toDouble(),
            placeName = sp.getString("s_place", "") ?: "",
            calendarOnDial = sp.getBoolean("s_cal", false),
            sunOnDial = sp.getBoolean("s_sun", true),
            readSummaryAloud = sp.getBoolean("s_read", false),
            skipHolidaysDefault = sp.getBoolean("s_skiphol", false),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val n = f(_s.value)
        Prefs.sp.edit()
            .putInt("s_rings", n.defaultRings)
            .putInt("s_interval", n.defaultInterval)
            .putInt("s_snooze", n.defaultSnooze)
            .putBoolean("s_vibrate", n.defaultVibrate)
            .putInt("s_gradual", n.gradualSec)
            .putInt("s_timeout", n.ringTimeoutMin)
            .putInt("s_upcoming", n.upcomingLeadMin)
            .putString("s_volume", n.volumeButtons.name)
            .putLong("s_paused_until", n.pausedUntil)
            .putStringSet("s_paused_groups", n.pausedGroups)
            .putString("s_accent", n.accent.name)
            .putBoolean("s_use_loc", n.useDeviceLocation)
            .putFloat("s_lat", n.lat.toFloat())
            .putFloat("s_lon", n.lon.toFloat())
            .putString("s_place", n.placeName)
            .putBoolean("s_cal", n.calendarOnDial)
            .putBoolean("s_sun", n.sunOnDial)
            .putBoolean("s_read", n.readSummaryAloud)
            .putBoolean("s_skiphol", n.skipHolidaysDefault)
            .apply()
        _s.value = n
    }
}
