package dev.stars.outdoor

// 同步 (spec §2.12): 轨迹 and 标注 of the logged-in account, once 开启同步. Push what changed here (only the
// attributes that changed: the server keeps the last write of each), then pull what changed there. Runs on
// opening the app, after a recording ends and a few seconds after any change (TrackDb). Photos go up only
// on an unmetered network unless the user allows mobile data; over the 1 GB quota they stay on the phone.

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** SharedPreferences: 同步 on; the account it last synced (another one uploads everything again); the pull cursor; photos on mobile data too. */
const val PREF_SYNC = "sync"
const val PREF_SYNC_ACCOUNT = "sync_account"
const val PREF_SYNC_CURSOR = "sync_cursor"
const val PREF_SYNC_MOBILE_PHOTOS = "sync_mobile_photos"
/** When a sync last got through (ms), for the 状态条. */
const val PREF_SYNC_LAST = "sync_last"

/** TrackDb dirty bits: which attributes changed since the last push. A 轨迹 has name, datum and public (公开轨迹, §2.8), a 标注 name, description and photo. */
const val SYNC_NAME = 1
const val SYNC_DATUM = 2
const val SYNC_PUBLIC = 4
const val SYNC_DESCRIPTION = 2
const val SYNC_PHOTO = 4
const val SYNC_ALL = 7

/** A point as stored (before 纠偏) and its segment. */
data class SyncPoint(val segment: Int, val p: TrackPoint)

/** openapi.yaml SyncPoint, as synced and in a 队伍轨迹. */
fun JsonArrayBuilder.addSyncPoints(points: List<SyncPoint>) {
  for ((s, p) in points) addJsonObject {
    put("t", p.timeMs)
    put("lat", p.lat)
    put("lon", p.lon)
    p.ele?.let { put("ele", it) }
    put("s", s)
  }
}

fun parseSyncPoint(o: JsonObject) = SyncPoint(
  o["s"]!!.jsonPrimitive.int, TrackPoint(o["t"]!!.jsonPrimitive.long, o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double, o["ele"]?.jsonPrimitive?.doubleOrNull),
)

/** A 轨迹 as the server has it (openapi.yaml SyncTrack); [name] null for a recording shown by its start time. */
data class SyncTrack(
  val uuid: String, val startedAt: Long, val endedAt: Long, val planned: Boolean, val points: List<SyncPoint>,
  val name: String?, val datum: Datum, val public: Boolean, val deleted: Boolean,
)

/** A 标注 as the server has it; [track] is its 轨迹's uuid, [photo] the server's photo id (null: none). */
data class SyncWaypoint(
  val uuid: String, val track: String?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val deleted: Boolean,
)

/** A local 轨迹 with changes to push: [synced] false means the server has never seen it. */
data class PendingTrack(
  val id: Long, val uuid: String, val synced: Boolean, val dirty: Int, val edits: Int,
  val startedAt: Long, val endedAt: Long, val planned: Boolean, val name: String?, val datum: String, val public: Boolean,
)

/** A local 标注 with changes to push; [photo] the file, [photoId] the server's id for it ('' = stays local, null = not uploaded). */
data class PendingWaypoint(
  val id: Long, val uuid: String, val synced: Boolean, val dirty: Int, val edits: Int, val deleted: Boolean,
  val track: String?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val photoId: String?,
)

/** The SyncTrackChange for [t] ([points] when the server has never seen it), and the dirty bits it carries. */
fun trackChange(t: PendingTrack, points: List<SyncPoint>?): Pair<JsonObject, Int> {
  val bits = (if (t.synced) t.dirty else SYNC_ALL) and (SYNC_NAME or SYNC_DATUM or SYNC_PUBLIC)
  return buildJsonObject {
    put("id", t.uuid)
    if (!t.synced) {
      put("startedAt", t.startedAt)
      put("endedAt", t.endedAt)
      put("planned", t.planned)
      putJsonArray("points") { addSyncPoints(points.orEmpty()) }
    }
    if (bits and SYNC_NAME != 0) put("name", t.name.orEmpty())
    if (bits and SYNC_DATUM != 0) put("datum", t.datum)
    if (bits and SYNC_PUBLIC != 0) put("public", t.public)
  } to bits
}

