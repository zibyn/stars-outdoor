package com.starsdom.trail

import android.content.SharedPreferences
import android.util.Log
import com.starsdom.trail.net.outdated
import com.starsdom.trail.net.trailClient
import com.starsdom.trail.track.TrackPoint
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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

/** This build's API client; [quiet] for background work, which never raises [UpgradePrompt]. */
fun api(prefs: SharedPreferences, quiet: Boolean = false) = Api(BuildConfig.API_URL, deviceId(prefs), BuildConfig.VERSION_CODE.toLong(), quiet)

// One for the process: OkHttp's connection pool and threads, under every [trailClient].
private val engine by lazy { OkHttp.create() }

/** This build's generated API client (#219), taking over from [api] call by call; [quiet] requests never raise [UpgradePrompt]. */
fun trailClient(prefs: SharedPreferences) = trailClient(BuildConfig.API_URL, deviceId(prefs), BuildConfig.VERSION_CODE.toLong(), engine) { ClientOutdated.prompt.value = true }

/**
 * 强制升级 (#118): the server no longer serves this build's online features. One prompt for the whole app
 * ([UpgradePrompt]), raised by the launch check ([outdated]) and by any client_outdated answer to
 * something the user did; offline features never ask.
 */
object ClientOutdated {
  val prompt = MutableStateFlow(false)
}

// The server pings every minute; our own pings notice a dead connection (a tunnel, no signal) sooner.
private val live by lazy { OkHttpClient.Builder().pingInterval(45, TimeUnit.SECONDS).build() }

/** The API (server/openapi.yaml). [deviceId] and [clientVersion] go on every request; client_outdated raises [ClientOutdated] unless [quiet]. */
class Api(private val baseUrl: String, private val deviceId: String, private val clientVersion: Long, private val quiet: Boolean = false) {
  private val files = setOf("basemap.pmtiles", "dem.pmtiles", "contours.pmtiles", "places.sqlite", "routes.geojson", "public-tracks.geojson")

  /** 沿途天气 (§2.9) for a [weatherRequest]; the answer as sent, for [parseForecast] and the cache. */
  fun weather(request: String): String = call("POST", "/v1/weather", request, retry = true)

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

  /** 队伍 (§2.11): a new team with the caller as 发起人; members go by their account's 昵称. */
  fun createTeam(account: Account): Team = parseTeam(call("POST", "/v1/teams", "{}", account.token))

  /** The 队伍卡片 for [code], joining nothing; team_not_found if no active team has it. */
  fun teamCard(account: Account, code: String): TeamCard = parseTeamCard(call("GET", "/v1/teams/join?code=$code", null, account.token))

  fun joinTeam(account: Account, code: String): Team =
    parseTeam(call("POST", "/v1/teams/join", buildJsonObject { put("code", code) }.toString(), account.token))

  /** The account's 昵称 and 头像 id (ux-v3 §8.4 第 5、6 条; null: none). */
  fun me(account: Account): Pair<String, String?> = meOf(call("GET", "/v1/me", null, account.token))

  /** 换头像: [jpeg] as [avatarJpeg] makes it; its new id. The server tells the team. */
  fun setAvatar(account: Account, jpeg: ByteArray): String =
    meOf(String(request("PUT", "/v1/me/avatar", jpeg, "image/jpeg", account.token))).second!!

  /** 不用头像. */
  fun dropAvatar(account: Account) {
    call("DELETE", "/v1/me/avatar", null, account.token)
  }

  /** A 头像's JPEG, anyone's. */
  fun avatar(account: Account, id: String): ByteArray = request("GET", "/v1/avatars/$id", null, null, account.token)

  private fun meOf(json: String) = Json.parseToJsonElement(json).jsonObject.let { it["nickname"]!!.jsonPrimitive.content to it["avatar"]?.jsonPrimitive?.content }

  /** 改昵称: [name] as [nicknameOf] gives it; the server tells the team. */
  fun setNickname(account: Account, name: String) {
    call("PUT", "/v1/me/nickname", buildJsonObject { put("nickname", name) }.toString(), account.token)
  }

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

