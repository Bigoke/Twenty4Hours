package com.bigoke.twenty4hours.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

data class GeoPlace(val lat: Double, val lon: Double, val name: String)

/** Where "here" is, for sun times and weather. */
object Places {
    /** Rough coordinates for time zones, used when the device location isn't available. */
    private val ZONES = mapOf(
        "Africa/Johannesburg" to GeoPlace(-26.20, 28.04, "Johannesburg"),
        "Africa/Maseru" to GeoPlace(-29.31, 27.48, "Maseru"),
        "Africa/Gaborone" to GeoPlace(-24.65, 25.91, "Gaborone"),
        "Africa/Windhoek" to GeoPlace(-22.56, 17.08, "Windhoek"),
        "Africa/Harare" to GeoPlace(-17.83, 31.05, "Harare"),
        "Africa/Maputo" to GeoPlace(-25.97, 32.57, "Maputo"),
        "Africa/Nairobi" to GeoPlace(-1.29, 36.82, "Nairobi"),
        "Africa/Lagos" to GeoPlace(6.52, 3.38, "Lagos"),
        "Africa/Cairo" to GeoPlace(30.04, 31.24, "Cairo"),
        "Europe/London" to GeoPlace(51.51, -0.13, "London"),
        "Europe/Paris" to GeoPlace(48.86, 2.35, "Paris"),
        "Europe/Berlin" to GeoPlace(52.52, 13.40, "Berlin"),
        "Europe/Amsterdam" to GeoPlace(52.37, 4.90, "Amsterdam"),
        "Europe/Madrid" to GeoPlace(40.42, -3.70, "Madrid"),
        "Europe/Rome" to GeoPlace(41.90, 12.50, "Rome"),
        "Europe/Lisbon" to GeoPlace(38.72, -9.14, "Lisbon"),
        "Europe/Moscow" to GeoPlace(55.76, 37.62, "Moscow"),
        "Asia/Dubai" to GeoPlace(25.20, 55.27, "Dubai"),
        "Asia/Kolkata" to GeoPlace(28.61, 77.21, "Delhi"),
        "Asia/Singapore" to GeoPlace(1.35, 103.82, "Singapore"),
        "Asia/Shanghai" to GeoPlace(31.23, 121.47, "Shanghai"),
        "Asia/Tokyo" to GeoPlace(35.68, 139.69, "Tokyo"),
        "Australia/Sydney" to GeoPlace(-33.87, 151.21, "Sydney"),
        "Australia/Perth" to GeoPlace(-31.95, 115.86, "Perth"),
        "Pacific/Auckland" to GeoPlace(-36.85, 174.76, "Auckland"),
        "America/New_York" to GeoPlace(40.71, -74.01, "New York"),
        "America/Chicago" to GeoPlace(41.88, -87.63, "Chicago"),
        "America/Denver" to GeoPlace(39.74, -104.99, "Denver"),
        "America/Los_Angeles" to GeoPlace(34.05, -118.24, "Los Angeles"),
        "America/Toronto" to GeoPlace(43.65, -79.38, "Toronto"),
        "America/Sao_Paulo" to GeoPlace(-23.55, -46.63, "São Paulo"),
        "America/Mexico_City" to GeoPlace(19.43, -99.13, "Mexico City"),
    )

    fun fromZone(zone: ZoneId = ZoneId.systemDefault()): GeoPlace? = ZONES[zone.id]

    /** The saved place, else the time zone's city. */
    fun current(): GeoPlace? {
        val s = AppSettings.value
        return if (s.hasPlace) GeoPlace(s.lat, s.lon, s.placeName.ifBlank { "Here" }) else fromZone()
    }

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Refresh the saved place from the device's last known location (no GPS fix needed). */
    @SuppressLint("MissingPermission")
    fun refreshFromDevice(ctx: Context): GeoPlace? {
        if (!AppSettings.value.useDeviceLocation || !hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: ""
        AppSettings.update { it.copy(lat = loc.latitude, lon = loc.longitude, placeName = name) }
        return GeoPlace(loc.latitude, loc.longitude, name)
    }
}

/** Sunrise and sunset as minutes after local midnight (null for polar day/night). */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double): Double { var x = v % m; if (x < 0) x += m; return x }

    private fun utHour(n: Int, lat: Double, lon: Double, rising: Boolean): Double? {
        val lngHour = lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        return norm(tt - lngHour, 24.0)
    }

    fun times(date: LocalDate, place: GeoPlace, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int>? {
        val offsetH = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val up = utHour(date.dayOfYear, place.lat, place.lon, true) ?: return null
        val down = utHour(date.dayOfYear, place.lat, place.lon, false) ?: return null
        fun toMin(h: Double) = (norm(h + offsetH, 24.0) * 60).toInt()
        return toMin(up) to toMin(down)
    }
}
