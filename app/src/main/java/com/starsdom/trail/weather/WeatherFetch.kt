package com.starsdom.trail.weather

import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.net.idempotent
import io.ktor.client.statement.bodyAsText
import java.io.File

/** Notification id of 出行提醒 raised while recording. */
const val WEATHER_NOTIFICATION = 3

/**
 * Fetches the week's forecast at (lat, lon) for [ele] (GPS or the track point's; null, the server's DEM), with
 * [detail] for the place the 天气 page is open on (not 沿途 overviews or 出行提醒). With a
 * [cache] file, an answer with its forecast is kept there; one without shows the cached forecast with its own warnings
 * ([PlaceWeather.orCached]), and without network (or when the server can't answer) the cached one comes back marked
 * offline, wherever it was for. Nothing fetched or cached: throws what went wrong. The answer is kept as sent.
 */
suspend fun fetchWeather(api: BaseApi, lat: Double, lon: Double, ele: Double?, detail: Boolean = false, cache: File? = null, now: Long = System.currentTimeMillis()): PlaceWeather {
  val w = try {
    PlaceWeather(lat, lon, ele, now, api.prepareGetWeather(lat = lat, lon = lon, ele = ele, detail = true.takeIf { detail }) { idempotent() }.execute { it.bodyAsText() })
      .also { it.forecast } // parses, so a bad answer isn't cached
  } catch (e: Exception) {
    // Offline, or the server couldn't answer (or answered nonsense): the last good forecast.
    return cache?.let(::cachedWeather) ?: throw e
  }
  if (!w.forecast.ok) return w.orCached(cache?.let(::cachedWeather))
  cache?.let { f ->
    f.parentFile!!.mkdirs()
    File(f.path + ".tmp").apply { writeText(writeWeather(w)) }.renameTo(f)
  }
  return w
}

/** What [fetchWeather] last kept in [file], marked offline. */
fun cachedWeather(file: File): PlaceWeather? = runCatching { readWeather(file.readText()) }.getOrNull()?.copy(offline = true)
