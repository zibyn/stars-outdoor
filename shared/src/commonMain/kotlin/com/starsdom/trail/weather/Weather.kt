package com.starsdom.trail.weather

import com.starsdom.trail.net.model.WeatherDto
import com.starsdom.trail.net.orNull
import com.starsdom.trail.net.wire
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.compass
import com.starsdom.trail.track.haversine
import com.starsdom.trail.track.radians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// 天气 (§2.9): a place's week hour by hour (my location, a long-pressed point, or spots along a track), the
// official warnings, and the 出行提醒 rules over the coming hours. Data only: no arrival times, no 配速, the walker judges. Only the fetch
// goes out.

/** The hour's weather for its icon (晴, 多云, 阴, 雾, 雨, 雪); [WeatherHour.thunder] goes on top. */
enum class Sky { Clear, Partly, Cloudy, Fog, Rain, Snow }

/** 云海 likelihood or 雷暴潜势: 低 / 中 / 高. */
enum class Odds(val label: String) { Low("低"), Medium("中"), High("高") }

/** A pressure level of the 垂直剖面: [height] metres above sea level, [temp] °C, [rh] %. */
data class WeatherLevel(val height: Double, val temp: Double, val rh: Double)

/**
 * One hour's forecast at the place's height (the server's [Forecast.elevation]). [windDir]: degrees the wind blows from,
 * null if unknown. The rest only when the 天气 page asked (detail): cloud cover by layer (%), [cloudSea] with the
 * [cloudTop] under it and [freezingLevel] (metres above sea level), [thunderPotential], and the 垂直剖面 [profile].
 */
data class WeatherHour(
  val temp: Double, val feelsLike: Double, val precip: Double, val gust: Double, val thunder: Boolean,
  val sky: Sky? = null, val windDir: Double? = null,
  val cloudLow: Double? = null, val cloudMid: Double? = null, val cloudHigh: Double? = null,
  val cloudSea: Odds? = null, val cloudTop: Double? = null, val thunderPotential: Odds? = null, val freezingLevel: Double? = null,
  val profile: List<WeatherLevel>? = null,
)

/**
 * One local day of a forecast (today from the current hour): its hours, highs and lows at the place's height. [stormy]:
 * 强降水 or 大风, as the day strip and 沿途天气's pins mark it; not 雷阵雨 (出行提醒 only, #245) or 低温, freezing most days high up.
 */
data class WeatherDay(val startMs: Long, val hours: List<Pair<Long, WeatherHour>>, val high: Int, val low: Int, val precip: Double, val sky: Sky?, val thunder: Boolean, val stormy: Boolean)

/**
 * [w]'s days from [nowMs] in [zone], at most [max]. A day's icon: 雷阵雨 if any hour has it, else 雪 or 雨 when it
 * snows or rains at least 1 mm, else the sky most hours of its daytime (08–19) have.
 */