/**
 * The SyncWaypointChange for [w], and the dirty bits it carries: a photo not uploaded yet (no Wi-Fi) stays
 * dirty and goes with a later push.
 */
fun waypointChange(w: PendingWaypoint): Pair<JsonObject, Int> {
  if (w.deleted) return buildJsonObject { put("id", w.uuid); put("deleted", true) } to 0
  var bits = if (w.synced) w.dirty else SYNC_ALL
  val photo = if (w.photo == null) "" else w.photoId
  if (photo == null) bits = bits and SYNC_PHOTO.inv()
  return buildJsonObject {
    put("id", w.uuid)
    if (!w.synced) {
      w.track?.let { put("track", it) }
      put("time", w.timeMs)
      put("lat", w.lat)
      put("lon", w.lon)
      w.ele?.let { put("ele", it) }
    }
    if (bits and SYNC_NAME != 0) put("name", w.name)
    if (bits and SYNC_DESCRIPTION != 0) put("description", w.description)
    if (bits and SYNC_PHOTO != 0) put("photo", photo)
  } to bits
}

fun syncChanges(tracks: List<JsonObject>, waypoints: List<JsonObject>): String =
  JsonObject(mapOf("tracks" to JsonArray(tracks), "waypoints" to JsonArray(waypoints))).toString()

/** One pull (openapi.yaml Sync): what changed, the cursor to ask from next, and whether there is more. */
data class SyncPage(val cursor: Long, val more: Boolean, val tracks: List<SyncTrack>, val waypoints: List<SyncWaypoint>)

fun parseSync(json: String): SyncPage {
  val o = Json.parseToJsonElement(json).jsonObject
  fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
  return SyncPage(
    o["cursor"]!!.jsonPrimitive.long, o["more"]!!.jsonPrimitive.boolean,
    o["tracks"]!!.jsonArray.map { it.jsonObject }.map { t ->
      SyncTrack(
        t.str("id"), t["startedAt"]!!.jsonPrimitive.long, t["endedAt"]!!.jsonPrimitive.long, t["planned"]!!.jsonPrimitive.boolean,
        t["points"]!!.jsonArray.map { parseSyncPoint(it.jsonObject) },
        t.str("name").ifEmpty { null }, Datum.entries.firstOrNull { it.name == t.str("datum") } ?: Datum.WGS84,
        t["public"]!!.jsonPrimitive.boolean, t["deleted"]!!.jsonPrimitive.boolean,
      )
    },
    o["waypoints"]!!.jsonArray.map { it.jsonObject }.map { w ->
      SyncWaypoint(
        w.str("id"), w.str("track").ifEmpty { null }, w["time"]!!.jsonPrimitive.long, w["lat"]!!.jsonPrimitive.double, w["lon"]!!.jsonPrimitive.double,
        w["ele"]?.jsonPrimitive?.doubleOrNull, w.str("name"), w.str("description"), w.str("photo").ifEmpty { null }, w["deleted"]!!.jsonPrimitive.boolean,
      )
    },
  )
}

/** Runs 同步 one at a time on its own thread; [changes] ticks when a pull changed local data. */
object CloudSync {
  private val exec = Executors.newSingleThreadScheduledExecutor()
  private var next: ScheduledFuture<*>? = null
  val changes = MutableStateFlow(0)
  /** The last sync didn't get through (状态条); the app asks again when back online. */
  val failed = MutableStateFlow(false)

  /** No sync starts before this (ms): a 标注 whose 撤销 is still on offer stays off the server (§9.1). */
  @Volatile private var heldUntil = 0L

