package com.starsdom.trail.track

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
}
