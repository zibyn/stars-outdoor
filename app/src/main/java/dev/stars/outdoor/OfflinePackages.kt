package dev.stars.outdoor

import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.serialization.json.Json
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
// track corridor; each package is a directory under packages/ holding those four files plus meta.json.

const val MAX_REQUEST_POINTS = 2000

/** A downloaded package; [request] is the JSON body it was made from, re-sent to update it. */
data class OfflinePackage(val dir: File, val name: String, val version: String, val request: String, val bytes: Long)

/** Server error code → what the user sees. */
fun offlineMessage(code: String?): String = when (code) {
  "region_too_large" -> "范围太大：单个离线包约 100 × 100 km 以内，请放大地图后再下载"
  "region_unsupported" -> "该地区暂不支持离线"
  "daily_quota_exceeded" -> "今天的离线下载额度（1 GB）已用完，明天再试"
  "rate_limited" -> "请求太频繁，稍后再试"
  "client_outdated" -> "请更新 App 后再下载离线包"
  "offline" -> "网络不可用，稍后再试"
  else -> "下载失败，稍后再试"
}

fun bboxRequest(west: Double, south: Double, east: Double, north: Double) = "{\"bbox\":[$west,$south,$east,$north]}"

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
 * Adds, for each package directory, a copy of every local source (url under `__DIR__`) pointing into it,
 * and a copy of each layer drawing from such a source right after the original.
 */
