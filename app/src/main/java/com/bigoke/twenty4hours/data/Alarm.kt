package com.bigoke.twenty4hours.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AlarmKind { ALARM, REMINDER }

enum class Challenge(val title: String) {
    NONE("None"), MATH("Math"), QR("Scan code"), SHAKE("Shake"), STEPS("Walk")
}

/** Robot voice styles (see RobotVoice). */
enum class VoiceStyle(val title: String) { DROID("Droid"), CLASSIC("Classic robot"), VOCODER("Vocoder") }

/** How an alarm repeats. WEEKLY with no days = once. */
enum class RepeatMode { WEEKLY, EVERY_N, WEEK_AB, DATE }

/** Special meaning for the dial (bedtime arc, sleep length). */
enum class Role { NONE, BEDTIME, WAKE }

/**
 * One entry on the 24-hour dial.
 *
 * @param days repeat days as java.time DayOfWeek values (1 = Monday … 7 = Sunday).
 * @param daysB the second week's days for WEEK_AB schedules.
 * @param anchorDay epoch day the EVERY_N / WEEK_AB pattern counts from.
 * @param dateEpochDay the single date for DATE alarms.
 * @param wakeWindowMin start ringing softly this many minutes early (smart wake).
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
    val repeatMode: RepeatMode = RepeatMode.WEEKLY,
    val everyN: Int = 2,
    val anchorDay: Long = LocalDate.now().toEpochDay(),
    val daysB: Set<Int> = emptySet(),
    val dateEpochDay: Long = -1L,
    val skipHolidays: Boolean = false,
    val wakeWindowMin: Int = 0,
    val sunrise: Boolean = false,
    val morningSummary: Boolean = false,
    val group: String = "",
    val qrCode: String? = null,
    val role: Role = Role.NONE,
    /** Speak the label out loud in a robot voice while ringing. */
    val robotVoice: Boolean = false,
    val robotStyle: VoiceStyle = VoiceStyle.DROID,
    /** effect strength 0–100 */
    val robotLevel: Int = 60,
) {
    val minuteOfDay: Int get() = hour * 60 + minute
    val repeating: Boolean
        get() = when (repeatMode) {
            RepeatMode.WEEKLY -> days.isNotEmpty()
            RepeatMode.EVERY_N, RepeatMode.WEEK_AB -> true
            RepeatMode.DATE -> false
        }
    val isReminder: Boolean get() = kind == AlarmKind.REMINDER
    val displayLabel: String get() = label.ifBlank { if (isReminder) "Reminder" else "Alarm" }
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    /** Switched on, and its group isn't paused. */
    val active: Boolean get() = enabled && (group.isBlank() || group !in AppSettings.value.pausedGroups)

    val isBedtime: Boolean
        get() = role == Role.BEDTIME || (role == Role.NONE && isReminder && label.contains("bed", ignoreCase = true))

    fun isSkipping(now: ZonedDateTime) = skipUntil > now.toInstant().toEpochMilli()

    /** Whether this alarm rings on [day] (ignoring time of day). */
    fun ringsOn(day: LocalDate): Boolean {
        val ok = when (repeatMode) {
            RepeatMode.DATE -> day.toEpochDay() == dateEpochDay
            RepeatMode.WEEKLY -> days.isEmpty() || day.dayOfWeek.value in days
            RepeatMode.EVERY_N -> {
                val diff = day.toEpochDay() - anchorDay
                diff >= 0 && diff % everyN.coerceAtLeast(1) == 0L
            }
            RepeatMode.WEEK_AB -> {
                val anchorMonday = LocalDate.ofEpochDay(anchorDay).with(DayOfWeek.MONDAY).toEpochDay()
                val week = Math.floorDiv(day.toEpochDay() - anchorMonday, 7L)
                day.dayOfWeek.value in (if (Math.floorMod(week, 2L) == 0L) days else daysB)
            }
        }
        if (!ok) return false
        if (repeating && skipHolidays && Holidays.isHoliday(day)) return false
        return true
    }

    /**
     * Next moment (strictly after [from]) this alarm is set for — after any "skip next",
     * holiday mode or skipped holiday. Returns null when it will never ring again.
     */
    fun nextTriggerOrNull(from: ZonedDateTime): ZonedDateTime? {
        var startMs = from.toInstant().toEpochMilli()
        if (skipUntil > startMs) startMs = skipUntil
        val pause = AppSettings.value.pausedUntil
        if (pause > startMs) startMs = pause
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMs), from.zone)
        val firstDay = start.toLocalDate()
        for (i in 0..800) {
            val day = firstDay.plusDays(i.toLong())
            val t = day.atTime(hour, minute).atZone(from.zone)
            if (!t.isAfter(start)) continue
            if (ringsOn(day)) return t
            if (repeatMode == RepeatMode.DATE && day.toEpochDay() > dateEpochDay) return null
        }
        return null
    }

    /** Like [nextTriggerOrNull] but never null (far future when it won't ring). */
    fun nextTrigger(from: ZonedDateTime): ZonedDateTime = nextTriggerOrNull(from) ?: from.plusYears(50)

    fun daysText(): String = when (repeatMode) {
        RepeatMode.DATE -> if (dateEpochDay >= 0)
            LocalDate.ofEpochDay(dateEpochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        else "Once"
        RepeatMode.EVERY_N -> if (everyN == 2) "Every other day" else "Every $everyN days"
        RepeatMode.WEEK_AB -> "Week A/B"
        RepeatMode.WEEKLY -> when {
            days.isEmpty() -> "Once"
            days.size == 7 -> "Every day"
            days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
            days == setOf(6, 7) -> "Weekends"
            else -> days.sorted().joinToString(" ") { DAY_SHORT[it - 1] }
        }
    }

    fun repeatText(): String {
        val parts = mutableListOf(daysText())
        if (isReminder) parts.add("reminder")
        else if (rings > 1) parts.add("$rings rings")
        if (challenge != Challenge.NONE && !isReminder) parts.add(challenge.title.lowercase())
        if (robotVoice && !isReminder) parts.add("robot voice")
        if (group.isNotBlank()) parts.add(group)
        return parts.joinToString(" · ")
    }

    fun ringsText(): String = when {
        isReminder -> "gentle reminder"
        rings > 1 -> "$rings rings · every $intervalMin min"
        wakeWindowMin > 0 -> "wakes gently from ${wakeWindowMin}m before"
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
        put("repeatMode", repeatMode.name)
        put("everyN", everyN)
        put("anchorDay", anchorDay)
        put("daysB", JSONArray(daysB.sorted()))
        put("dateEpochDay", dateEpochDay)
        put("skipHolidays", skipHolidays)
        put("wakeWindowMin", wakeWindowMin)
        put("sunrise", sunrise)
        put("morningSummary", morningSummary)
        put("group", group)
        qrCode?.let { put("qrCode", it) }
        put("role", role.name)
        put("robotVoice", robotVoice)
        put("robotStyle", robotStyle.name)
        put("robotLevel", robotLevel)
    }

    companion object {
        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        private fun ints(a: JSONArray?): Set<Int> =
            if (a == null) emptySet() else (0 until a.length()).map { a.getInt(it) }.toSet()

        fun fromJson(o: JSONObject): Alarm {
            val interval = o.optInt("intervalMin", 5).coerceIn(1, 60)
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                label = o.optString("label", ""),
                enabled = o.optBoolean("enabled", true),
                days = ints(o.optJSONArray("days")),
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
                repeatMode = runCatching { RepeatMode.valueOf(o.optString("repeatMode", "WEEKLY")) }.getOrDefault(RepeatMode.WEEKLY),
                everyN = o.optInt("everyN", 2).coerceIn(2, 30),
                anchorDay = o.optLong("anchorDay", LocalDate.now().toEpochDay()),
                daysB = ints(o.optJSONArray("daysB")),
                dateEpochDay = o.optLong("dateEpochDay", -1L),
                skipHolidays = o.optBoolean("skipHolidays", false),
                wakeWindowMin = o.optInt("wakeWindowMin", 0).coerceIn(0, 60),
                sunrise = o.optBoolean("sunrise", false),
                morningSummary = o.optBoolean("morningSummary", false),
                group = o.optString("group", ""),
                qrCode = o.optString("qrCode", "").ifBlank { null },
                role = runCatching { Role.valueOf(o.optString("role", "NONE")) }.getOrDefault(Role.NONE),
                robotVoice = o.optBoolean("robotVoice", false),
                robotStyle = runCatching { VoiceStyle.valueOf(o.optString("robotStyle", "DROID")) }.getOrDefault(VoiceStyle.DROID),
                robotLevel = o.optInt("robotLevel", 60).coerceIn(0, 100),
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
