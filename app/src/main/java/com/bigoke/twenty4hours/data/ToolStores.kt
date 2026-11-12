package com.bigoke.twenty4hours.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------------ timers

enum class TimerState { IDLE, RUNNING, PAUSED }

data class TimerItem(
    val id: Int,
    val label: String,
    val durationMs: Long,
    val state: TimerState = TimerState.IDLE,
    /** wall-clock end time while RUNNING */
    val endAt: Long = 0L,
    /** remaining time while PAUSED */
    val remainingMs: Long = 0L,
) {
    fun remaining(now: Long = System.currentTimeMillis()): Long = when (state) {
        TimerState.RUNNING -> (endAt - now).coerceAtLeast(0L)
        TimerState.PAUSED -> remainingMs
        TimerState.IDLE -> durationMs
    }

    val displayLabel: String get() = label.ifBlank { formatHms(durationMs) + " timer" }

    fun toJson() = JSONObject().apply {
        put("id", id); put("label", label); put("durationMs", durationMs)
        put("state", state.name); put("endAt", endAt); put("remainingMs", remainingMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = TimerItem(
            id = o.getInt("id"),
            label = o.optString("label", ""),
            durationMs = o.optLong("durationMs", 300_000L),
            state = runCatching { TimerState.valueOf(o.optString("state")) }.getOrDefault(TimerState.IDLE),
            endAt = o.optLong("endAt", 0L),
            remainingMs = o.optLong("remainingMs", 0L),
        )
    }
}

object TimerStore {
    private const val KEY = "timers"
    private const val KEY_ID = "timer_next_id"
    private const val KEY_SELECTED = "timer_selected"

    private val _timers = MutableStateFlow<List<TimerItem>>(emptyList())
    val timers: StateFlow<List<TimerItem>> = _timers.asStateFlow()

    private val _selected = MutableStateFlow(0)
    val selected: StateFlow<Int> = _selected.asStateFlow()

    internal fun load() {
        val raw = Prefs.sp.getString(KEY, null)
        var list = try {
            if (raw == null) emptyList() else JSONArray(raw).let { a ->
                (0 until a.length()).map { TimerItem.fromJson(a.getJSONObject(it)) }
            }
        } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) list = listOf(TimerItem(newIdRaw(), "", 5 * 60_000L))
        _timers.value = list
        _selected.value = Prefs.sp.getInt(KEY_SELECTED, list.first().id)
            .let { s -> if (list.any { it.id == s }) s else list.first().id }
        save(list)
    }

    private fun newIdRaw(): Int {
        val id = Prefs.sp.getInt(KEY_ID, 1)
        Prefs.sp.edit().putInt(KEY_ID, id + 1).apply()
        return id
    }

    private fun save(list: List<TimerItem>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        Prefs.sp.edit().putString(KEY, a.toString()).apply()
        _timers.value = list
    }

    fun get(id: Int) = _timers.value.firstOrNull { it.id == id }

    fun select(id: Int) {
        _selected.value = id
        Prefs.sp.edit().putInt(KEY_SELECTED, id).apply()
    }

    @Synchronized
    fun upsert(t: TimerItem) {
        val l = _timers.value.toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        save(l)
    }

    @Synchronized
    fun add(durationMs: Long, label: String = ""): TimerItem {
        val t = TimerItem(newIdRaw(), label, durationMs)
        upsert(t)
        select(t.id)
        return t
    }

    @Synchronized
    fun delete(id: Int) {
        val l = _timers.value.filterNot { it.id == id }
        if (l.isEmpty()) {
            val t = TimerItem(newIdRaw(), "", 5 * 60_000L)
            save(listOf(t)); select(t.id)
        } else {
            save(l)
            if (_selected.value == id) select(l.first().id)
        }
    }
}

// --------------------------------------------------------------- stopwatch

data class StopwatchData(
    val running: Boolean = false,
    /** SystemClock.elapsedRealtime() at last start */
    val startedAt: Long = 0L,
    /** time accumulated before the last start */
    val accumulated: Long = 0L,
    /** total elapsed at each lap press */
    val laps: List<Long> = emptyList(),
) {
    fun elapsed(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        accumulated + if (running) (nowElapsed - startedAt).coerceAtLeast(0) else 0
}

object StopwatchStore {
    private val _s = MutableStateFlow(StopwatchData())
    val flow: StateFlow<StopwatchData> = _s.asStateFlow()
    val value get() = _s.value

    internal fun load() {
        val sp = Prefs.sp
        val laps = try {
            JSONArray(sp.getString("sw_laps", "[]")).let { a -> (0 until a.length()).map { a.getLong(it) } }
        } catch (e: Exception) { emptyList() }
        var d = StopwatchData(
            running = sp.getBoolean("sw_running", false),
            startedAt = sp.getLong("sw_started", 0L),
            accumulated = sp.getLong("sw_acc", 0L),
            laps = laps,
        )
        // elapsedRealtime resets on reboot; a start time "in the future" means we rebooted.
        if (d.running && d.startedAt > SystemClock.elapsedRealtime()) {
            d = d.copy(running = false)
        }
        _s.value = d
    }

    internal fun set(d: StopwatchData) {
        val a = JSONArray(); d.laps.forEach { a.put(it) }
        Prefs.sp.edit()
            .putBoolean("sw_running", d.running)
            .putLong("sw_started", d.startedAt)
            .putLong("sw_acc", d.accumulated)
            .putString("sw_laps", a.toString())
            .apply()
        _s.value = d
    }

    fun start() {
        val d = _s.value
        if (!d.running) set(d.copy(running = true, startedAt = SystemClock.elapsedRealtime()))
    }

    fun pause() {
        val d = _s.value
        if (d.running) set(d.copy(running = false, accumulated = d.elapsed()))
    }

    fun lap() {
        val d = _s.value
        if (d.running) set(d.copy(laps = d.laps + d.elapsed()))
    }

    fun reset() = set(StopwatchData())
}

// ------------------------------------------------------------- world clock

object WorldStore {
    private val _zones = MutableStateFlow<List<String>>(emptyList())
    val zones: StateFlow<List<String>> = _zones.asStateFlow()

    internal fun load() {
        val raw = Prefs.sp.getString("world_zones", null)
        _zones.value = if (raw == null) listOf("Europe/London", "America/New_York", "Asia/Tokyo")
        else try {
            JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(l: List<String>) {
        val a = JSONArray(); l.forEach { a.put(it) }
        Prefs.sp.edit().putString("world_zones", a.toString()).apply()
        _zones.value = l
        Stores.onWorldChanged?.invoke()
    }

    fun add(zone: String) { if (zone !in _zones.value) save(_zones.value + zone) }
    fun remove(zone: String) = save(_zones.value - zone)
    fun move(from: Int, to: Int) {
        val l = _zones.value.toMutableList()
        if (from !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(from)); save(l)
    }
}
