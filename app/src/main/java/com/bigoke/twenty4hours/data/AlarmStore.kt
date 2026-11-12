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

    @Synchronized
    fun init(context: Context) {
        if (Prefs.ready) return
        Prefs.init(context)
        AppSettings.load()
        AlarmStore.load()
        TimerStore.load()
        StopwatchStore.load()
        WorldStore.load()
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
)

enum class VolumeAction { NOTHING, SNOOZE, STOP }

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
            .apply()
        _s.value = n
    }
}
