package com.bigoke.twenty4hours.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

enum class AlarmKind { ALARM, REMINDER }

enum class Challenge { NONE, MATH }

/**
 * One entry on the 24-hour dial.
 *
 * @param days repeat days as java.time DayOfWeek values (1 = Monday … 7 = Sunday). Empty = once.
 * @param rings how many times the alarm rings in a row.
 * @param intervalMin minutes between those rings.
 * @param snoozeMin snooze length after the last ring.
 * @param soundUri ringtone; null = system default alarm sound.
 * @param skipUntil epoch millis; occurrences before this are skipped ("skip next").
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
    val kind: AlarmKind = AlarmKind.ALARM,
    val snoozeMin: Int = 10,
    val soundUri: String? = null,
    val soundName: String? = null,
    val vibrate: Boolean = true,
    val gradual: Boolean = true,
    val challenge: Challenge = Challenge.NONE,
    val skipUntil: Long = 0L,
) {
    val minuteOfDay: Int get() = hour * 60 + minute
    val repeating: Boolean get() = days.isNotEmpty()
    val isReminder: Boolean get() = kind == AlarmKind.REMINDER
    val displayLabel: String get() = label.ifBlank { if (isReminder) "Reminder" else "Alarm" }
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    fun isSkipping(now: ZonedDateTime) = skipUntil > now.toInstant().toEpochMilli()

    /** Next moment (strictly after [from], and after any skip) this alarm should start ringing. */
    fun nextTrigger(from: ZonedDateTime): ZonedDateTime {
        val start = if (skipUntil > from.toInstant().toEpochMilli())
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(skipUntil), from.zone) else from
        var t = start.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(start)) t = t.plusDays(1)
        if (days.isNotEmpty()) {
            var guard = 0
            while (t.dayOfWeek.value !in days && guard < 8) {
                t = t.plusDays(1); guard++
            }
        }
        return t
    }

    fun daysText(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(" ") { DAY_SHORT[it - 1] }
    }

    fun repeatText(): String {
        val parts = mutableListOf(daysText())
        if (isReminder) parts.add("reminder")
        else if (rings > 1) parts.add("$rings rings")
        if (challenge == Challenge.MATH && !isReminder) parts.add("math")
        return parts.joinToString(" · ")
    }

    fun ringsText(): String = when {
        isReminder -> "gentle reminder"
        rings > 1 -> "$rings rings · every $intervalMin min"
        else -> "1 ring · snooze $snoozeMin min"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("enabled", enabled)
        put("days", JSONArray(days.sorted()))
        put("rings", rings)
        put("intervalMin", intervalMin)
        put("kind", kind.name)
        put("snoozeMin", snoozeMin)
        soundUri?.let { put("soundUri", it) }
        soundName?.let { put("soundName", it) }
        put("vibrate", vibrate)
        put("gradual", gradual)
        put("challenge", challenge.name)
        put("skipUntil", skipUntil)
    }

    companion object {
        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun fromJson(o: JSONObject): Alarm {
            val d = o.optJSONArray("days") ?: JSONArray()
            val interval = o.optInt("intervalMin", 5).coerceIn(1, 60)
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                label = o.optString("label", ""),
                enabled = o.optBoolean("enabled", true),
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                rings = o.optInt("rings", 1).coerceIn(1, 10),
                intervalMin = interval,
                kind = runCatching { AlarmKind.valueOf(o.optString("kind", "ALARM")) }.getOrDefault(AlarmKind.ALARM),
                snoozeMin = o.optInt("snoozeMin", interval).coerceIn(1, 60),
                soundUri = o.optString("soundUri", "").ifBlank { null },
                soundName = o.optString("soundName", "").ifBlank { null },
                vibrate = o.optBoolean("vibrate", true),
                gradual = o.optBoolean("gradual", true),
                challenge = runCatching { Challenge.valueOf(o.optString("challenge", "NONE")) }.getOrDefault(Challenge.NONE),
                skipUntil = o.optLong("skipUntil", 0L),
            )
        }
    }
}

/** "4h 56m", "56m", "2d 3h", "now". */
fun formatCountdown(from: ZonedDateTime, to: ZonedDateTime): String {
    val dur = Duration.between(from, to)
    val total = dur.toMinutes() + if (dur.seconds % 60 > 0) 1 else 0
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

/** 1:05:09 / 5:09 for timers and stopwatch. */
fun formatHms(ms: Long, forceHours: Boolean = false): String {
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec / 60) % 60
    val s = totalSec % 60
    return if (h > 0 || forceHours) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Stopwatch style 01:23.45 (centiseconds), with hours when needed. */
fun formatStopwatch(ms: Long): String {
    val cs = (ms / 10) % 100
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec / 60) % 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%02d:%02d.%02d".format(m, s, cs)
}
