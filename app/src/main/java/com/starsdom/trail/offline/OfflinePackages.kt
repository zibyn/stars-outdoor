package com.starsdom.trail.offline

import android.content.SharedPreferences
import com.starsdom.trail.BuildConfig
import com.starsdom.trail.OfflineError
import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.net.outdated
import com.starsdom.trail.net.trailClient
import com.starsdom.trail.net.wire
import com.starsdom.trail.networkCode
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.alongTrack
import com.starsdom.trail.track.dayText
import com.starsdom.trail.weather.stale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// Offline packages (§2.3): the server clips basemap/DEM/contours and the 地名索引 (§2.10) to a viewport or
// track corridor; each package is a directory under packages/ holding those four files, the 周边路网 in it
// (§2.8: routes.geojson and a snapshot of the 公开轨迹, public-tracks.geojson), and meta.json, which also keeps the
// outline the server clipped to (#183).

const val MAX_REQUEST_POINTS = 2000

/**
 * A downloaded package; [request] is the JSON body it was made from, re-sent to update it; [outline] the GeoJSON
 * geometry the server clipped to, null on packages from before it said (#183).
 */
data class OfflinePackage(val dir: File, val name: String, val version: String, val request: String, val bytes: Long, val outline: String? = null) {
  val alongTrack get() = alongTrack(request)
  /** When it came: its directory is named by the time ([MainActivity] downloadPackage). */
  val savedMs get() = dir.name.toLongOrNull() ?: dir.lastModified()
}

/** 沿线 or 附近, by the [request] a package was made from (§8.6 第 14 条: told apart by the icon). */
fun alongTrack(request: String) = "track" in Json.parseToJsonElement(request).jsonObject

/** What 离线地图 draws for [pkg] (§8.6 第 15 条): the server's outline, else the box asked for or around the track. */
fun packageOutline(pkg: OfflinePackage): String {
  pkg.outline?.let { return it }
  val req = Json.parseToJsonElement(pkg.request).jsonObject
  val coords = req["bbox"]?.jsonArray?.map { it.jsonPrimitive.double }?.let { (w, s, e, n) -> listOf(listOf(w, s), listOf(e, n)) }
    ?: req["track"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
  val w = coords.minOf { it[0] }; val e = coords.maxOf { it[0] }
  val s = coords.minOf { it[1] }; val n = coords.maxOf { it[1] }
  return "{\"type\":\"Polygon\",\"coordinates\":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}"
}

/** The box around a GeoJSON geometry, as west, south, east, north. */
fun outlineBox(geoJson: String): List<Double> {
  val points = mutableListOf<List<Double>>()
  fun walk(el: JsonElement) {
    val arr = el as? JsonArray ?: return
    if (arr.firstOrNull() is JsonPrimitive) points += arr.map { it.jsonPrimitive.double } else arr.forEach(::walk)
  }
  walk(Json.parseToJsonElement(geoJson).jsonObject["coordinates"]!!)
  return listOf(points.minOf { it[0] }, points.minOf { it[1] }, points.maxOf { it[0] }, points.maxOf { it[1] })
}

/** A package row's second line (C6-60): 「6.8 MB · 10月3日」, 「6.8 MB · 可更新」, or 「42%」 while it downloads. */
fun packageLine(size: String, savedMs: Long, nowMs: Long, stale: Boolean, percent: Int?): String =
  percent?.let { "$it%" } ?: "$size · ${if (stale) "可更新" else dayText(savedMs, nowMs)}"

/** 轨迹详情's 下载沿线 button (C2-55…60); never a size on it (§8.2 第 6 条). */
sealed interface Corridor {
  data object Download : Corridor
  data class Percent(val n: Int) : Corridor
  data object Done : Corridor
  data object Update : Corridor
  /** Can't be tapped: the server would refuse it. */
  data object TooLarge : Corridor
}

/**
 * The track's package [pkg], the server's [dataVersion] once asked, [percent] while this one downloads, and whether
 * it's [corridorTooLarge]. Another package downloading changes nothing: a tap says to wait (C2-59).
 */
fun corridor(pkg: OfflinePackage?, dataVersion: String?, percent: Int?, tooLarge: Boolean): Corridor = when {
  percent != null -> Corridor.Percent(percent)
  pkg == null -> if (tooLarge) Corridor.TooLarge else Corridor.Download
  dataVersion != null && pkg.version != dataVersion -> Corridor.Update
  else -> Corridor.Done
}

/** The server's limit on a package's area (server/offline.go maxAreaKm2), and the corridor's half width. */
private const val MAX_AREA_KM2 = 100.0 * 100.0
private const val CORRIDOR_KM = 2.0

/**
 * Whether the track's corridor is over one package (§8.2 第 12 条), told here by the box around it rather than the
 * server's error: a long diagonal track may be called too large where the server would take it.
 */
fun corridorTooLarge(segments: List<List<TrackPoint>>): Boolean {
  val points = segments.flatten().ifEmpty { return false }
  val (south, north) = points.minOf { it.lat } to points.maxOf { it.lat }
  val heightKm = (north - south) * 111.2 + 2 * CORRIDOR_KM
  val widthKm = (points.maxOf { it.lon } - points.minOf { it.lon }) * 111.2 * Math.cos(Math.toRadians((south + north) / 2)) + 2 * CORRIDOR_KM
  return heightKm * widthKm > MAX_AREA_KM2
}

fun bboxRequest(west: Double, south: Double, east: Double, north: Double) = "{\"bbox\":[$west,$south,$east,$north]}"

/** 下载这附近 (§2.3): about 20 × 20 km centred on the point, as west, south, east, north. */
fun nearbyBbox(lat: Double, lon: Double): List<Double> {
  // Half the side in degrees: 10 km over the Earth radius of [haversine].
  val dLat = Math.toDegrees(10_000 / 6_371_000.0)
  val dLon = dLat / Math.cos(Math.toRadians(lat))
  return listOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
}


/** The track as a request body, thinned to about [MAX_REQUEST_POINTS]; the 2 km corridor hides the thinning. */
fun trackRequest(segments: List<List<TrackPoint>>): String {
  val points = segments.flatten()
  val step = maxOf(1, (points.size + MAX_REQUEST_POINTS - 2) / (MAX_REQUEST_POINTS - 1))
  val kept = points.filterIndexed { i, _ -> i % step == 0 } + listOfNotNull(points.lastOrNull()?.takeIf { (points.size - 1) % step != 0 })
  return buildJsonObject { put("track", buildJsonArray { for (p in kept) add(buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }) }) }.toString()
}

