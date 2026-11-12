package com.bigoke.twenty4hours.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Small JSON-in-SharedPreferences store. Alarms are few, so this stays simple and fast. */
object AlarmStore {
    private const val PREFS = "twenty4hours"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_SETUP_SHOWN = "setup_shown"

    private lateinit var prefs: SharedPreferences
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _alarms.value = load()
    }

    private fun load(): List<Alarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ALARMS, arr.toString()).apply()
        _alarms.value = list
    }

    fun get(id: Int): Alarm? = _alarms.value.firstOrNull { it.id == id }

    @Synchronized
    fun newId(): Int {
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).apply()
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
        get() = prefs.getBoolean(KEY_SETUP_SHOWN, false)
        set(v) { prefs.edit().putBoolean(KEY_SETUP_SHOWN, v).apply() }
}
