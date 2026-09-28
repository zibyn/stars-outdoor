package dev.stars.outdoor

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
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

// 沿途天气与出行提醒 (§2.9): expected arrival along the track (Tobler × 配速档), sample points, the server's
// forecast for each at its arrival hour, and the six v1 rules. Everything but the fetch is local.

/** SharedPreferences key holding the 配速档. */
const val PREF_PACE = "pace"

/** Notification id of 出行提醒 raised while recording. */
const val WEATHER_NOTIFICATION = 3

/** At most this many points per request (the server's limit); about 4 days of hourly samples. */
const val MAX_WEATHER_POINTS = 100

/** 配速档: a factor on Tobler's speed. */
enum class Pace(val label: String, val factor: Double) { Slow("慢", 0.75), Medium("中", 1.0), Fast("快", 1.25) }

/** Tobler's hiking function: km/h on a slope (rise over run); 6 km/h at its best, 5.04 on the flat. */
fun toblerKmh(slope: Double) = 6 * exp(-3.5 * abs(slope + 0.05))

/** 配速档 from prefs (default 中). */
fun pace(prefs: SharedPreferences) = Pace.entries.firstOrNull { it.name == prefs.getString(PREF_PACE, null) } ?: Pace.Medium

/** A point where the weather is asked for: expected there at [etaMs], [distM] along the track; [highest] = the top. */
data class Sample(val point: TrackPoint, val etaMs: Long, val distM: Double, val highest: Boolean)

/**
 * The sample points (§2.9): every hour or every 5 km along, whichever comes first, plus the start, the end
 * and the highest point. Arrival follows Tobler's function over the slope of each ≥ 100 m stretch (point
 * to point, GPS altitude noise would read as cliffs), times [pace]; no elevation counts as flat.
 */
fun samples(points: List<TrackPoint>, departMs: Long, pace: Pace): List<Sample> {
  if (points.isEmpty()) return emptyList()
  val dist = DoubleArray(points.size)
  val eta = LongArray(points.size).also { it[0] = departMs }
  var a = 0
  for (i in 1..points.lastIndex) {
    dist[i] = dist[i - 1] + haversine(points[i - 1], points[i])
    if (dist[i] - dist[a] < 100 && i < points.lastIndex) continue
    val run = dist[i] - dist[a]
    val rise = points[i].ele?.let { e -> points[a].ele?.let { e - it } } ?: 0.0
    val mps = toblerKmh(if (run > 0) rise / run else 0.0) * pace.factor / 3.6
    for (j in a + 1..i) eta[j] = eta[a] + ((dist[j] - dist[a]) / mps * 1000).toLong()
    a = i
  }
  val top = points.indices.filter { points[it].ele != null }.maxByOrNull { points[it].ele!! }
  val picked = sortedSetOf(0, points.lastIndex)
  top?.let { picked += it }
  var last = 0
  for (i in 1..points.lastIndex) {
    if (eta[i] - eta[last] >= 3_600_000 || dist[i] - dist[last] >= 5000) {
      picked += i
      last = i
    }
  }
  // A very long track keeps the start, top and end, and the earliest others; past ~4 days there's no forecast anyway.
  val must = setOf(0, points.lastIndex, top)
  val kept = picked.filter { it in must } + picked.filter { it !in must }.take(MAX_WEATHER_POINTS - must.size)
  return kept.sorted().map { Sample(points[it], eta[it], dist[it], it == top) }
}

/** The forecast for sample i's arrival hour, at the cell's ground [elevation] (null when unknown). */
data class WeatherHour(val temp: Double, val feelsLike: Double, val precip: Double, val gust: Double, val thunder: Boolean, val elevation: Double?)

data class OfficialAlert(val id: String, val title: String, val text: String, val thunder: Boolean)

/** The server's answer (POST /v1/weather): hours by sample index, only for samples within the forecast. */
data class Forecast(val hours: Map<Int, WeatherHour>, val alerts: List<OfficialAlert>, val sources: List<String>)

enum class Risk(val label: String) { Thunder("雷暴"), Rain("强降水"), Wind("大风"), Cold("低温"), Dark("天黑前走不完"), Official("官方预警") }

data class TripAlert(val risk: Risk, val text: String, val detail: String = "") {
  /** What makes a risk "new" between two forecasts: the kind, or each official warning by its title. */
  val key get() = if (risk == Risk.Official) text else risk.name
}

