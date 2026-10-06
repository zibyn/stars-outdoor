package com.starsdom.trail

// 同步 (spec §2.12): 轨迹, 标注组 and 标注 of the logged-in account, once 开启同步. Push what changed here (only the
// attributes that changed: the server keeps the last write of each), then pull what changed there. Runs on
// opening the app, after a recording ends and a few seconds after any change (TrackDb). Photos go up only
// on an unmetered network unless the user allows mobile data; over the 1 GB quota they stay on the phone.

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.net.model.DatumDto
import com.starsdom.trail.net.model.SyncChangesDto
import com.starsdom.trail.net.model.SyncDto
import com.starsdom.trail.net.model.SyncGroupChangeDto
import com.starsdom.trail.net.model.SyncPointDto
import com.starsdom.trail.net.model.SyncTrackChangeDto
import com.starsdom.trail.net.model.SyncWaypointChangeDto
import com.starsdom.trail.net.option
import com.starsdom.trail.net.orNull
import com.starsdom.trail.track.TrackDb
import com.starsdom.trail.track.TrackPoint
import de.quati.kotlin.util.Option
import io.ktor.client.statement.bodyAsBytes
import io.ktor.util.reflect.typeInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** SharedPreferences: 同步 on; the account it last synced (another one uploads everything again); the pull cursor; photos on mobile data too. */
const val PREF_SYNC = "sync"
const val PREF_SYNC_ACCOUNT = "sync_account"
const val PREF_SYNC_CURSOR = "sync_cursor"
const val PREF_SYNC_MOBILE_PHOTOS = "sync_mobile_photos"
/** When a sync last got through (ms), for the 状态条. */
const val PREF_SYNC_LAST = "sync_last"

/**
 * TrackDb dirty bits: which attributes changed since the last push. A 轨迹 has name, datum and public (公开轨迹, §2.8),
 * a 标注 name, description, photo and group (its 标注组), a 标注组 its name.
 */
const val SYNC_NAME = 1
const val SYNC_DATUM = 2
const val SYNC_PUBLIC = 4
const val SYNC_DESCRIPTION = 2
const val SYNC_PHOTO = 4
const val SYNC_GROUP = 8
const val SYNC_ALL = 15
/** The bits a 轨迹 has. */
const val SYNC_TRACK = SYNC_NAME or SYNC_DATUM or SYNC_PUBLIC

/** A point as stored (before 纠偏) and its segment. */
data class SyncPoint(val segment: Int, val p: TrackPoint)

/** openapi.yaml SyncPoint, as synced and in a 队伍轨迹. */
fun SyncPoint.toDto() = SyncPointDto(p.timeMs, p.lat, p.lon, p.ele.option(), segment)

fun SyncPointDto.toSyncPoint() = SyncPoint(s, TrackPoint(t, lat, lon, ele.orNull()))

/** A 轨迹 as the server has it (openapi.yaml SyncTrack); [name] null for a recording shown by its start time. */
data class SyncTrack(
  val uuid: String, val startedAt: Long, val endedAt: Long, val planned: Boolean, val points: List<SyncPoint>,
  val name: String?, val datum: Datum, val public: Boolean, val deleted: Boolean, val source: String? = null,
)

/** A 标注组 as the server has it. */
data class SyncGroup(val uuid: String, val name: String, val deleted: Boolean)

/** A 标注 as the server has it; [track] is its 轨迹's uuid, [group] its 标注组's, [photo] the server's photo id (null: none). */
data class SyncWaypoint(
  val uuid: String, val track: String?, val group: String?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val deleted: Boolean,
)

/** A local 轨迹 with changes to push: [synced] false means the server has never seen it. */
data class PendingTrack(
  val id: Long, val uuid: String, val synced: Boolean, val dirty: Int, val edits: Int,
  val startedAt: Long, val endedAt: Long, val planned: Boolean, val name: String?, val datum: String, val public: Boolean,
  val deleted: Boolean, val source: String? = null,
)

/** A local 标注 with changes to push; [photo] the file, [photoId] the server's id for it ('' = stays local, null = not uploaded). */
data class PendingWaypoint(
  val id: Long, val uuid: String, val synced: Boolean, val dirty: Int, val edits: Int, val deleted: Boolean,
  val track: String?, val group: String?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val photoId: String?,
)