  /** Syncs in [delayMs] (or once [hold] ends), replacing a sync already waiting (edits in a row sync once). Does nothing unless 同步 is on. */
  @Synchronized
  fun request(context: Context, delayMs: Long = 0) {
    val app = context.applicationContext
    next?.cancel(false)
    // ponytail: a sync already running when the hold starts still takes what it finds.
    next = exec.schedule({
      runCatching { run(app) }.onSuccess { failed.value = false }.onFailure {
        Log.w("sync", "sync failed", it)
        failed.value = true
        // Again in 5 minutes (or when back online).
        request(app, 300_000)
      }
    }, maxOf(delayMs, heldUntil - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
  }

  /** Keeps syncs off for [ms], then syncs. */
  fun hold(context: Context, ms: Long) {
    heldUntil = System.currentTimeMillis() + ms
    request(context, ms)
  }

  /** Turns 同步 on for [account]; a different account than last time gets all local data uploaded again. */
  fun enable(context: Context, account: Account) {
    val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    if (prefs.getString(PREF_SYNC_ACCOUNT, null) != account.phone) {
      TrackDb(context).use { it.resetSync() }
      prefs.edit().putString(PREF_SYNC_ACCOUNT, account.phone).putLong(PREF_SYNC_CURSOR, 0).apply()
    }
    prefs.edit().putBoolean(PREF_SYNC, true).apply()
    request(context)
  }

  /** 注销账号 done: nothing here is on a server any more, so a next 开启同步 uploads it all. */
  fun forget(context: Context) {
    context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove(PREF_SYNC).remove(PREF_SYNC_ACCOUNT).remove(PREF_SYNC_CURSOR).remove(PREF_SYNC_LAST).apply()
    failed.value = false
    TrackDb(context).use { it.resetSync() }
  }

  private fun run(ctx: Context) {
    val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PREF_SYNC, false)) return
    val account = AccountStore(prefs).get() ?: return
    val api = api(prefs, quiet = true)
    TrackDb(ctx).use { db ->
      push(ctx, prefs, api, account, db)
      pull(ctx, prefs, api, account, db)
    }
    prefs.edit().putLong(PREF_SYNC_LAST, System.currentTimeMillis()).apply()
  }

  private fun push(ctx: Context, prefs: android.content.SharedPreferences, api: Api, account: Account, db: TrackDb) {
    // One request per track: a new one carries all its points.
    for (t in db.pendingTracks()) {
      val (change, bits) = trackChange(t, if (t.synced) null else db.rawPoints(t.id))
      api.pushSync(account, syncChanges(listOf(change), emptyList()))
      db.pushed("track", t.id, bits, t.edits)
    }
    val unmetered = !ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    if (unmetered || prefs.getBoolean(PREF_SYNC_MOBILE_PHOTOS, false)) {
      for (w in db.pendingWaypoints()) if (!w.deleted && w.photo != null && w.photoId == null) {
        val id = runCatching { api.uploadPhoto(account, shrinkPhoto(ctx, Uri.fromFile(File(w.photo)))) }.getOrElse {
          // Over quota, or the file is gone or unreadable: it stays (or goes) with this phone only.
          if (it is OfflineError && it.code != "photo_quota_exceeded") throw it
          ""
        }
        db.setPhotoId(w.id, w.photo, id)
      }
    }
    // Left out: known ones whose only change is a photo still waiting for Wi-Fi.
    val changes = db.pendingWaypoints().map { it to waypointChange(it) }.filter { (w, c) -> !w.synced || w.deleted || c.second != 0 }
    for (chunk in changes.chunked(500)) {
      api.pushSync(account, syncChanges(emptyList(), chunk.map { it.second.first }))
      for ((w, c) in chunk) if (w.deleted) db.purgeWaypoint(w.id) else db.pushed("waypoint", w.id, c.second, w.edits)
    }
  }

  private fun pull(ctx: Context, prefs: android.content.SharedPreferences, api: Api, account: Account, db: TrackDb) {
    var cursor = prefs.getLong(PREF_SYNC_CURSOR, 0)
    var changed = false
    // 标注 after every page of tracks, so each finds its 轨迹 however the pages fell.
    val waypoints = mutableListOf<SyncWaypoint>()
    do {
      val page = parseSync(api.pullSync(account, cursor))
      for (t in page.tracks) changed = db.applyTrack(t) || changed
      waypoints += page.waypoints
      cursor = page.cursor
    } while (page.more)
    val photos = File(ctx.filesDir, "photos").apply { mkdirs() }
    for (w in waypoints) changed = db.applyWaypoint(w) { id ->
      File(photos, "sync-$id.jpg").apply { writeBytes(api.syncPhoto(account, id)) }.path
    } || changed
    prefs.edit().putLong(PREF_SYNC_CURSOR, cursor).apply()
    if (changed) changes.value++
  }
}
