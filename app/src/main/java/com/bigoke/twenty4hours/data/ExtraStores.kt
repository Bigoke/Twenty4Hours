package com.bigoke.twenty4hours.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------- event log (sleep stats)

enum class EventType { STOP, SNOOZE, BEDTIME }

data class LogEvent(
    val type: EventType,
    val at: Long,
    val alarmId: Int,
    /** when the alarm was set for (epoch millis), 0 if unknown */
    val scheduledAt: Long,
    val label: String,
) {
    fun toJson() = JSONObject().apply {
        put("t", type.name); put("at", at); put("id", alarmId); put("s", scheduledAt); put("l", label)
    }

    companion object {
        fun fromJson(o: JSONObject) = LogEvent(
            runCatching { EventType.valueOf(o.getString("t")) }.getOrDefault(EventType.STOP),
            o.optLong("at"), o.optInt("id"), o.optLong("s"), o.optString("l", "")
        )
    }
}

object EventLog {
    private const val KEY = "event_log"
    private const val MAX = 400
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    internal fun load() {
        _events.value = try {
            JSONArray(Prefs.sp.getString(KEY, "[]")).let { a -> (0 until a.length()).map { LogEvent.fromJson(a.getJSONObject(it)) } }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun add(e: LogEvent) {
        val l = (_events.value + e).takeLast(MAX)
        save(l)
    }

    internal fun save(l: List<LogEvent>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        Prefs.sp.edit().putString(KEY, a.toString()).apply()
        _events.value = l
    }

    fun clear() = save(emptyList())
}

// ------------------------------------------------------------- saved timer presets

data class Preset(val id: Int, val name: String, val ms: Long)

object PresetStore {
    private const val KEY = "presets"
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val DEFAULTS = listOf(
        Preset(1, "Soft egg", 390_000L),
        Preset(2, "Hard egg", 600_000L),
        Preset(3, "Pasta", 660_000L),
        Preset(4, "Rice", 1_080_000L),
        Preset(5, "Tea", 240_000L),
        Preset(6, "Power nap", 1_200_000L),
    )

    internal fun load() {
        val raw = Prefs.sp.getString(KEY, null)
        _presets.value = if (raw == null) DEFAULTS else try {
            JSONArray(raw).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { Preset(it.getInt("id"), it.getString("n"), it.getLong("ms")) } }
            }
        } catch (e: Exception) { DEFAULTS }
    }

    private fun save(l: List<Preset>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("ms", it.ms)) }
        Prefs.sp.edit().putString(KEY, a.toString()).apply()
        _presets.value = l
    }

    fun add(name: String, ms: Long) {
        val id = (_presets.value.maxOfOrNull { it.id } ?: 0) + 1
        save(_presets.value + Preset(id, name.ifBlank { formatHms(ms) }, ms))
    }

    fun remove(id: Int) = save(_presets.value.filterNot { it.id == id })
}

// ------------------------------------------------------------- focus & interval sessions

enum class SessionKind { FOCUS, INTERVAL }

enum class PhaseKind { PREP, WORK, REST, LONG_BREAK }

data class Phase(val kind: PhaseKind, val ms: Long, val round: Int)

data class FocusConfig(val focusMin: Int = 25, val breakMin: Int = 5, val longBreakMin: Int = 15, val rounds: Int = 4)

data class IntervalConfig(val workSec: Int = 40, val restSec: Int = 20, val rounds: Int = 8, val prepSec: Int = 10, val voice: Boolean = true)