/** A local 标注组 with changes to push. */
data class PendingGroup(val id: Long, val uuid: String, val synced: Boolean, val dirty: Int, val edits: Int, val name: String, val deleted: Boolean)

/** The SyncGroupChange for [g], and the dirty bits it carries. */
fun groupChange(g: PendingGroup): Pair<SyncGroupChangeDto, Int> {
  if (g.deleted) return SyncGroupChangeDto(g.uuid, deleted = Option.Some(true)) to 0
  val bits = (if (g.synced) g.dirty else SYNC_ALL) and SYNC_NAME
  return SyncGroupChangeDto(g.uuid, name = g.name.takeIf { bits != 0 }.option()) to bits
}

/** The SyncTrackChange for [t] ([points] when the server has never seen it), and the dirty bits it carries. */
fun trackChange(t: PendingTrack, points: List<SyncPoint>?): Pair<SyncTrackChangeDto, Int> {
  if (t.deleted) return SyncTrackChangeDto(t.uuid, deleted = Option.Some(true)) to 0
  val bits = (if (t.synced) t.dirty else SYNC_ALL) and SYNC_TRACK
  val new = !t.synced
  return SyncTrackChangeDto(
    t.uuid,
    startedAt = t.startedAt.takeIf { new }.option(),
    endedAt = t.endedAt.takeIf { new }.option(),
    planned = t.planned.takeIf { new }.option(),
    points = points.orEmpty().map { it.toDto() }.takeIf { new }.option(),
    source = t.source.takeIf { new }.option(),
    name = t.name.orEmpty().takeIf { bits and SYNC_NAME != 0 }.option(),
    datum = DatumDto.fromSerial(t.datum).takeIf { bits and SYNC_DATUM != 0 }.option(),
    public = t.public.takeIf { bits and SYNC_PUBLIC != 0 }.option(),
  ) to bits
}

/**
 * The SyncWaypointChange for [w], and the dirty bits it carries: a photo not uploaded yet (no Wi-Fi) stays
 * dirty and goes with a later push.
 */
fun waypointChange(w: PendingWaypoint): Pair<SyncWaypointChangeDto, Int> {
  if (w.deleted) return SyncWaypointChangeDto(w.uuid, deleted = Option.Some(true)) to 0
  var bits = if (w.synced) w.dirty else SYNC_ALL
  val photo = if (w.photo == null) "" else w.photoId
  if (photo == null) bits = bits and SYNC_PHOTO.inv()
  val new = !w.synced
  return SyncWaypointChangeDto(
    w.uuid,
    track = w.track.takeIf { new }.option(),
    time = w.timeMs.takeIf { new }.option(),
    lat = w.lat.takeIf { new }.option(),
    lon = w.lon.takeIf { new }.option(),
    ele = w.ele.takeIf { new }.option(),
    name = w.name.takeIf { bits and SYNC_NAME != 0 }.option(),
    description = w.description.takeIf { bits and SYNC_DESCRIPTION != 0 }.option(),
    photo = photo.takeIf { bits and SYNC_PHOTO != 0 }.option(),
    group = w.group.orEmpty().takeIf { bits and SYNC_GROUP != 0 }.option(),
  ) to bits
}

fun syncChanges(tracks: List<SyncTrackChangeDto>, waypoints: List<SyncWaypointChangeDto>, groups: List<SyncGroupChangeDto> = emptyList()) =
  SyncChangesDto(tracks, Option.Some(groups), waypoints)

/** One pull (openapi.yaml Sync): what changed, the cursor to ask from next, and whether there is more. */
data class SyncPage(val cursor: Long, val more: Boolean, val tracks: List<SyncTrack>, val groups: List<SyncGroup>, val waypoints: List<SyncWaypoint>)