// ponytail: every package duplicates all local layers, so layer count grows with packages; merge overlapping
// packages into one archive (pmtiles merge) if people keep dozens.
/**
 * Adds, for each package directory, a copy of every local source (url, or GeoJSON data, under `__DIR__`)
 * pointing into it, and a copy of each layer drawing from such a source right after the original.
 */
fun withPackages(style: String, dirs: List<String>): String {
  if (dirs.isEmpty()) return style
  val root = Json.parseToJsonElement(style).jsonObject
  val base = root["sources"]!!.jsonObject
  val file = { src: JsonObject -> listOf("url", "data").firstOrNull { (src[it] as? JsonPrimitive)?.content?.contains("__DIR__") == true } }
  val local = base.filterValues { file(it.jsonObject) != null }.keys
  val sources = base.toMutableMap()
  dirs.forEachIndexed { i, dir ->
    for (id in local) {
      val src = base[id]!!.jsonObject
      val key = file(src)!!
      sources["$id-pkg$i"] = JsonObject(src + (key to JsonPrimitive(src[key]!!.jsonPrimitive.content.replace("__DIR__", dir))))
    }
  }
  val layers = root["layers"]!!.jsonArray.flatMap { el ->
    val layer = el.jsonObject
    val source = layer["source"]?.jsonPrimitive?.content
    if (source !in local) listOf(layer)
    else listOf(layer) + dirs.indices.map { i ->
      JsonObject(layer + mapOf("id" to JsonPrimitive("${layer["id"]!!.jsonPrimitive.content}-pkg$i"), "source" to JsonPrimitive("$source-pkg$i")))
    }
  }
  return JsonObject(root + mapOf("sources" to JsonObject(sources), "layers" to buildJsonArray { layers.forEach { add(it) } })).toString()
}

/**
 * 地形 online (#53): for each source X with an "X-remote" beside it (the server's tiles of the same data),
 * a copy of each layer drawing from X, right under it, so the local data and the packages draw over it
 * and the remote fills in where they have nothing. Offline its tiles fail and only the local ones remain.
 */
fun withRemote(style: String): String {
  val root = Json.parseToJsonElement(style).jsonObject
  val sources = root["sources"]!!.jsonObject
  val layers = root["layers"]!!.jsonArray.flatMap { el ->
    val layer = el.jsonObject
    val remote = layer["source"]?.jsonPrimitive?.content?.let { "$it-remote" }?.takeIf { it in sources }
    listOfNotNull(remote?.let { JsonObject(layer + mapOf("id" to JsonPrimitive("${layer["id"]!!.jsonPrimitive.content}-remote"), "source" to JsonPrimitive(it))) }, layer)
  }
  return JsonObject(root + ("layers" to buildJsonArray { layers.forEach { add(it) } })).toString()
}

