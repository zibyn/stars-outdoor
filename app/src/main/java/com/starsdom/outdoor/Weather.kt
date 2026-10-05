package com.starsdom.outdoor

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// 天气 (§2.9): a place's forecast hour by hour (my location, a long-pressed point, or spots along a track), the
// official warnings, and the 出行提醒 rules over the coming hours. Data only: no arrival times, no 配速, the walker judges. Only the fetch
// goes out.

/** Notification id of 出行提醒 raised while recording. */
const val WEATHER_NOTIFICATION = 3

/** Hours of forecast asked for a place: two days, well within the server's 100 points. */
const val WEATHER_HOURS = 48

/** One hour's forecast, at the cell's ground [elevation] (null when unknown). */
data class WeatherHour(val temp: Double, val feelsLike: Double, val precip: Double, val gust: Double, val thunder: Boolean, val elevation: Double?)

data class OfficialAlert(val id: String, val title: String, val text: String, val thunder: Boolean)

/** The server's answer (POST /v1/weather): hours by point index, only for points within the forecast. */
data class Forecast(val hours: Map<Int, WeatherHour>, val alerts: List<OfficialAlert>, val sources: List<String>)

enum class Risk(val label: String) { Thunder("雷暴"), Rain("强降水"), Wind("大风"), Cold("低温"), Official("官方预警") }

data class TripAlert(val risk: Risk, val text: String, val detail: String = "") {
  /** What makes a risk "new" between two forecasts: the kind, or each official warning by its title. */
  val key get() = if (risk == Risk.Official) text else risk.name
}

/** Temperature lapse (§2.9): −0.65 °C per 100 m above the cell's ground; none when either elevation is unknown. */
fun WeatherHour.lapse(ele: Double?): Double = if (ele != null && elevation != null) (elevation - ele) * 0.0065 else 0.0

fun WeatherHour.tempAt(ele: Double?): Double = temp + lapse(ele)

fun WeatherHour.feelsLikeAt(ele: Double?): Double = feelsLike + lapse(ele)

/**
 * 出行提醒 (§2.9) over [w]'s hours from [fromMs] until [untilMs]: 雷暴, 强降水 ≥ 8 mm/h, 大风 gusts ≥ 17.2 m/s,
 * 低温 feels-like ≤ 0 °C, each told once at its first hour, then every official warning as it came.
 */
fun alerts(w: PlaceWeather, fromMs: Long, untilMs: Long, zone: TimeZone = TimeZone.getDefault()): List<TripAlert> {
  val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
  val hours = w.hours().filter { (t, _) -> t + 3_600_000 > fromMs && t < untilMs }
  fun first(test: (WeatherHour) -> Boolean) = hours.firstOrNull { test(it.second) }
  val out = mutableListOf<TripAlert>()
  first { it.thunder }?.let { (t, _) -> out += TripAlert(Risk.Thunder, "雷暴：约 ${clock.format(t)} 起有雷阵雨") }
    ?: w.forecast.alerts.firstOrNull { it.thunder }?.let { out += TripAlert(Risk.Thunder, "雷暴：所在区域有雷电或强对流预警") }
  first { isHeavyRain(it) }?.let { (t, h) -> out += TripAlert(Risk.Rain, "强降水：约 ${clock.format(t)} 小时降水 ${String.format(Locale.ROOT, "%.1f", h.precip)} mm") }
  first { isGale(it) }?.let { (t, h) -> out += TripAlert(Risk.Wind, "大风：约 ${clock.format(t)} 阵风 ${String.format(Locale.ROOT, "%.1f", h.gust)} m/s") }
  first { isFreezing(it, w.ele) }?.let { (t, h) -> out += TripAlert(Risk.Cold, "低温：约 ${clock.format(t)} 体感 ${Math.round(h.feelsLikeAt(w.ele))}°C") }
  for (a in w.forecast.alerts) out += TripAlert(Risk.Official, a.title, a.text)
  return out
}

fun isHeavyRain(h: WeatherHour) = h.precip >= 8

fun isGale(h: WeatherHour) = h.gust >= 17.2

fun isFreezing(h: WeatherHour, ele: Double?) = h.feelsLikeAt(ele) <= 0

/**
 * Sunrise and sunset on the local day (in [zone]) of [dayMs] at (lat, lon), by the sunrise equation (about a minute
 * off); null during polar day or night.
 */