/** The server's empty strings are this app's nulls. */
fun SyncDto.toPage() = SyncPage(
  cursor, more,
  tracks.map { t ->
    SyncTrack(
      t.id, t.startedAt, t.endedAt, t.planned, t.points.map { it.toSyncPoint() },
      t.name.ifEmpty { null }, Datum.valueOf(t.datum.value), t.public, t.deleted, t.source.ifEmpty { null },
    )
  },
  groups.map { SyncGroup(it.id, it.name, it.deleted) },
  waypoints.map { w ->
    SyncWaypoint(
      w.id, w.track.ifEmpty { null }, w.group.ifEmpty { null }, w.time, w.lat, w.lon, w.ele.orNull(), w.name, w.description,
      w.photo.ifEmpty { null }, w.deleted,
    )
  },
)

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
      TrackDb.get(context).resetSync()
      prefs.edit().putString(PREF_SYNC_ACCOUNT, account.phone).putLong(PREF_SYNC_CURSOR, 0).apply()
    }
    prefs.edit().putBoolean(PREF_SYNC, true).apply()
    request(context)
  }

  /** 注销账号 done: nothing here is on a server any more, so a next 开启同步 uploads it all. */
  fun forget(context: Context) {
    context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove(PREF_SYNC).remove(PREF_SYNC_ACCOUNT).remove(PREF_SYNC_CURSOR).remove(PREF_SYNC_LAST).apply()
    failed.value = false
    TrackDb.get(context).resetSync()
  }

  private fun run(ctx: Context) {
    val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PREF_SYNC, false)) return
    val account = AccountStore(prefs).get() ?: return
    val api = trailClient(prefs, quiet = true)
    val db = TrackDb.get(ctx)
    runBlocking {
      push(ctx, prefs, api, account, db)
      pull(ctx, prefs, api, account, db)
    }
    prefs.edit().putLong(PREF_SYNC_LAST, System.currentTimeMillis()).apply()
  }

  private suspend fun push(ctx: Context, prefs: android.content.SharedPreferences, api: BaseApi, account: Account, db: TrackDb) {
    val auth = account.auth()
    // One request per track: a new one carries all its points.
    for (t in db.pendingTracks()) {
      val (change, bits) = trackChange(t, if (t.synced) null else db.rawPoints(t.id))
      api.postSync(syncChangesDto = syncChanges(listOf(change), emptyList()), block = auth)
      if (t.deleted) db.purgeTrack(t.id) else db.pushed("track", t.id, bits, t.edits)
    }
    // Before the 标注 that name them.
    val groups = db.pendingGroups().map { it to groupChange(it) }
    if (groups.isNotEmpty()) api.postSync(syncChangesDto = syncChanges(emptyList(), emptyList(), groups.map { it.second.first }), block = auth)
    for ((g, c) in groups) if (g.deleted) db.purgeGroup(g.id) else db.pushed("waypoint_group", g.id, c.second, g.edits)
    val unmetered = !ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    if (unmetered || prefs.getBoolean(PREF_SYNC_MOBILE_PHOTOS, false)) {
      for (w in db.pendingWaypoints()) if (!w.deleted && w.photo != null && w.photoId == null) {
        val id = runCatching { api.postSyncPhoto(string = shrinkPhoto(ctx, Uri.fromFile(File(w.photo))), bodyType = typeInfo<ByteArray>(), block = auth).body().photo }.getOrElse {
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
      api.postSync(syncChangesDto = syncChanges(emptyList(), chunk.map { it.second.first }), block = auth)
      for ((w, c) in chunk) if (w.deleted) db.purgeWaypoint(w.id) else db.pushed("waypoint", w.id, c.second, w.edits)
    }
  }

  private suspend fun pull(ctx: Context, prefs: android.content.SharedPreferences, api: BaseApi, account: Account, db: TrackDb) {
    val auth = account.auth()
    var cursor = prefs.getLong(PREF_SYNC_CURSOR, 0)
    var changed = false
    // 标注组, then 标注, after every page of tracks, so each finds its 轨迹 or 标注组 however the pages fell.
    val groups = mutableListOf<SyncGroup>()
    val waypoints = mutableListOf<SyncWaypoint>()
    do {
      val page = api.getSync(after = cursor, block = auth).body().toPage()
      for (t in page.tracks) changed = db.applyTrack(t) || changed
      groups += page.groups
      waypoints += page.waypoints
      cursor = page.cursor
    } while (page.more)
    for (g in groups) changed = db.applyGroup(g) || changed
    val photos = File(ctx.filesDir, "photos").apply { mkdirs() }
    // TrackDb asks for the photo as it takes the 标注 in, blocking: this thread is the sync's own.
    for (w in waypoints) changed = db.applyWaypoint(w) { id ->
      val jpeg = runBlocking { api.prepareGetSyncPhoto(photo = id, block = auth).execute { it.bodyAsBytes() } }
      File(photos, "sync-$id.jpg").apply { writeBytes(jpeg) }.path
    } || changed
    prefs.edit().putLong(PREF_SYNC_CURSOR, cursor).apply()
    if (changed) changes.value++
  }
}
