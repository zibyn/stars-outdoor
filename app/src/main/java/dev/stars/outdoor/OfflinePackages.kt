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
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Offline packages (§2.3): the server clips basemap/DEM/contours and the 地名索引 (§2.10) to a viewport or
// track corridor; each package is a directory under packages/ holding those four files, the 周边路网 in it
// (§2.8: routes.geojson and a snapshot of the 公开轨迹, public-tracks.geojson), and meta.json.

const val MAX_REQUEST_POINTS = 2000

/** A downloaded package; [request] is the JSON body it was made from, re-sent to update it. */
data class OfflinePackage(val dir: File, val name: String, val version: String, val request: String, val bytes: Long)

/** Server error code → what the user sees. */
fun offlineMessage(code: String?): String = when (code) {
  "region_too_large" -> "范围太大：单个离线包约 100 × 100 km 以内"
  "region_unsupported" -> "该地区暂不支持离线"
  "client_outdated" -> "请更新 App 后再下载离线包"
  "offline" -> "离线地图没下完，没有网络，联网后再下载"
  else -> "离线地图没下完，再试一次"
}

/**
 * 沿线离线地图 (ux-v2 §4.2): the track's package [pkg], the server's [dataVersion] once asked, and [percent] while
 * downloading; [busy] when another package is downloading, which holds back the 下载 button.
 */
fun corridorText(pkg: OfflinePackage?, dataVersion: String?, percent: Int?, busy: Boolean = false): String {
  if (percent != null) return "下载中 $percent%"
  val state = when {
    pkg == null -> "未下载"
    dataVersion != null && pkg.version != dataVersion -> "可更新"
    else -> return "已下载"
  }
  return if (busy) "$state · 等另一个离线包下完" else state
}

fun bboxRequest(west: Double, south: Double, east: Double, north: Double) = "{\"bbox\":[$west,$south,$east,$north]}"

/** 下载这附近 (§2.3): about 20 × 20 km centred on the point, as west, south, east, north. */
fun nearbyBbox(lat: Double, lon: Double): List<Double> {
  // Half the side in degrees: 10 km over the Earth radius of [haversine].
  val dLat = Math.toDegrees(10_000 / 6_371_000.0)
  val dLon = dLat / Math.cos(Math.toRadians(lat))
  return listOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
}

// ponytail: scaled from §2.3's 50 km 山区 at about 25 MB; ask the server for the real size if it's often off.
/** What 下载这附近 asks before it starts (ux-v2 §6.5). */
const val NEARBY_CONFIRM = "下载这附近约 20 × 20 km，大约 4 MB"

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
  buildJsonObject { put("name", pkg.name); put("version", pkg.version); put("request", pkg.request); put("bytes", pkg.bytes) }.toString()
)

fun readPackage(dir: File): OfflinePackage? = runCatching {
  val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
  OfflinePackage(dir, meta["name"]!!.jsonPrimitive.content, meta["version"]!!.jsonPrimitive.content, meta["request"]!!.jsonPrimitive.content, meta["bytes"]!!.jsonPrimitive.long)
}.getOrNull()

/** Thrown with the server's error code (or "offline") for [offlineMessage]. */
class OfflineError(val code: String?) : Exception(code)

/** Headers the API wants on every request, map tiles included: the device ID and the version gate. */
fun apiHeaders(deviceId: String, clientVersion: Long) = mapOf("X-Device-Id" to deviceId, "X-Client-Version" to clientVersion.toString())

/** The anonymous per-install ID sent as X-Device-Id, made on first use. */
fun deviceId(prefs: SharedPreferences): String =
  prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

/** This build's API client. */
fun api(prefs: SharedPreferences) = Api(BuildConfig.API_URL, deviceId(prefs), BuildConfig.VERSION_CODE.toLong())

// The server pings every minute; our own pings notice a dead connection (a tunnel, no signal) sooner.
private val live by lazy { OkHttpClient.Builder().pingInterval(45, TimeUnit.SECONDS).build() }

