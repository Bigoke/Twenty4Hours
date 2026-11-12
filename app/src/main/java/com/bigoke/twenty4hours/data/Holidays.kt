package com.bigoke.twenty4hours.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/** South African public holidays, including the "Sunday → Monday" rule. */
object Holidays {
    private val FIXED = listOf(
        MonthDay.of(1, 1) to "New Year's Day",
        MonthDay.of(3, 21) to "Human Rights Day",
        MonthDay.of(4, 27) to "Freedom Day",
        MonthDay.of(5, 1) to "Workers' Day",
        MonthDay.of(6, 16) to "Youth Day",
        MonthDay.of(8, 9) to "National Women's Day",
        MonthDay.of(9, 24) to "Heritage Day",
        MonthDay.of(12, 16) to "Day of Reconciliation",
        MonthDay.of(12, 25) to "Christmas Day",
        MonthDay.of(12, 26) to "Day of Goodwill",
    )

    private val cache = HashMap<Int, Map<LocalDate, String>>()

    /** Western (Gregorian) Easter Sunday. */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    @Synchronized
    fun forYear(year: Int): Map<LocalDate, String> = cache.getOrPut(year) {
        val map = LinkedHashMap<LocalDate, String>()
        FIXED.forEach { (md, name) ->
            val d = md.atYear(year)
            map[d] = name
            if (d.dayOfWeek == DayOfWeek.SUNDAY) map.putIfAbsent(d.plusDays(1), "$name (observed)")
        }
        val e = easter(year)
        map[e.minusDays(2)] = "Good Friday"
        map[e.plusDays(1)] = "Family Day"
        map.toSortedMap()
    }

    fun nameOf(day: LocalDate): String? = forYear(day.year)[day]

    fun isHoliday(day: LocalDate): Boolean = nameOf(day) != null

    /** The next few holidays from [from], for display. */
    fun upcoming(from: LocalDate, count: Int = 3): List<Pair<LocalDate, String>> =
        (forYear(from.year) + forYear(from.year + 1)).toList()
            .filter { !it.first.isBefore(from) }.sortedBy { it.first }.take(count)
}