fun sunriseMs(lat: Double, lon: Double, dayMs: Long, zone: TimeZone): Long? = sun(lat, lon, dayMs, zone, rise = true)

fun sunsetMs(lat: Double, lon: Double, dayMs: Long, zone: TimeZone): Long? = sun(lat, lon, dayMs, zone, rise = false)

private fun sun(lat: Double, lon: Double, dayMs: Long, zone: TimeZone, rise: Boolean): Long? {
  val noon = Calendar.getInstance(zone).apply {
    timeInMillis = dayMs
    set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis
  val n = Math.round(noon / 86_400_000.0 + 2440587.5 - 2451545.0 + 0.0008).toDouble()
  val j = n - lon / 360
  val m = Math.toRadians((357.5291 + 0.98560028 * j) % 360)
  val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
  val lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372) % 360)
  val transit = 2451545 + j + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
  val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
  val cosW = (sin(Math.toRadians(-0.833)) - sin(Math.toRadians(lat)) * sin(decl)) / (cos(Math.toRadians(lat)) * cos(decl))
  if (cosW !in -1.0..1.0) return null
  return Math.round((transit + (if (rise) -1 else 1) * Math.toDegrees(acos(cosW)) / 360 - 2440587.5) * 86_400_000)
}

/** A place on a track whose weather is shown (ADR 0010): [distM] along it as walked; [label] 起点 / 终点 / 最高点 / "x km". */
data class TrackSpot(val point: TrackPoint, val distM: Double, val label: String)

/**
 * 沿途天气's places (ADR 0010): the start, about every [everyM] (wider so there are at most [max]), the highest point
 * and the end, one per 1 km forecast cell. Distances as [trackStats] counts them. No times: when to be where is the
 * walker's call.
 */
fun trackSpots(walked: List<List<TrackPoint>>, everyM: Double = 5000.0, max: Int = 8): List<TrackSpot> {
  val pts = mutableListOf<Pair<TrackPoint, Double>>()
  var total = 0.0
  for (seg in walked) seg.forEachIndexed { i, p -> if (i > 0) total += haversine(seg[i - 1], p); pts += p to total }
  if (pts.isEmpty()) return emptyList()
  val step = maxOf(everyM, total / (max - 2))
  val first = TrackSpot(pts.first().first, 0.0, "起点")
  val last = TrackSpot(pts.last().first, total, "终点")
  val top = pts.filter { it.first.ele != null }.maxByOrNull { it.first.ele!! }?.let { (p, d) -> TrackSpot(p, d, "最高点") }
  val between = mutableListOf<TrackSpot>()
  var next = step
  for ((p, d) in pts) if (d >= next && total - d >= step / 2) {
    between += TrackSpot(p, d, "${Math.round(d / 1000)} km")
    next = d + step
  }
  // The top stands in for an even spot near it.
  if (top != null) between.removeAll { kotlin.math.abs(it.distM - top.distM) < step / 2 }
  val loop = first.point.cell == last.point.cell
  val spots = listOfNotNull(if (loop) first.copy(label = "起终点") else first, last.takeIf { !loop }, top) + between
  return spots.distinctBy { it.point.cell }.sortedBy { it.distM }
}

/** Under 沿途天气's choices: the spot's height and how far along. */
fun spotText(s: TrackSpot): String =
  listOfNotNull(s.point.ele?.let { "海拔 ${Math.round(it)} m" }, String.format(Locale.ROOT, "沿轨 %.1f km", s.distM / 1000)).joinToString("，")

/** The server's forecast cell, 0.01°. */
private val TrackPoint.cell get() = Math.round(lat * 100) to Math.round(lon * 100)

/** "预报更新于 X 小时前", shown when the forecast comes from the cache (§2.9). */
fun updatedText(fetchedMs: Long, nowMs: Long): String {
  val min = (nowMs - fetchedMs).coerceAtLeast(0) / 60_000
  return if (min < 60) "预报更新于 $min 分钟前" else "预报更新于 ${min / 60} 小时前"
}

/** Older than 12 h: greyed out, "预报可能已过时". */
fun stale(fetchedMs: Long, nowMs: Long) = nowMs - fetchedMs > 12 * 3_600_000L

/**
 * A place's forecast as fetched at [fetchedMs]: [response] (the server's JSON) holds hour i from [startMs] (a whole
 * hour) at index i. [ele], when known, corrects the temperature for the cell's ground; [offline]: read back from the cache.
 */