data class SessionState(
    val kind: SessionKind = SessionKind.FOCUS,
    val phases: List<Phase> = emptyList(),
    val index: Int = 0,
    val running: Boolean = false,
    /** wall-clock end of the current phase while running */
    val phaseEnd: Long = 0L,
    /** remaining of the current phase while paused */
    val remaining: Long = 0L,
) {
    val active: Boolean get() = phases.isNotEmpty() && index < phases.size
    val phase: Phase? get() = phases.getOrNull(index)
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        if (running) (phaseEnd - now).coerceAtLeast(0) else remaining
    val totalRounds: Int get() = phases.maxOfOrNull { it.round } ?: 0

    fun toJson() = JSONObject().apply {
        put("k", kind.name); put("i", index); put("r", running); put("e", phaseEnd); put("rem", remaining)
        val a = JSONArray()
        phases.forEach { a.put(JSONObject().put("k", it.kind.name).put("ms", it.ms).put("r", it.round)) }
        put("p", a)
    }

    companion object {
        fun fromJson(o: JSONObject): SessionState {
            val a = o.optJSONArray("p") ?: JSONArray()
            return SessionState(
                runCatching { SessionKind.valueOf(o.getString("k")) }.getOrDefault(SessionKind.FOCUS),
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { Phase(PhaseKind.valueOf(it.getString("k")), it.getLong("ms"), it.getInt("r")) }
                },
                o.optInt("i"), o.optBoolean("r"), o.optLong("e"), o.optLong("rem")
            )
        }

        fun focus(c: FocusConfig): List<Phase> = buildList {
            for (r in 1..c.rounds) {
                add(Phase(PhaseKind.WORK, c.focusMin * 60_000L, r))
                if (r < c.rounds) add(Phase(PhaseKind.REST, c.breakMin * 60_000L, r))
                else add(Phase(PhaseKind.LONG_BREAK, c.longBreakMin * 60_000L, r))
            }
        }

        fun interval(c: IntervalConfig): List<Phase> = buildList {
            if (c.prepSec > 0) add(Phase(PhaseKind.PREP, c.prepSec * 1000L, 0))
            for (r in 1..c.rounds) {
                add(Phase(PhaseKind.WORK, c.workSec * 1000L, r))
                if (r < c.rounds && c.restSec > 0) add(Phase(PhaseKind.REST, c.restSec * 1000L, r))
            }
        }
    }
}

object SessionStore {
    private val _s = MutableStateFlow(SessionState())
    val flow: StateFlow<SessionState> = _s.asStateFlow()
    val value get() = _s.value

    private val _focus = MutableStateFlow(FocusConfig())
    val focusConfig: StateFlow<FocusConfig> = _focus.asStateFlow()
    private val _interval = MutableStateFlow(IntervalConfig())
    val intervalConfig: StateFlow<IntervalConfig> = _interval.asStateFlow()

    internal fun load() {
        val sp = Prefs.sp
        _s.value = try {
            sp.getString("session", null)?.let { SessionState.fromJson(JSONObject(it)) } ?: SessionState()
        } catch (e: Exception) { SessionState() }
        _focus.value = FocusConfig(sp.getInt("f_focus", 25), sp.getInt("f_break", 5), sp.getInt("f_long", 15), sp.getInt("f_rounds", 4))
        _interval.value = IntervalConfig(
            sp.getInt("i_work", 40), sp.getInt("i_rest", 20), sp.getInt("i_rounds", 8), sp.getInt("i_prep", 10),
            sp.getBoolean("i_voice", true)
        )
    }

    fun set(s: SessionState) {
        Prefs.sp.edit().putString("session", s.toJson().toString()).apply()
        _s.value = s
    }

    fun setFocus(c: FocusConfig) {
        Prefs.sp.edit().putInt("f_focus", c.focusMin).putInt("f_break", c.breakMin)
            .putInt("f_long", c.longBreakMin).putInt("f_rounds", c.rounds).apply()
        _focus.value = c
    }

    fun setInterval(c: IntervalConfig) {
        Prefs.sp.edit().putInt("i_work", c.workSec).putInt("i_rest", c.restSec).putInt("i_rounds", c.rounds)
            .putInt("i_prep", c.prepSec).putBoolean("i_voice", c.voice).apply()
        _interval.value = c
    }
}
