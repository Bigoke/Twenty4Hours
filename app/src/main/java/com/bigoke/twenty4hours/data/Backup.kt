package com.bigoke.twenty4hours.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Export / import everything (alarms, timers, cities, settings, history) as one JSON file. */
object Backup {
    private const val VERSION = 1

    fun export(ctx: Context, uri: Uri): Boolean = try {
        val o = JSONObject().put("app", "Twenty4Hours").put("version", VERSION)
        val data = JSONObject()
        Prefs.sp.all.forEach { (k, v) ->
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            data.put(k, e)
        }
        o.put("data", data)
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(o.toString(2).toByteArray()) }
        true
    } catch (e: Exception) {
        false
    }

    /** Replaces the current data with the backup. Call AlarmScheduler.rescheduleAll afterwards. */
    fun import(ctx: Context, uri: Uri): Boolean = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("empty")
        val o = JSONObject(text)
        require(o.optString("app") == "Twenty4Hours")
        val data = o.getJSONObject("data")
        val ed = Prefs.sp.edit().clear()
        data.keys().forEach { k ->
            val e = data.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "set" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        ed.commit()
        Stores.reload()
        true
    } catch (e: Exception) {
        false
    }
}