fun weatherDays(w: PlaceWeather, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault(), max: Int = 7): List<WeatherDay> {
  return w.hours().filter { (t, _) -> t + 3_600_000 > nowMs }.groupBy { local(it.first, zone).date }.values.take(max).map { hs ->
    val temps = hs.map { it.second.temp }
    val precip = hs.sumOf { it.second.precip }
    val wet = hs.filter { it.second.precip >= 0.1 }.mapNotNull { it.second.sky }
    val daytime = hs.filter { local(it.first, zone).hour in 8..19 }.ifEmpty { hs }
    val sky = when {
      precip >= 1 && Sky.Snow in wet -> Sky.Snow
      precip >= 1 -> Sky.Rain
      else -> daytime.mapNotNull { it.second.sky }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
    WeatherDay(
      hs.first().first, hs, temps.max().roundToInt(), temps.min().roundToInt(), precip, sky,
      hs.any { it.second.thunder }, hs.any { it.second.let { h -> isHeavyRain(h) || isGale(h) } },
    )
  }
}

/** The 气象图's rows under the base ones (#245 方案 A), in order: 分层云量, 云海, 雷暴潜势, 0°C 层. */
enum class ProRow { Clouds, CloudSea, Thunder, Freezing }

/** The rows this day has: each for a field some hour has; 云海 only if some hour has a grade. */
fun WeatherDay.proRows(): List<ProRow> = ProRow.entries.filter { r ->
  hours.any { (_, h) ->
    when (r) {
      ProRow.Clouds -> listOfNotNull(h.cloudLow, h.cloudMid, h.cloudHigh).isNotEmpty()
      ProRow.CloudSea -> h.cloudSea != null
      ProRow.Thunder -> h.thunderPotential != null
      ProRow.Freezing -> h.freezingLevel != null
    }
  }
}

/** The 0°C 层 in km, 「3.7k」. */
fun freezingText(m: Double) = oneDecimal(m / 1000) + "k"

/** The 0°C 层 is below the place's height ([Forecast.elevation]): freezing there, in red. */
fun PlaceWeather.freezesBelow(h: WeatherHour): Boolean {
  val ele = forecast.elevation ?: return false
  return h.freezingLevel?.let { it < ele } == true
}

/**
 * The 垂直剖面's line on top: 云海 grade, how far the 云顶 is below the place's height ([Forecast.elevation]),
 * 雷暴潜势 and 分层云量, each only if [h] has it.
 */
fun PlaceWeather.profileSummary(h: WeatherHour): String = listOfNotNull(
  h.cloudSea?.let { "云海 ${it.label}" },
  // The server only gives a 云顶 under the place.
  h.cloudTop?.let { top -> "云顶约 ${top.roundToInt()} m" + forecast.elevation?.let { "，比你低 ${(it - top).roundToInt()} m" }.orEmpty() },
  h.thunderPotential?.let { "雷暴潜势 ${it.label}" },
  listOfNotNull(h.cloudLow?.let { "低" to it }, h.cloudMid?.let { "中" to it }, h.cloudHigh?.let { "高" to it })
    .takeIf { it.isNotEmpty() }?.joinToString(" ", "云量 ") { (k, v) -> "$k ${v.roundToInt()}%" },
).joinToString(" · ")

/** An official warning (官方预警); [sender] (发布台站) and [issuedMs] when the server knows them. */
data class OfficialAlert(val id: String, val title: String, val text: String, val thunder: Boolean, val sender: String? = null, val issuedMs: Long? = null)

/** Under a warning: 「萍乡市气象台 · 10月6日 07:30 发布」, either part left out when unknown; null with neither. */
fun OfficialAlert.issuedText(zone: TimeZone = TimeZone.currentSystemDefault()): String? = listOfNotNull(
  sender,
  issuedMs?.let { local(it, zone).let { t -> "${t.month.ordinal + 1}月${t.day}日 ${t.hour.twoDigits()}:${t.minute.twoDigits()} 发布" } },
).joinToString(" · ").ifEmpty { null }

/**
 * The server's answer (GET /v1/weather): [hours] by their start, in order, for [elevation] metres, empty unless [ok]
 * ([status] says why); the warnings, unless [alertsFailed]. [groundElevation]: the forecast cell's ground, with detail.
 */
data class Forecast(
  val status: WeatherDto.ForecastDto, val hours: List<Pair<Long, WeatherHour>>, val alerts: List<OfficialAlert>, val alertsFailed: Boolean, val elevation: Double?,
  val groundElevation: Double? = null,
) {
  val ok get() = status == WeatherDto.ForecastDto.OK
}

enum class Risk(val label: String) { Thunder("雷暴"), Rain("强降水"), Wind("大风"), Cold("低温"), Official("官方预警") }

data class TripAlert(val risk: Risk, val text: String, val detail: String = "") {
  /** What makes a risk "new" between two forecasts: the kind, or each official warning by its title. */
  val key get() = if (risk == Risk.Official) text else risk.name
}

/**
 * 出行提醒 (§2.9) over [w]'s hours from [fromMs] until [untilMs]: 雷暴, 强降水 ≥ 8 mm/h, 大风 gusts ≥ 17.2 m/s,
 * 低温 feels-like ≤ 0 °C, each told once at its first hour, then every official warning as it came.
 */
fun alerts(w: PlaceWeather, fromMs: Long, untilMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<TripAlert> {
  fun clock(ms: Long) = local(ms, zone).let { "${it.hour.twoDigits()}:${it.minute.twoDigits()}" }
  val hours = w.hours().filter { (t, _) -> t + 3_600_000 > fromMs && t < untilMs }
  fun first(test: (WeatherHour) -> Boolean) = hours.firstOrNull { test(it.second) }
  val out = mutableListOf<TripAlert>()
  first { it.thunder }?.let { (t, _) -> out += TripAlert(Risk.Thunder, "雷暴：约 ${clock(t)} 起有雷阵雨") }
    ?: w.alerts.firstOrNull { it.thunder }?.let { out += TripAlert(Risk.Thunder, "雷暴：所在区域有雷电或强对流预警") }
  first { isHeavyRain(it) }?.let { (t, h) -> out += TripAlert(Risk.Rain, "强降水：约 ${clock(t)} 小时降水 ${oneDecimal(h.precip)} mm") }
  first { isGale(it) }?.let { (t, h) -> out += TripAlert(Risk.Wind, "大风：约 ${clock(t)} 阵风 ${oneDecimal(h.gust)} m/s") }
  first { isFreezing(it) }?.let { (t, h) -> out += TripAlert(Risk.Cold, "低温：约 ${clock(t)} 体感 ${h.feelsLike.roundToLong()}°C") }
  for (a in w.alerts) out += TripAlert(Risk.Official, a.title, a.text)
  return out
}

/** C3-38: the 提示条 for a risk while the app is up. */
fun riskHint(a: TripAlert): String = if (a.risk == Risk.Official) "⚠ 有官方天气预警" else "⚠ 3 小时内有${a.risk.label}"

fun isHeavyRain(h: WeatherHour) = h.precip >= 8

fun isGale(h: WeatherHour) = h.gust >= 17.2

fun isFreezing(h: WeatherHour) = h.feelsLike <= 0

/**
 * Sunrise and sunset on the local day (in [zone]) of [dayMs] at (lat, lon), by the sunrise equation (about a minute
 * off); null during polar day or night.
 */
fun sunriseMs(lat: Double, lon: Double, dayMs: Long, zone: TimeZone): Long? = sun(lat, lon, dayMs, zone, rise = true)

fun sunsetMs(lat: Double, lon: Double, dayMs: Long, zone: TimeZone): Long? = sun(lat, lon, dayMs, zone, rise = false)

private fun sun(lat: Double, lon: Double, dayMs: Long, zone: TimeZone, rise: Boolean): Long? {
  val noon = local(dayMs, zone).date.atTime(12, 0).toInstant(zone).toEpochMilliseconds()
  val n = (noon / 86_400_000.0 + 2440587.5 - 2451545.0 + 0.0008).roundToLong().toDouble()
  val j = n - lon / 360
  val m = radians((357.5291 + 0.98560028 * j) % 360)
  val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
  val lambda = radians((degrees(m) + c + 180 + 102.9372) % 360)
  val transit = 2451545 + j + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
  val decl = asin(sin(lambda) * sin(radians(23.4397)))
  val cosW = (sin(radians(-0.833)) - sin(radians(lat)) * sin(decl)) / (cos(radians(lat)) * cos(decl))
  if (cosW !in -1.0..1.0) return null
  return ((transit + (if (rise) -1 else 1) * degrees(acos(cosW)) / 360 - 2440587.5) * 86_400_000).roundToLong()
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
    between += TrackSpot(p, d, "${(d / 1000).roundToLong()} km")
    next = d + step
  }
  // The top stands in for an even spot near it.
  if (top != null) between.removeAll { abs(it.distM - top.distM) < step / 2 }
  val loop = first.point.cell == last.point.cell
  val spots = listOfNotNull(if (loop) first.copy(label = "起终点") else first, last.takeIf { !loop }, top) + between
  return spots.distinctBy { it.point.cell }.sortedBy { it.distM }
}

/** The server's forecast cell, 0.01°. */
private val TrackPoint.cell get() = (lat * 100).roundToLong() to (lon * 100).roundToLong()

/** C2-99: how old a cached forecast is, 「3 小时前」. */
fun updatedText(fetchedMs: Long, nowMs: Long): String {
  val min = (nowMs - fetchedMs).coerceAtLeast(0) / 60_000
  return if (min < 60) "$min 分钟前" else "${min / 60} 小时前"
}

private val BEAUFORT = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)