data class PlaceWeather(val lat: Double, val lon: Double, val ele: Double?, val fetchedMs: Long, val startMs: Long, val response: String, val offline: Boolean = false) {
  val forecast by lazy { parseForecast(response) }

  /** Each forecast hour's start with its forecast, in order. */
  fun hours(): List<Pair<Long, WeatherHour>> = forecast.hours.toSortedMap().map { (i, h) -> startMs + i * 3_600_000L to h }

  /** The hour [nowMs] falls in, if forecast. */
  fun at(nowMs: Long): WeatherHour? = forecast.hours[((nowMs - startMs).floorDiv(3_600_000L)).toInt()]
}

/** [hours] hourly points at (lat, lon) from [startMs], as POST /v1/weather takes them. */
fun weatherRequest(lat: Double, lon: Double, startMs: Long, hours: Int): String = buildJsonObject {
  put("points", buildJsonArray {
    for (i in 0 until hours) add(buildJsonObject { put("lon", lon); put("lat", lat); put("time", startMs / 1000 + i * 3600L) })
  })
}.toString()

fun parseForecast(json: String): Forecast {
  val root = Json.parseToJsonElement(json).jsonObject
  val hours = root["hours"]!!.jsonArray.map { it.jsonObject }.associate { h ->
    fun d(k: String) = h[k]!!.jsonPrimitive.double
    h["point"]!!.jsonPrimitive.int to WeatherHour(d("temp"), d("feelsLike"), d("precip"), d("gust"), h["thunder"]!!.jsonPrimitive.boolean, h["elevation"]?.jsonPrimitive?.doubleOrNull)
  }
  val alerts = root["warnings"]!!.jsonArray.map { it.jsonObject }.map { a ->
    OfficialAlert(a["id"]!!.jsonPrimitive.content, a["title"]!!.jsonPrimitive.content, a["text"]!!.jsonPrimitive.content, a["thunder"]!!.jsonPrimitive.boolean)
  }
  return Forecast(hours, alerts, root["sources"]!!.jsonArray.map { it.jsonPrimitive.content })
}

/** The cache file's content. */
fun writeWeather(w: PlaceWeather): String = buildJsonObject {
  put("lat", w.lat)
  put("lon", w.lon)
  put("ele", w.ele?.let(::JsonPrimitive) ?: JsonNull)
  put("fetchedAt", w.fetchedMs)
  put("start", w.startMs)
  put("response", w.response)
}.toString()

fun readWeather(json: String): PlaceWeather? = runCatching {
  val root = Json.parseToJsonElement(json).jsonObject
  fun d(k: String) = root[k]!!.jsonPrimitive.double
  PlaceWeather(d("lat"), d("lon"), root["ele"]?.jsonPrimitive?.doubleOrNull, root["fetchedAt"]!!.jsonPrimitive.long, root["start"]!!.jsonPrimitive.long, root["response"]!!.jsonPrimitive.content)
}.getOrNull()

/**
 * Fetches the next [hours] of forecast at (lat, lon), from the current whole hour. With a [cache] file, a good answer
 * is kept there, and without network (or when the server can't answer) the cached one comes back marked offline,
 * wherever it was for. Null: nothing fetched or cached. Blocking: call off the main thread.
 */
fun fetchWeather(
  api: Api, lat: Double, lon: Double, ele: Double?, cache: File? = null,
  hours: Int = WEATHER_HOURS, now: Long = System.currentTimeMillis(),
): PlaceWeather? {
  val start = now - now.mod(3_600_000L)
  return try {
    val w = PlaceWeather(lat, lon, ele, now, start, api.weather(weatherRequest(lat, lon, start, hours)))
    w.forecast // parses, so a bad answer isn't cached
    cache?.let { f ->
      f.parentFile!!.mkdirs()
      File(f.path + ".tmp").apply { writeText(writeWeather(w)) }.renameTo(f)
    }
    w
  } catch (e: Exception) {
    // Offline, or the server couldn't answer (or answered nonsense): the last good forecast.
    cache?.let(::cachedWeather)
  }
}

/** What [fetchWeather] last kept in [file], marked offline. */
fun cachedWeather(file: File): PlaceWeather? = runCatching { readWeather(file.readText()) }.getOrNull()?.copy(offline = true)