/** Temperature lapse (§2.9): −0.65 °C per 100 m above the cell's ground; none when either elevation is unknown. */
fun WeatherHour.lapse(ele: Double?): Double = if (ele != null && elevation != null) (elevation - ele) * 0.0065 else 0.0

fun WeatherHour.tempAt(ele: Double?): Double = temp + lapse(ele)

fun WeatherHour.feelsLikeAt(ele: Double?): Double = feelsLike + lapse(ele)

/**
 * 出行提醒 v1 (§2.9), each judged by the hour of arrival: 雷暴, 强降水 ≥ 8 mm/h, 大风 gusts ≥ 17.2 m/s
 * (said again for the top), 低温 feels-like ≤ 0 °C, 天黑前走不完 (the end later than 30 min before the first
 * sunset after setting out, computed here), and every official warning as it came. Each risk is told once, where first met.
 */
fun tripAlerts(samples: List<Sample>, forecast: Forecast, zone: TimeZone): List<TripAlert> {
  val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
  fun at(i: Int) = "约 ${clock.format(samples[i].etaMs)} 在 ${String.format(Locale.ROOT, "%.1f", samples[i].distM / 1000)} km 处"
  fun first(test: (WeatherHour) -> Boolean) = samples.indices.firstOrNull { i -> forecast.hours[i]?.let(test) == true }
  val out = mutableListOf<TripAlert>()
  first { it.thunder }?.let { out += TripAlert(Risk.Thunder, "雷暴：${at(it)}有雷阵雨") }
    ?: forecast.alerts.firstOrNull { it.thunder }?.let { out += TripAlert(Risk.Thunder, "雷暴：途经区域有雷电或强对流预警") }
  first { it.precip >= 8 }?.let { out += TripAlert(Risk.Rain, "强降水：${at(it)}小时降水 ${String.format(Locale.ROOT, "%.1f", forecast.hours[it]!!.precip)} mm") }
  val windy = { h: WeatherHour -> h.gust >= 17.2 }
  fun gust(i: Int) = String.format(Locale.ROOT, "%.1f", forecast.hours[i]!!.gust)
  // ponytail: "山脊" is only the highest point; add ridge detection (a local max on the profile) if people ask.
  val top = samples.indexOfFirst { it.highest }.takeIf { it >= 0 && forecast.hours[it]?.let(windy) == true }
  first(windy)?.takeIf { it != top }?.let { out += TripAlert(Risk.Wind, "大风：${at(it)}阵风 ${gust(it)} m/s") }
  top?.let { out += TripAlert(Risk.Wind, "大风：最高点（${Math.round(samples[it].point.ele!!)} m）约 ${clock.format(samples[it].etaMs)} 阵风 ${gust(it)} m/s") }
  samples.indices.firstOrNull { i -> forecast.hours[i]?.let { it.feelsLikeAt(samples[i].point.ele) <= 0 } == true }?.let { i ->
    out += TripAlert(Risk.Cold, "低温：${at(i)}体感 ${Math.round(forecast.hours[i]!!.feelsLikeAt(samples[i].point.ele))}°C")
  }
  samples.lastOrNull()?.let { end ->
    val start = samples.first().etaMs
    val today = sunsetMs(end.point.lat, end.point.lon, start, zone)
    val sunset = (if (today != null && today > start) today else sunsetMs(end.point.lat, end.point.lon, start + 86_400_000, zone)) ?: return@let
    if (end.etaMs > sunset - 30 * 60_000) out += TripAlert(Risk.Dark, "天黑前走不完：预计 ${clock.format(end.etaMs)} 到达终点，日落 ${clock.format(sunset)}")
  }
  for (a in forecast.alerts) out += TripAlert(Risk.Official, a.title, a.text)
  return out
}

/**
 * Sunset on the local day (in [zone]) of [dayMs] at (lat, lon), by the sunrise equation (about a minute off);
 * null during polar day or night.
 */
fun sunsetMs(lat: Double, lon: Double, dayMs: Long, zone: TimeZone): Long? {
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
  return Math.round((transit + Math.toDegrees(acos(cosW)) / 360 - 2440587.5) * 86_400_000)
}

/** "预报更新于 X 小时前", shown when the forecast comes from the cache (§2.9). */
fun updatedText(fetchedMs: Long, nowMs: Long): String {
  val min = (nowMs - fetchedMs).coerceAtLeast(0) / 60_000
  return if (min < 60) "预报更新于 $min 分钟前" else "预报更新于 ${min / 60} 小时前"
}