/** [ms] metres a second as a 风力等级 (0–12). */
fun beaufort(ms: Double): Int = BEAUFORT.count { ms >= it }

/** C2-104: 「东北风 3 级」, the wind named for where it comes [from] (degrees). */
fun windText(from: Double?, ms: Double): String = listOfNotNull(from?.let { compass(it) + "风" }, "${beaufort(ms)} 级").joinToString(" ")

/** Older than 12 h: faded, 「已过期」 (C2-100). */
fun stale(fetchedMs: Long, nowMs: Long) = nowMs - fetchedMs > 12 * 3_600_000L

/**
 * A place's forecast as fetched at [fetchedMs] for [ele] (the app's own 地点海拔, null to leave it to the server):
 * [response] is the server's JSON; [offline]: read back from the cache. [latest]: the answer just fetched, its
 * warnings standing in for the cached ones when its forecast didn't come ([orCached]).
 */
data class PlaceWeather(val lat: Double, val lon: Double, val ele: Double?, val fetchedMs: Long, val response: String, val offline: Boolean = false, val latest: String? = null) {
  val forecast by lazy { parseForecast(response) }
  private val warned by lazy { latest?.let(::parseForecast) ?: forecast }
  val alerts get() = warned.alerts
  val alertsFailed get() = warned.alertsFailed

