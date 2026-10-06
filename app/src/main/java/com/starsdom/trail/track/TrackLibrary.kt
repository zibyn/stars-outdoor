package com.starsdom.trail.track

import android.content.Context
import com.starsdom.trail.Datum
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * 轨迹库: 我的轨迹 as the screen sees it — the lists, and (in time) importing, editing, deleting with 撤销, exporting.
 * One for the process ([get]). What it shows is read again on [io] whenever [TrackDb.version] ticks, so a write from
 * anywhere (the recording service, 同步, here) shows without telling anyone. Recording points, 同步 and 离线包 aren't its.
 */
class TrackLibrary(private val db: TrackDb, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
  companion object {
    @Volatile private var instance: TrackLibrary? = null

    fun get(context: Context): TrackLibrary = instance ?: synchronized(this) {
      instance ?: TrackLibrary(TrackDb.get(context), CoroutineScope(SupervisorJob())).also { instance = it }
    }
  }

  /** [query] read again as the database changes, while anyone watches; the last read kept between. */
  private fun <T> watch(initial: T, query: TrackDb.() -> T): StateFlow<T> =
    db.version.map { db.query() }.flowOn(io).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

  /** 我的轨迹's tracks, the last to come onto this phone first. */
  val tracks: StateFlow<List<TrackSummary>> = watch(emptyList()) { tracks() }

  /** Every 标注 (a track's with its 坐标纠偏), by time. */
  val waypoints: StateFlow<List<Waypoint>> = watch(emptyList()) { waypoints() }

  /** 标注组 by name, with their counts. */
  val groups: StateFlow<List<WaypointGroup>> = watch(emptyList()) { groups() }

  /** Track [id] for 轨迹详情, 参考轨迹 or 叠加, read again as it changes; null once it's gone. */
  fun detail(id: Long): Flow<TrackDetail?> = db.version.map { db.detail(id) }.flowOn(io).distinctUntilChanged()

  /** Track [id]'s points now, corrected from its 坐标纠偏 (§2.6). */
  suspend fun segments(id: Long): List<List<TrackPoint>> = withContext(io) { db.segments(id) }

  /** Names a new 标注组 can't take: those whose 撤销 is still on offer too. */
  suspend fun groupNames(): Set<String> = withContext(io) { db.groupNames() }

  // Writes one at a time, in the order asked (C5-22: a 标注's text as typed, letter by letter), and done once asked for,
  // the screen gone or not.
  private val writer = io.limitedParallelism(1)

  private suspend fun <T> write(block: TrackDb.() -> T): T = withContext(writer + NonCancellable) { db.block() }

  /** 改名; blank goes back to its start time. */
  suspend fun rename(id: Long, name: String) = write { setName(id, name) }

  /** 坐标纠偏 (§2.6). */
  suspend fun setDatum(id: Long, datum: Datum) = write { setDatum(id, datum) }

  /** 公开 or 撤回 (§2.8); the server does it on the next push. */
  suspend fun setPublic(id: Long, public: Boolean) = write { setPublic(id, public) }

  /** 截取 (#88): points [range] of track [id] into a new private track; the original stays as it was. The new id. */
  suspend fun trim(id: Long, range: IntRange, name: String, source: String, now: Long): Long = write { trimTrack(id, range, name, source, now) }

  /** Tracks (not plans) whose times overlap: they can't be 合并ed. */
  suspend fun mergeOverlaps(ids: List<Long>): Boolean = withContext(io) { db.mergeOverlaps(ids) }

  /** 合并 (#189): [ids] one after another into a new private track; the originals stay as they were. The new id. */
  suspend fun merge(ids: List<Long>, name: String, source: String, now: Long): Long = write { mergeTracks(ids, name, source, now) }

  /** A 标注 named [name], on [trackId] if given (the track being recorded). */
  suspend fun addWaypoint(trackId: Long?, timeMs: Long, lat: Double, lon: Double, ele: Double?, name: String): Waypoint = write {
    Waypoint(addWaypoint(trackId, timeMs, lat, lon, ele).also { updateWaypoint(it, name, "", null) }, trackId, timeMs, lat, lon, ele, name, "", null)
  }

  /** A 标注's name and description as typed (C5-22), its photo left as it is. */
  suspend fun setWaypointText(id: Long, name: String, description: String) = write { setWaypointText(id, name, description) }

  /** A 标注's photo now [photo] (a file of its own), with its text as typed; the one it had goes. */
  suspend fun setWaypointPhoto(id: Long, name: String, description: String, photo: String) = write {
    val old = waypointPhoto(id)
    updateWaypoint(id, name, description, photo)
    if (old != null && old != photo) File(old).delete()
  }

  /** At once, photo and all: a 标注 just made whose 撤销 was tapped (it never reached the server). */
  suspend fun deleteWaypoint(id: Long) = write { deleteWaypoint(id) }

  /** A 标注 not on a track into [groupId] (null: out of groups), shown there. */
  suspend fun moveWaypoint(id: Long, groupId: Long?) = write { setWaypointGroup(id, groupId) }

  /** 叠加 of a 标注 not on a track nor in a group. */
  suspend fun setWaypointShown(id: Long, shown: Boolean) = write { setWaypointShown(id, shown) }

  /** 新建标注组 (#121): its id, or null if [name] is taken. */
  suspend fun addGroup(name: String): Long? = write { addGroup(name) }

  /** False if another group has [name]. */
  suspend fun renameGroup(id: Long, name: String): Boolean = write { renameGroup(id, name) }

  /** 叠加 of a 标注组. */
  suspend fun setGroupShown(id: Long, shown: Boolean) = write { setGroupShown(id, shown) }
}

/**
 * 轨迹详情: [raw] its points as stored, [segments] as shown (corrected from [datum]); [planned] 计划轨迹, [source] where it
 * came from, [imported] it has a 坐标来源 to pick, [public] 公开轨迹.
 */
data class TrackDetail(
  val id: Long, val name: String, val datum: Datum, val raw: List<List<TrackPoint>>, val planned: Boolean, val source: String?,
  val imported: Boolean, val public: Boolean,
) {
  val segments = segmentsIn(datum)

  /** Its points as if they came from [d]. */
  fun segmentsIn(d: Datum): List<List<TrackPoint>> =
    if (d == Datum.WGS84) raw else raw.map { s -> s.map { p -> d.toWgs84(p.lat, p.lon).let { (lat, lon) -> p.copy(lat = lat, lon = lon) } } }
}