/** Older than 12 h: greyed out, "预报可能已过时". */
fun stale(fetchedMs: Long, nowMs: Long) = nowMs - fetchedMs > 12 * 3_600_000L

/** A track's 沿途天气 as fetched at [fetchedMs] ([response] is the server's JSON); [offline]: read back from the cache. */
data class TrackWeather(val fetchedMs: Long, val departMs: Long, val samples: List<Sample>, val response: String, val offline: Boolean) {
  val forecast by lazy { parseForecast(response) }

  fun alerts(zone: TimeZone = TimeZone.getDefault()) = tripAlerts(samples, forecast, zone)
}

/** The departure to use: the one set before while it's still ahead, else now. */
fun departure(cached: TrackWeather?, nowMs: Long): Long = cached?.departMs?.takeIf { it > nowMs } ?: nowMs

fun weatherRequest(samples: List<Sample>): String = buildJsonObject {
  put("points", buildJsonArray {
    for (s in samples) add(buildJsonObject { put("lon", s.point.lon); put("lat", s.point.lat); put("time", s.etaMs / 1000) })
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

/** The cache file's content: everything needed to show the forecast and its 出行提醒 offline. */
fun writeWeather(w: TrackWeather): String = buildJsonObject {
  put("fetchedAt", w.fetchedMs)
  put("departMs", w.departMs)
  put("samples", buildJsonArray {
    for (s in w.samples) add(buildJsonArray { add(s.point.lon); add(s.point.lat); add(s.point.ele?.let(::JsonPrimitive) ?: JsonNull); add(s.etaMs); add(s.distM); add(s.highest) })
  })
  put("response", w.response)
}.toString()

fun readWeather(json: String): TrackWeather? = runCatching {
  val root = Json.parseToJsonElement(json).jsonObject
  val samples = root["samples"]!!.jsonArray.map { el ->
    val s = el.jsonArray
    Sample(TrackPoint(0, s[1].jsonPrimitive.double, s[0].jsonPrimitive.double, s[2].jsonPrimitive.doubleOrNull), s[3].jsonPrimitive.long, s[4].jsonPrimitive.double, s[5].jsonPrimitive.boolean)
  }
  TrackWeather(root["fetchedAt"]!!.jsonPrimitive.long, root["departMs"]!!.jsonPrimitive.long, samples, root["response"]!!.jsonPrimitive.content, offline = false)
}.getOrNull()

/**
 * Fetches [trackId]'s 沿途天气 from [departMs] (default: [departure]) and caches it in filesDir/weather/;
 * [from] (lat, lon): on the way, from the nearest track point on. Without network (or when the server
 * can't answer) it's the cached one, marked offline. Null: no track points, or nothing fetched or cached
 * yet. Blocking: call off the main thread.
 */
fun fetchTrackWeather(
  context: Context, api: OfflineApi, trackId: Long, departMs: Long?, pace: Pace,
  from: Pair<Double, Double>? = null, now: Long = System.currentTimeMillis(),
): TrackWeather? {
  val file = weatherFile(context, trackId)
  val cached = runCatching { readWeather(file.readText()) }.getOrNull()
  val all = TrackDb(context).use { it.segments(trackId) }.flatten()
  val points = from?.let { (lat, lon) ->
    val here = TrackPoint(0, lat, lon, null)
    all.drop(all.indices.minByOrNull { haversine(here, all[it]) } ?: 0)
  } ?: all
  val samples = samples(points, departMs ?: departure(cached, now), pace)
  if (samples.isEmpty()) return null
  return try {
    val w = TrackWeather(now, samples.first().etaMs, samples, api.weather(weatherRequest(samples)), offline = false)
    w.forecast // parses, so a bad answer isn't cached
    file.parentFile!!.mkdirs()
    File(file.path + ".tmp").apply { writeText(writeWeather(w)) }.renameTo(file)
    w
  } catch (e: Exception) {
    // Offline, or the server couldn't answer (or answered nonsense): the last good forecast.
    cached?.copy(offline = true)
  }
}

/** The cached 沿途天气 of [trackId], if any (no network needed). */
fun cachedTrackWeather(context: Context, trackId: Long): TrackWeather? =
  runCatching { readWeather(weatherFile(context, trackId).readText()) }.getOrNull()?.copy(offline = true)

private fun weatherFile(context: Context, trackId: Long) = File(context.filesDir, "weather/$trackId.json")
