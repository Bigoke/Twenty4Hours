package com.bigoke.twenty4hours.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** A calendar event, in minutes from local midnight. */
data class CalEvent(val title: String, val startMin: Int, val endMin: Int, val allDay: Boolean, val color: Int)

object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events on [day] from all visible calendars. Empty without permission. */
    fun eventsOn(ctx: Context, day: LocalDate = LocalDate.now()): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val zone = ZoneId.systemDefault()
        val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = mutableListOf<CalEvent>()
        try {
            ctx.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "Event"
                    val b = c.getLong(1)
                    val e = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    fun mins(ms: Long): Int {
                        if (ms <= begin) return 0
                        if (ms >= end) return 1440
                        val t = Instant.ofEpochMilli(ms).atZone(zone)
                        return t.hour * 60 + t.minute
                    }
                    out += CalEvent(title, if (allDay) 0 else mins(b), if (allDay) 1440 else mins(e), allDay, c.getInt(4))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

data class Weather(
    val tempC: Int,
    val code: Int,
    val highC: Int,
    val lowC: Int,
    val rainChance: Int,
) {
    val description: String get() = describe(code)

    companion object {
        fun describe(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67 -> "Rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorms"
            else -> "Weather"
        }
    }
}

/** Free weather from Open-Meteo (no account or key). Call off the main thread. */
object WeatherRepo {
    fun fetch(place: GeoPlace): Weather? = try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, place.lat, place.lon) +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&forecast_days=1&timezone=auto"
        )
        val con = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
        }
        val body = con.inputStream.bufferedReader().use { it.readText() }
        con.disconnect()
        val o = JSONObject(body)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        Weather(
            tempC = cur.getDouble("temperature_2m").roundToInt(),
            code = cur.getInt("weather_code"),
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            rainChance = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
        )
    } catch (e: Exception) {
        null
    }
}
