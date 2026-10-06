package com.starsdom.trail.weather

import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.net.idempotent
import io.ktor.client.statement.bodyAsText
import java.io.File

/** Notification id of 出行提醒 raised while recording. */
const val WEATHER_NOTIFICATION = 3

/**
 * Fetches the next [hours] of forecast at (lat, lon), from the current whole hour. With a [cache] file, a good answer
 * is kept there, and without network (or when the server can't answer) the cached one comes back marked offline,
 * wherever it was for. Nothing fetched or cached: throws what went wrong. The answer is kept as sent, for the cache.
 */
suspend fun fetchWeather(
  api: BaseApi, lat: Double, lon: Double, ele: Double?, cache: File? = null,
  hours: Int = WEATHER_HOURS, now: Long = System.currentTimeMillis(),
): PlaceWeather {
  val start = now - now.mod(3_600_000L)
  return try {
    val response = api.preparePostWeather(weatherRequestDto = weatherRequest(lat, lon, start, hours)) { idempotent() }.execute { it.bodyAsText() }
    val w = PlaceWeather(lat, lon, ele, now, start, response)
    w.forecast // parses, so a bad answer isn't cached
    cache?.let { f ->
      f.parentFile!!.mkdirs()
      File(f.path + ".tmp").apply { writeText(writeWeather(w)) }.renameTo(f)
    }
    w
  } catch (e: Exception) {
    // Offline, or the server couldn't answer (or answered nonsense): the last good forecast.
    cache?.let(::cachedWeather) ?: throw e
  }
}

/** What [fetchWeather] last kept in [file], marked offline. */
fun cachedWeather(file: File): PlaceWeather? = runCatching { readWeather(file.readText()) }.getOrNull()?.copy(offline = true)