/** The API (server/openapi.yaml). [deviceId] and [clientVersion] go on every request. */
class Api(private val baseUrl: String, private val deviceId: String, private val clientVersion: Long) {
  private val files = setOf("basemap.pmtiles", "dem.pmtiles", "contours.pmtiles", "places.sqlite", "routes.geojson", "public-tracks.geojson")

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

  /** 经过这里的轨迹 (§2.8): the 公开轨迹 passing within [radiusM] of a point, one GeoJSON FeatureCollection. */
  fun nearbyTracks(lat: Double, lon: Double, radiusM: Double): String = call("GET", "/v1/nearby-tracks?lat=$lat&lon=$lon&radius=$radiusM", null)

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

  /** 队伍 (§2.11): a new team with the caller as 发起人; [name] empty lets the server use 尾号. */
  fun createTeam(account: Account, name: String): Team =
    parseTeam(call("POST", "/v1/teams", buildJsonObject { if (name.isNotEmpty()) put("name", name) }.toString(), account.token))

  fun joinTeam(account: Account, code: String, name: String): Team =
    parseTeam(call("POST", "/v1/teams/join", buildJsonObject { put("code", code); if (name.isNotEmpty()) put("name", name) }.toString(), account.token))

  fun postPositions(account: Account, team: Long, positions: List<TeamPosition>) {
    call("POST", "/v1/teams/$team/positions", positionsJson(positions), account.token)
  }

  fun setSharing(account: Account, team: Long, sharing: Boolean) {
    call("PUT", "/v1/teams/$team/sharing", buildJsonObject { put("sharing", sharing) }.toString(), account.token)
  }

  fun leaveTeam(account: Account, team: Long) {
    call("POST", "/v1/teams/$team/leave", null, account.token)
  }

  fun endTeam(account: Account, team: Long) {
    call("POST", "/v1/teams/$team/end", null, account.token)
  }

  /** The team with what's stored after [after] (its cursor), to catch up without a socket. */
  fun team(account: Account, team: Long, after: Long): Team = parseTeam(call("GET", "/v1/teams/$team?after=$after", null, account.token))

  /** 队伍轨迹 (§2.11): the 发起人 gives ([teamTrackJson]) or 取消; members fetch its points ([parseTeamTrack]). */
  fun putTeamTrack(account: Account, team: Long, track: String) {
    call("PUT", "/v1/teams/$team/track", track, account.token)
  }

  fun deleteTeamTrack(account: Account, team: Long) {
    call("DELETE", "/v1/teams/$team/track", null, account.token)
  }

  fun teamTrack(account: Account, team: Long): String = call("GET", "/v1/teams/$team/track", null, account.token)

  /** Sends a [messageJson] to the 队伍对话; the message as stored. */
  fun postMessage(account: Account, team: Long, message: String): TeamMessage =
    parseMessage(Json.parseToJsonElement(call("POST", "/v1/teams/$team/messages", message, account.token)).jsonObject)

  /** Uploads a JPEG ([shrinkPhoto]); its id, for an image message. */
  fun uploadImage(account: Account, team: Long, jpeg: ByteArray): String =
    Json.parseToJsonElement(String(request("POST", "/v1/teams/$team/images", jpeg, "image/jpeg", account.token))).jsonObject["image"]!!.jsonPrimitive.content

  /** A 对话 photo (JPEG bytes), or its thumbnail; the thumbnail too once the original is gone. */
  fun image(account: Account, team: Long, image: String, thumb: Boolean): ByteArray =
    request("GET", "/v1/teams/$team/images/$image?thumb=$thumb", null, null, account.token)

  /** 注销账号 (§2.12): the server deletes everything of the account's. */
  fun deleteAccount(account: Account) {
    call("DELETE", "/v1/me", null, account.token)
  }

