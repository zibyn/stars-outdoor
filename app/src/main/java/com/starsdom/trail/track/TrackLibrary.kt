package com.starsdom.trail.track

import android.content.Context
import com.starsdom.trail.Datum
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