fun withPackages(style: String, dirs: List<String>): String {
  if (dirs.isEmpty()) return style
  val root = Json.parseToJsonElement(style).jsonObject
  val base = root["sources"]!!.jsonObject
  val local = base.filterValues { it.jsonObject["url"]?.jsonPrimitive?.content?.contains("__DIR__") == true }.keys
  val sources = base.toMutableMap()
  dirs.forEachIndexed { i, dir ->
    for (id in local) {
      val src = base[id]!!.jsonObject
      sources["$id-pkg$i"] = JsonObject(src + ("url" to JsonPrimitive(src["url"]!!.jsonPrimitive.content.replace("__DIR__", dir))))
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

fun writePackage(pkg: OfflinePackage) = File(pkg.dir, "meta.json").writeText(
  buildJsonObject { put("name", pkg.name); put("version", pkg.version); put("request", pkg.request); put("bytes", pkg.bytes) }.toString()
)

fun readPackage(dir: File): OfflinePackage? = runCatching {
  val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
  OfflinePackage(dir, meta["name"]!!.jsonPrimitive.content, meta["version"]!!.jsonPrimitive.content, meta["request"]!!.jsonPrimitive.content, meta["bytes"]!!.jsonPrimitive.long)
}.getOrNull()

/** Thrown with the server's error code (or "offline") for [offlineMessage]. */
class OfflineError(val code: String?) : Exception(code)

/** Headers the API wants on every request, map tiles included: rate limiting and the version gate. */
fun apiHeaders(deviceId: String, clientVersion: Long) = mapOf("X-Device-Id" to deviceId, "X-Client-Version" to clientVersion.toString())

/** The anonymous per-install ID sent as X-Device-Id, made on first use. */
fun deviceId(prefs: SharedPreferences): String =
  prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

/** This build's API client. */
fun api(prefs: SharedPreferences) = Api(BuildConfig.API_URL, deviceId(prefs), BuildConfig.VERSION_CODE.toLong())

/** The API (server/openapi.yaml). [deviceId] and [clientVersion] go on every request. */
class Api(private val baseUrl: String, private val deviceId: String, private val clientVersion: Long) {
  private val files = setOf("basemap.pmtiles", "dem.pmtiles", "contours.pmtiles", "places.sqlite")

  fun dataVersion(): String = Json.parseToJsonElement(call("GET", "/v1/offline/version", null)).jsonObject["version"]!!.jsonPrimitive.content

  /** 沿途天气 (§2.9) for a [weatherRequest]; the answer as sent, for [parseForecast] and the cache. */
  fun weather(request: String): String = call("POST", "/v1/weather", request)

  /** 搜索 (§2.10) online: Photon and 天地图 through the server, for [rankPlaces]. */
  fun search(query: String, lat: Double, lon: Double): List<Place> {
    val res = Json.parseToJsonElement(call("GET", "/v1/search?q=${URLEncoder.encode(query, "UTF-8")}&lat=$lat&lon=$lon", null)).jsonObject
    return res["places"]!!.jsonArray.map { it.jsonObject }.map { p ->
      Place(p["name"]!!.jsonPrimitive.content, p["kind"]!!.jsonPrimitive.content, p["lat"]!!.jsonPrimitive.double, p["lon"]!!.jsonPrimitive.double, p["detail"]?.jsonPrimitive?.content)
    }
  }

  /** Texts a login code to [phone] (from [mainlandPhone]). */
  fun sendCode(phone: String) {
    call("POST", "/v1/auth/code", buildJsonObject { put("phone", phone) }.toString())
  }

  /** Logs in with the texted [code]; the account, whose token goes on routes that need one. */
  fun login(phone: String, code: String): Account {
    val res = call("POST", "/v1/auth/login", buildJsonObject { put("phone", phone); put("code", code) }.toString())
    return Account(phone, Json.parseToJsonElement(res).jsonObject["token"]!!.jsonPrimitive.content)
  }

  fun logout(account: Account) {
    call("POST", "/v1/auth/logout", null, account.token)
  }

  /** Asks the server for a package and downloads it into [dir] as a readable [OfflinePackage]. */
  fun download(name: String, request: String, dir: File): OfflinePackage {
    val res = Json.parseToJsonElement(call("POST", "/v1/offline/packages", request)).jsonObject
    dir.mkdirs()
    for (f in res["files"]!!.jsonArray.map { it.jsonObject }) {
      val file = f["name"]!!.jsonPrimitive.content.takeIf { it in files } ?: throw OfflineError(null)
      val out = File(dir, file)
      offline {
        (URL(f["url"]!!.jsonPrimitive.content).openConnection() as HttpURLConnection).run {
          connectTimeout = 15_000
          readTimeout = 60_000
          if (responseCode != 200) throw OfflineError(null)
          inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
      }
      if (out.length() != f["bytes"]!!.jsonPrimitive.long) throw OfflineError(null)
    }
    val version = res["version"]!!.jsonPrimitive.content
    val bytes = res["bytes"]!!.jsonPrimitive.long
    return OfflinePackage(dir, name, version, request, bytes).also(::writePackage)
  }

  /** The answer's body; a failure is an [OfflineError] with the server's code ("unauthorized": the token is no longer valid). */
  private fun call(method: String, path: String, body: String?, token: String? = null): String = offline {
    (URL(baseUrl + path).openConnection() as HttpURLConnection).run {
      requestMethod = method
      connectTimeout = 15_000
      // Clipping a big area on the server takes a while the first time.
      readTimeout = 120_000
      for ((k, v) in apiHeaders(deviceId, clientVersion)) setRequestProperty(k, v)
      if (token != null) setRequestProperty("Authorization", "Bearer $token")
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body.toByteArray()) }
      }
      if (responseCode in 200..299) inputStream.bufferedReader().use { it.readText() }
      else throw OfflineError(runCatching { Json.parseToJsonElement(errorStream.bufferedReader().readText()).jsonObject["error"]!!.jsonPrimitive.content }.getOrNull())
    }
  }

  /** Network failures become "offline"; anything else (a full disk) stays a plain failure. */
  private fun <T> offline(block: () -> T): T = try {
    block()
  } catch (e: Exception) {
    throw if (e is UnknownHostException || e is SocketException || e is SocketTimeoutException) OfflineError("offline") else e
  }
}