fun writePackage(pkg: OfflinePackage) = File(pkg.dir, "meta.json").writeText(
  buildJsonObject {
    put("name", pkg.name); put("version", pkg.version); put("request", pkg.request); put("bytes", pkg.bytes)
    pkg.outline?.let { put("outline", Json.parseToJsonElement(it)) }
  }.toString()
)

fun readPackage(dir: File): OfflinePackage? = runCatching {
  val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
  OfflinePackage(
    dir, meta["name"]!!.jsonPrimitive.content, meta["version"]!!.jsonPrimitive.content, meta["request"]!!.jsonPrimitive.content, meta["bytes"]!!.jsonPrimitive.long,
    meta["outline"]?.toString(),
  )
}.getOrNull()

/** Headers the API wants on every request, map tiles included: the device ID and the version gate. */
fun apiHeaders(deviceId: String, clientVersion: Long) = mapOf("X-Device-Id" to deviceId, "X-Client-Version" to clientVersion.toString())

/** The anonymous per-install ID sent as X-Device-Id, made on first use. */
fun deviceId(prefs: SharedPreferences): String =
  prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

// One for the process: OkHttp's connection pool and threads, under every [trailClient].
// The server pings the team's WebSocket every minute; our own pings notice a dead connection (a tunnel, no signal) sooner.
private val engine by lazy { OkHttp.create { config { pingInterval(45, TimeUnit.SECONDS) } } }

private val clients = ConcurrentHashMap<Boolean, BaseApi>()

/** This build's generated API client (ADR 0016); [quiet] for background work (or one [quiet] request), which never raises [UpgradePrompt]. */
fun trailClient(prefs: SharedPreferences, quiet: Boolean = false) = clients.getOrPut(quiet) {
  trailClient(BuildConfig.API_URL, deviceId(prefs), BuildConfig.VERSION_CODE.toLong(), engine, quiet) { ClientOutdated.prompt.value = true }
}

/** For the packages' files at their signed URLs, and anything else not the API (GitHub, OpenFreeMap): no device ID and no retry. */
val storage by lazy {
  HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      socketTimeoutMillis = 60_000
    }
  }
}

/**
 * Asks the server for a package and downloads it into [dir] as a readable [OfflinePackage]; [onPercent] as it goes.
 * Each file goes to disk as it comes from [files] ([storage]); one that's refused or of the wrong size fails it.
 */
suspend fun fetchPackage(api: BaseApi, files: HttpClient, name: String, request: String, dir: File, onPercent: (Int) -> Unit = {}): OfflinePackage {
  // Clipping a big area on the server takes a while the first time.
  val res = api.postOfflinePackages(packageRequestDto = wire.decodeFromString(request)) { timeout { socketTimeoutMillis = 120_000 } }.body()
  dir.mkdirs()
  val total = res.files.sumOf { it.bytes }.coerceAtLeast(1)
  var done = 0L
  for (f in res.files) {
    val out = File(dir, f.name.value)
    try {
      files.prepareGet(f.url).execute { response ->
        if (response.status != HttpStatusCode.OK) throw OfflineError(null)
        val input = response.bodyAsChannel()
        out.outputStream().use { output ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = input.readAvailable(buf).takeIf { it >= 0 } ?: break
            output.write(buf, 0, n)
            val before = done * 100 / total
            done += n
            if (done * 100 / total != before) onPercent((done * 100 / total).toInt())
          }
        }
      }
    } catch (e: Exception) {
      // Network failures become [OfflineError]s ([networkCode]); anything else (a full disk) stays a plain failure.
      throw networkCode(e)?.let(::OfflineError) ?: e
    }
    if (out.length() != f.bytes) throw OfflineError(null)
  }
  return OfflinePackage(dir, name, res.version, request, res.bytes, res.outline.takeIf { it !is JsonNull }?.toString()).also(::writePackage)
}

/**
 * 强制升级 (#118): the server no longer serves this build's online features. One prompt for the whole app
 * ([UpgradePrompt]), raised by the launch check ([outdated]) and by any client_outdated answer to
 * something the user did; offline features never ask.
 */
object ClientOutdated {
  val prompt = MutableStateFlow(false)
}
