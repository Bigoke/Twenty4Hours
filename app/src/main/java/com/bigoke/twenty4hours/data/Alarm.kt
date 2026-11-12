package com.bigoke.twenty4hours.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime

/**
 * One alarm on the 24-hour dial.
 *
 * @param days repeat days as java.time DayOfWeek values (1 = Monday … 7 = Sunday). Empty = once.
 * @param rings how many times the alarm rings in a row.
 * @param intervalMin minutes between rings (also the snooze length).
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean = true,
    val days: Set<Int> = emptySet(),
    val rings: Int = 1,
    val intervalMin: Int = 5,
) {
    val minuteOfDay: Int get() = hour * 60 + minute
    val repeating: Boolean get() = days.isNotEmpty()
    val displayLabel: String get() = label.ifBlank { "Alarm" }
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    /** Next moment (strictly after [from]) this alarm should start ringing. */
    fun nextTrigger(from: ZonedDateTime): ZonedDateTime {
        var t = from.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(from)) t = t.plusDays(1)
        if (days.isNotEmpty()) {
            var guard = 0
            while (t.dayOfWeek.value !in days && guard < 8) {
                t = t.plusDays(1); guard++
            }
        }
        return t
    }

    fun repeatText(): String {
        val base = when {
            days.isEmpty() -> "Once"
            days.size == 7 -> "Every day"
            days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
            days == setOf(6, 7) -> "Weekends"
            else -> days.sorted().joinToString(" ") { DAY_SHORT[it - 1] }
        }
        return if (rings > 1) "$base · $rings rings" else base
    }

    fun ringsText(): String =
        if (rings > 1) "$rings rings · every $intervalMin min" else "1 ring"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("enabled", enabled)
        put("days", JSONArray(days.sorted()))
        put("rings", rings)
        put("intervalMin", intervalMin)
    }

    companion object {
        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun fromJson(o: JSONObject): Alarm {
            val d = o.optJSONArray("days") ?: JSONArray()
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                label = o.optString("label", ""),
                enabled = o.optBoolean("enabled", true),
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                rings = o.optInt("rings", 1).coerceIn(1, 10),
                intervalMin = o.optInt("intervalMin", 5).coerceIn(1, 60),
            )
        }
    }
}

/** "4h 56m", "56m", "2d 3h", "now". */
fun formatCountdown(from: ZonedDateTime, to: ZonedDateTime): String {
    val total = Duration.between(from, to).toMinutes().let {
        // round up so 07:00 at 02:04:30 reads 4h 56m like a person would expect
        if (Duration.between(from, to).seconds % 60 > 0) it + 1 else it
    }
    if (total <= 0) return "now"
    val d = total / (60 * 24)
    val h = (total / 60) % 24
    val m = total % 60
    return when {
        d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        else -> "${m}m"
    }
}