  /** Both the forecast and the warnings came: nothing to ask again on 重试. */
  val complete get() = forecast.ok && !alertsFailed

  /** Each forecast hour's start with its forecast, in order. */
  fun hours(): List<Pair<Long, WeatherHour>> = forecast.hours

  /** The hour [nowMs] falls in, if forecast. */
  fun at(nowMs: Long): WeatherHour? = forecast.hours.lastOrNull { (t, _) -> t <= nowMs }?.takeIf { (t, _) -> nowMs < t + 3_600_000 }?.second

  /** What to show for this answer: itself if its forecast came or there's no [cached] one, else that with this answer's warnings. */
  fun orCached(cached: PlaceWeather?): PlaceWeather = if (forecast.ok || cached == null) this else cached.copy(offline = true, latest = response)
}

/** The server's answer (WeatherDto), as fetched or as cached. */
fun parseForecast(json: String): Forecast {
  val w = wire.decodeFromString<WeatherDto>(json)
  return Forecast(
    w.forecast,
    w.hours.map { h ->
      h.time * 1000 to WeatherHour(
        h.temp, h.feelsLike, h.precip, h.gust, h.thunder,
        Sky.entries.firstOrNull { it.name.equals(h.sky.value, ignoreCase = true) }, h.windDir.orNull(),
        h.cloudLow.orNull(), h.cloudMid.orNull(), h.cloudHigh.orNull(), h.cloudSea.orNull()?.let { odds(it.value) },
        h.cloudTop.orNull(), h.thunderPotential.orNull()?.let { odds(it.value) }, h.freezingLevel.orNull(),
        h.profile.orNull()?.map { WeatherLevel(it.height, it.temp, it.rh) },
      )
    }.sortedBy { it.first },
    w.warnings.map { OfficialAlert(it.id, it.title, it.text, it.thunder, it.sender.orNull(), it.issuedAt.orNull()?.let { t -> runCatching { Instant.parse(t).toEpochMilliseconds() }.getOrNull() }) },
    w.warningsFailed.orNull() == true,
    w.elevation.orNull(),
    w.groundElevation.orNull(),
  )
}

private fun odds(v: String) = Odds.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }

/** The cache file's content. */
fun writeWeather(w: PlaceWeather): String = buildJsonObject {
  put("lat", w.lat)
  put("lon", w.lon)
  put("ele", w.ele?.let(::JsonPrimitive) ?: JsonNull)
  put("fetchedAt", w.fetchedMs)
  put("response", w.response)
}.toString()

/** What [writeWeather] wrote; null if its forecast no longer reads (cached from an older server). */
fun readWeather(json: String): PlaceWeather? = runCatching {
  val root = Json.parseToJsonElement(json).jsonObject
  fun d(k: String) = root[k]!!.jsonPrimitive.double
  PlaceWeather(d("lat"), d("lon"), root["ele"]?.jsonPrimitive?.doubleOrNull, root["fetchedAt"]!!.jsonPrimitive.long, root["response"]!!.jsonPrimitive.content)
    .also { it.forecast }
}.getOrNull()

private fun local(ms: Long, zone: TimeZone) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

private fun Int.twoDigits() = toString().padStart(2, '0')

/** As "%.1f" writes a value that isn't negative. */
private fun oneDecimal(x: Double) = (x * 10).roundToLong().let { "${it / 10}.${it % 10}" }

private fun degrees(rad: Double) = rad * (180 / PI)