  /** 同步 (§2.12): pushes a [syncChanges]. */
  fun pushSync(account: Account, changes: String) {
    call("POST", "/v1/sync", changes, account.token)
  }

  /** What changed after [after], for [parseSync]. */
  fun pullSync(account: Account, after: Long): String = call("GET", "/v1/sync?after=$after", null, account.token)

  /** Uploads a 标注 photo ([shrinkPhoto]); its id. Over the 1 GB quota: [OfflineError] photo_quota_exceeded. */
  fun uploadPhoto(account: Account, jpeg: ByteArray): String =
    Json.parseToJsonElement(String(request("POST", "/v1/sync/photos", jpeg, "image/jpeg", account.token))).jsonObject["photo"]!!.jsonPrimitive.content

  fun syncPhoto(account: Account, photo: String): ByteArray = request("GET", "/v1/sync/photos/$photo", null, null, account.token)

  /** The team's WebSocket (openapi.yaml /teams/{id}/live), each message a Team to [mergeTeam], from [after] on. */
  fun teamLive(account: Account, team: Long, after: Long, listener: WebSocketListener): WebSocket {
    val request = Request.Builder().url("$baseUrl/v1/teams/$team/live?after=$after").header("Authorization", "Bearer ${account.token}")
    for ((k, v) in apiHeaders(deviceId, clientVersion)) request.header(k, v)
    return live.newWebSocket(request.build(), listener)
  }

  /** Asks the server for a package and downloads it into [dir] as a readable [OfflinePackage]; [onPercent] as it goes. */
  fun download(name: String, request: String, dir: File, onPercent: (Int) -> Unit = {}): OfflinePackage {
    val res = Json.parseToJsonElement(call("POST", "/v1/offline/packages", request)).jsonObject
    dir.mkdirs()
    val listed = res["files"]!!.jsonArray.map { it.jsonObject }
    val total = listed.sumOf { it["bytes"]!!.jsonPrimitive.long }.coerceAtLeast(1)
    var done = 0L
    for (f in listed) {
      val file = f["name"]!!.jsonPrimitive.content.takeIf { it in files } ?: throw OfflineError(null)
      val out = File(dir, file)
      offline {
        (URL(f["url"]!!.jsonPrimitive.content).openConnection() as HttpURLConnection).run {
          connectTimeout = 15_000
          readTimeout = 60_000
          if (responseCode != 200) throw OfflineError(null)
          inputStream.use { input ->
            out.outputStream().use { output ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                val n = input.read(buf).takeIf { it >= 0 } ?: break
                output.write(buf, 0, n)
                val before = done * 100 / total
                done += n
                if (done * 100 / total != before) onPercent((done * 100 / total).toInt())
              }
            }
          }
        }
      }
      if (out.length() != f["bytes"]!!.jsonPrimitive.long) throw OfflineError(null)
    }
    val version = res["version"]!!.jsonPrimitive.content
    val bytes = res["bytes"]!!.jsonPrimitive.long
    return OfflinePackage(dir, name, version, request, bytes).also(::writePackage)
  }

  private fun call(method: String, path: String, body: String?, token: String? = null): String =
    String(request(method, path, body?.toByteArray(), "application/json", token))

  /** The answer's body; a failure is an [OfflineError] with the server's code ("unauthorized": the token is no longer valid). */
  private fun request(method: String, path: String, body: ByteArray?, type: String?, token: String?): ByteArray = offline {
    (URL(baseUrl + path).openConnection() as HttpURLConnection).run {
      requestMethod = method
      connectTimeout = 15_000
      // Clipping a big area on the server takes a while the first time.
      readTimeout = 120_000
      for ((k, v) in apiHeaders(deviceId, clientVersion)) setRequestProperty(k, v)
      if (token != null) setRequestProperty("Authorization", "Bearer $token")
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", type)
        outputStream.use { it.write(body) }
      }
      if (responseCode in 200..299) inputStream.use { it.readBytes() }
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