  /** Uploads a 对话 photo, telling [progress] how much went (0–1); its id. */
  fun uploadImage(account: Account, team: Long, jpeg: ByteArray, progress: (Float) -> Unit = {}): String =
    Json.parseToJsonElement(String(request("POST", "/v1/teams/$team/images", jpeg, "image/jpeg", account.token, progress))).jsonObject["image"]!!.jsonPrimitive.content

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
    // Clipping a big area on the server takes a while the first time.
    val res = Json.parseToJsonElement(call("POST", "/v1/offline/packages", request, readTimeoutMs = 120_000)).jsonObject
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
    return OfflinePackage(dir, name, version, request, bytes, res["outline"]?.takeIf { it !is JsonNull }?.toString()).also(::writePackage)
  }

  private fun call(method: String, path: String, body: String?, token: String? = null, readTimeoutMs: Int = 15_000, retry: Boolean = method != "POST"): String =
    String(request(method, path, body?.toByteArray(), "application/json", token, readTimeoutMs = readTimeoutMs, retry = retry))

  /**
   * The answer's body; a failure is an [OfflineError] with the server's code ("unauthorized": the token is no longer valid).
   * A [retryable] failure goes once more when [retry] (#133): a pooled connection can die silently, and the body, streamed, can't be resent on it.
   * Not POSTs, unless said: the server may have done it already (a message sent twice, a 验证码 used up).
   */
  private fun request(
    method: String, path: String, body: ByteArray?, type: String?, token: String?, progress: ((Float) -> Unit)? = null, readTimeoutMs: Int = 15_000,
    retry: Boolean = method != "POST",
  ): ByteArray = offline {
    try {
      attempt(method, path, body, type, token, progress, readTimeoutMs)
    } catch (e: Exception) {
      logFailure(method, path, e, false)
      if (!retry || !retryable(e)) throw e
      // ponytail: the dead connection is closed ([attempt]), but other idle ones in the pool may be dead too.
      try {
        attempt(method, path, body, type, token, progress, readTimeoutMs)
      } catch (again: Exception) {
        logFailure(method, path, again, true)
        throw again
      }
    }
  }

  private fun attempt(method: String, path: String, body: ByteArray?, type: String?, token: String?, progress: ((Float) -> Unit)?, readTimeoutMs: Int): ByteArray {
    val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
    try {
      return conn.run {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = readTimeoutMs
        for ((k, v) in apiHeaders(deviceId, clientVersion)) setRequestProperty(k, v)
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
          doOutput = true
          setRequestProperty("Content-Type", type)
          // Streamed in pieces, so [progress] can follow it (unbuffered, so it's the network's pace, not memory's).
          setFixedLengthStreamingMode(body.size)
          val piece = 16 * 1024
          outputStream.use { out ->
            for (from in body.indices step piece) {
              out.write(body, from, minOf(piece, body.size - from))
              progress?.invoke(minOf(from + piece, body.size) / body.size.toFloat())
            }
          }
        }
        if (responseCode in 200..299) return@run inputStream.use { it.readBytes() }
        val code = runCatching { Json.parseToJsonElement(errorStream.bufferedReader().readText()).jsonObject["error"]!!.jsonPrimitive.content }.getOrNull()
        if (code == "client_outdated" && !quiet) ClientOutdated.prompt.value = true
        throw OfflineError(code)
      }
    } catch (e: IOException) {
      // Closes the dead connection rather than handing it back to the pool for the next request.
      conn.disconnect()
      throw e
    }
  }

  /** One line per failed attempt that isn't the server's answer: what was asked (no query, body or token) and how it failed. */
  private fun logFailure(method: String, path: String, e: Exception, retried: Boolean) {
    if (e !is OfflineError) Log.w("Api", "$method ${path.substringBefore('?')} failed: ${e.javaClass.simpleName}${if (retried) " (retried)" else ""}")
  }

  /** Network failures become [OfflineError]s ([networkCode]); anything else (a full disk) stays a plain failure. */
  private fun <T> offline(block: () -> T): T = try {
    block()
  } catch (e: Exception) {
    throw networkCode(e)?.let(::OfflineError) ?: e
  }
}
