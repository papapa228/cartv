package com.bmwflow.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

class City(val name: String, val lat: Double, val lon: Double)
class Day(val date: LocalDate, val code: Int, val tMax: Int, val tMin: Int)
class Forecast(val days: List<Day>, val fetchedAt: Long) {
    fun daysFrom(today: LocalDate): List<Day> = days.filter { !it.date.isBefore(today) }
}

fun trustedToday(): LocalDate =
    Instant.ofEpochMilli(TrustedClock.now()).atZone(ZoneId.systemDefault()).toLocalDate()

object WeatherIcons {
    /** Коды погоды WMO, которые возвращает Open-Meteo. */
    fun forCode(code: Int): Int = when (code) {
        0 -> R.drawable.w_sun
        1, 2 -> R.drawable.w_partly
        3 -> R.drawable.w_cloud
        45, 48 -> R.drawable.w_fog
        in 51..67, in 80..82 -> R.drawable.w_rain
        in 71..77, 85, 86 -> R.drawable.w_snow
        in 95..99 -> R.drawable.w_storm
        else -> R.drawable.w_cloud
    }
}

fun formatTemp(t: Int): String = when {
    t > 0 -> "+$t°"
    t < 0 -> "−${-t}°"
    else -> "0°"
}

class WeatherRepository(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("flow_weather", 0)

    fun cached(): Forecast? {
        val raw = p.getString("json", null) ?: return null
        return try {
            parse(raw, p.getLong("fetched", 0))
        } catch (e: Exception) {
            null
        }
    }

    fun isStale(maxAgeMs: Long): Boolean {
        val c = cached() ?: return true
        return isDirty() || kotlin.math.abs(TrustedClock.now() - c.fetchedAt) > maxAgeMs
    }

    /** Блокирующий запрос — только из фонового потока. */
    fun fetch(lat: Double, lon: Double): Forecast? = try {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5",
            lat, lon
        )
        val body = get(url)
        if (body == null) null else {
            val now = TrustedClock.now()
            val f = parse(body, now)
            p.edit().putString("json", body).putLong("fetched", now).putBoolean("dirty", false).apply()
            f
        }
    } catch (e: Exception) {
        Log.w("Weather", "fetch failed", e)
        null
    }

    fun searchCity(query: String): List<City> = try {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&format=json&name=" +
                URLEncoder.encode(query, "UTF-8")
        val body = get(url)
        val arr = if (body == null) null else JSONObject(body).optJSONArray("results")
        if (arr == null) emptyList() else (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = listOf(o.optString("name"), o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() }.distinct()
            City(parts.joinToString(", "), o.getDouble("latitude"), o.getDouble("longitude"))
        }
    } catch (e: Exception) {
        Log.w("Weather", "geocoding failed", e)
        emptyList()
    }

    fun manualCity(): City? {
        val name = p.getString("city_name", null) ?: return null
        return City(
            name,
            java.lang.Double.longBitsToDouble(p.getLong("city_lat", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("city_lon", 0))
        )
    }

    fun setManualCity(c: City?) {
        val e = p.edit()
        if (c == null) e.remove("city_name").remove("city_lat").remove("city_lon")
        else e.putString("city_name", c.name)
            .putLong("city_lat", java.lang.Double.doubleToRawLongBits(c.lat))
            .putLong("city_lon", java.lang.Double.doubleToRawLongBits(c.lon))
        e.putBoolean("dirty", true).apply()
    }

    fun savedCoords(): Pair<Double, Double>? {
        if (!p.contains("last_lat")) return null
        return java.lang.Double.longBitsToDouble(p.getLong("last_lat", 0)) to
                java.lang.Double.longBitsToDouble(p.getLong("last_lon", 0))
    }

    fun saveCoords(lat: Double, lon: Double) {
        p.edit().putLong("last_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("last_lon", java.lang.Double.doubleToRawLongBits(lon)).apply()
    }

    fun markDirty() = p.edit().putBoolean("dirty", true).apply()
    private fun isDirty() = p.getBoolean("dirty", false)

    private fun get(url: String): String? =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }

    private fun parse(raw: String, fetched: Long): Forecast {
        val d = JSONObject(raw).getJSONObject("daily")
        val time = d.getJSONArray("time")
        val code = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val days = ArrayList<Day>()
        for (i in 0 until time.length()) {
            val hi = max.optDouble(i, Double.NaN)
            val lo = min.optDouble(i, Double.NaN)
            if (hi.isNaN() || lo.isNaN()) continue
            days.add(Day(LocalDate.parse(time.getString(i)), code.optInt(i, 3), hi.roundToInt(), lo.roundToInt()))
        }
        return Forecast(days, fetched)
    }
}

class LocationHelper(ctx: Context) {
    private val app = ctx.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    fun hasPermission(): Boolean =
        app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun anyProviderEnabled(): Boolean = try {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    /** Одно обновление координат; вызывается с главного потока, ответ — на главном потоке. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun requestSingle(timeoutMs: Long, cb: (Location?) -> Unit) {
        if (!hasPermission()) {
            cb(null); return
        }
        val provider = when {
            safeEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            safeEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> {
                cb(null); return
            }
        }
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                lm.removeUpdates(this)
                cb(location)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            cb(null); return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                lm.removeUpdates(listener)
                cb(lastKnown())
            }
        }, timeoutMs)
    }

    private fun safeEnabled(p: String) = try {
        lm.isProviderEnabled(p)
    } catch (e: Exception) {
        false
    }
}
