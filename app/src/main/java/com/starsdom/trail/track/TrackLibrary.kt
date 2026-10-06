package com.starsdom.trail.track

import android.content.Context
import com.starsdom.trail.team.GivenTrack
import com.starsdom.trail.team.TeamTracks
import com.starsdom.trail.ui.noSpace
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轨迹库: 我的轨迹 as the screen sees it — the lists, and (in time) importing, editing, deleting with 撤销, exporting.
 * One for the process ([get]). What it shows is read again on [io] whenever [TrackDb.version] ticks, so a write from
 * anywhere (the recording service, 同步, here) shows without telling anyone. Recording points, 同步 and 离线包 aren't its.
 */
class TrackLibrary(
  private val db: TrackDb,
  /** Where 标注 photos are kept (an import's among them). */
  private val photos: File,
  /** Where 导出 writes its files, for the share sheet to hand on. */
  private val exports: File,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : TeamTracks {
  companion object {
    @Volatile private var instance: TrackLibrary? = null

    fun get(context: Context): TrackLibrary = instance ?: synchronized(this) {
      instance ?: TrackLibrary(TrackDb.get(context), File(context.filesDir, "photos"), File(context.cacheDir, "exports"), CoroutineScope(SupervisorJob())).also { instance = it }
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

  /** The tracks here, for what this phone keeps by their id ([knownRefs]); null until read. */
  val known: StateFlow<KnownTracks?> = db.version.map { v -> KnownTracks(v, db.trackIds(), db.trashedTracks().toSet()) }.flowOn(io)
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

  /** Whether [k] is still how things are: no write since it was read. */
  fun current(k: KnownTracks) = k.version == db.version.value

  /** Track [id] for 轨迹详情, 参考轨迹 or 叠加, read again as it changes; null once it's gone. */
  fun detail(id: Long): Flow<TrackDetail?> = db.version.map { db.detail(id) }.flowOn(io).distinctUntilChanged()

  override suspend fun given(id: Long) = detail(id).first()?.let { GivenTrack(it.uuid, it.name, it.segments) }

  /** Track [id]'s points now, corrected from its 坐标纠偏 (§2.6). */
  suspend fun segments(id: Long): List<List<TrackPoint>> = withContext(io) { db.segments(id) }

  /** A recording never ended: its recording was killed, or is running now. */
  suspend fun openTrack(): Long? = withContext(io) { db.openTrack() }

  /** Names recording [id] (§8.3 第 15 条) by [name] of its first point, worked out off the main thread; no points, no name. */
  suspend fun nameRecording(id: Long, name: (TrackPoint) -> String) {
    val start = withContext(io) { db.segments(id).flatten().firstOrNull() } ?: return
    val named = withContext(io) { name(start) }
    write { setName(id, named) }
  }

  /**
   * 结束 on a recording cut off (§8.3 第 18 条): without a point it goes, its 标注 kept on their own (C3-28); else it ends
   * at its last point and is named as [nameRecording]. How far it went, or null if it went.
   */
  suspend fun finishRecording(id: Long, name: (TrackPoint) -> String): Double? {
    val segments = withContext(io) { db.segments(id) }
    if (segments.all { it.isEmpty() }) return null.also { write { discardTrack(id) } }
    write { endAtLastPoint(id) }
    nameRecording(id, name)
    return trackStats(segments).distanceM
  }

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

  /**
   * A track file to import (§2.6), read and told apart by content ([parseTrackFile]); what's wrong with it, if it can't
   * be: unreadable, over 50 MB, or nothing in it.
   */
  suspend fun read(open: () -> InputStream): Read = withContext(io) {
    val bytes = runCatching { open().use { it.readAtMost(MAX_TRACK_FILE_BYTES + 1) } }.getOrElse { return@withContext Read.Unreadable }
    if (bytes.size > MAX_TRACK_FILE_BYTES) return@withContext Read.TooBig
    val file = runCatching { parseTrackFile(bytes) }.getOrElse { return@withContext Read.Unreadable }
    if (file.tracks.isEmpty() && file.waypoints.isEmpty()) Read.Empty else Read.Ok(file)
  }

  /**
   * Imports tracks [selected] of [file] (from [fileName]), each under [importName]; its 标注 go with the first one, or
   * with no track into a new 标注组 named after the file (numbered if taken, #121). Photos from our own zip export come
   * along: a 标注's <link> names its file in the zip. All or nothing: failing, nothing is left behind (photos neither),
   * so 重试 doesn't import twice.
   */
  suspend fun import(fileName: String, file: TrackFile, selected: List<Int>): Imported = write {
    val written = mutableListOf<File>()
    try {
      atomically {
        val waypoints = file.waypoints.map { w ->
          w.copy(photo = w.photo?.let(file.photos::get)?.let { bytes ->
            File(photos, "import-${System.nanoTime()}-${File(w.photo).name}").also(written::add).apply { parentFile!!.mkdirs(); writeBytes(bytes) }.path
          })
        }
        if (file.tracks.isEmpty()) return@atomically Imported(emptyList(), waypoints.size, importGroup(fileName.substringBeforeLast('.'), waypoints), 0.0)
        val ids = selected.mapIndexed { n, i ->
          val t = file.tracks[i]
          importTrack(t, importName(t, fileName, i, file.tracks.size), if (n == 0) waypoints else emptyList(), System.currentTimeMillis(), imported = true)
        }
        Imported(ids, waypoints.size, null, selected.sumOf { trackStats(file.tracks[it].segments).distanceM })
      }
    } catch (e: Throwable) {
      written.forEach(File::delete)
      throw e
    }
  }

  /**
   * A track into 我的轨迹 that isn't from a file: a 周边路网 line, or my copy of a 队伍轨迹 (§2.11), made only once
   * per [uuid]: one already here with it is the one. Its id.
   */
  override suspend fun save(track: ParsedTrack, name: String, uuid: String?): Long = write {
    uuid?.let(::idOf) ?: importTrack(track, name, emptyList(), System.currentTimeMillis(), uuid)
  }

  /** 导出 a track (§8.5 第 14 条) under its name (#146), as [export] does. */
  suspend fun exportTrack(id: Long, kml: Boolean): Export = export(kml) { ExportOf(trackName(id), waypoints(id), segments(id), planned(id)) }

  /** 导出 the 标注 of 标注组 [groupId], or (null) those 不在组里 (#72), as [name]. */
  suspend fun exportWaypoints(groupId: Long?, name: String, kml: Boolean): Export =
    export(kml) { ExportOf(name, waypoints().filter { it.trackId == null && it.groupId == groupId }) }

  /**
   * [what] as a file: GPX, or KML. 标注 photos only travel with GPX: then it's a zip of the GPX and a photos/ folder,
   * each photo linked from its <wpt>. Failing, whether for want of space (C5-35).
   */
  private suspend fun export(kml: Boolean, what: TrackDb.() -> ExportOf): Export = withContext(io) {
    runCatching {
      val (name, all, segments, planned) = db.what()
      val photos = if (kml) emptyList() else all.filter { w -> w.photo?.let { File(it).isFile } == true }
      val waypoints = all.map { w -> w.copy(photo = if (w in photos) "photos/" + File(w.photo!!).name else null) }
      exports.mkdirs()
      when {
        kml -> Export.Ok(File(exports, exportFileName(name, "kml")).apply { writeText(toKml(name, segments, waypoints)) }, "application/vnd.google-earth.kml+xml")
        photos.isEmpty() -> Export.Ok(File(exports, exportFileName(name, "gpx")).apply { writeText(toGpx(name, segments, waypoints, planned)) }, "application/gpx+xml")
        else -> Export.Ok(File(exports, exportFileName(name, "zip")).apply {
          ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(exportFileName(name, "gpx")))
            zip.write(toGpx(name, segments, waypoints, planned).toByteArray())
            for (w in photos) {
              zip.putNextEntry(ZipEntry("photos/" + File(w.photo!!).name))
              File(w.photo).inputStream().use { it.copyTo(zip) }
            }
          }
        }, "application/zip")
      }
    }.getOrElse { if (noSpace(it)) Export.NoSpace else Export.Failed }
  }

  /**
   * 删除 (ux-v3 §8.5 第 15 条), softly: hidden at once (a track's or group's 标注 with it), gone for good — photos and all,
   * and so synced — [UNDO_MS] later unless [Deleted.undo]ne.
   */
  suspend fun delete(kind: Trash, id: Long): Deleted {
    // Its key for purging: when, and never one taken (two deleted within a millisecond each keep their 撤销).
    val at = lastDeleted.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
    // Due before it's hidden: a screen gone meanwhile can't leave it hidden and never deleted.
    val purge = scope.launch {
      delay(UNDO_MS)
      write { purgeTrashed(at) }
    }
    write { trash(kind, id, at) }
    return Deleted {
      purge.cancel()
      write { untrash(kind, id) }
    }
  }

  private val lastDeleted = AtomicLong()

  // What a killed app left deleted while its 撤销 was on offer: gone for good now, never half-deleted. First in line,
  // before anything deleted from now on.
  init {
    scope.launch(writer + NonCancellable) { db.purgeTrashed(null) }
  }
}

/** A file read for import ([TrackLibrary.read]), or what's wrong with it. */
sealed interface Read {
  data class Ok(val file: TrackFile) : Read
  data object Unreadable : Read
  data object TooBig : Read
  data object Empty : Read
}

/** What 导出 wrote: the file and its type, or why it couldn't (no space left, or anything else). */
sealed interface Export {
  data class Ok(val file: File, val type: String) : Export
  data object NoSpace : Export
  data object Failed : Export
}

/** What 导出 writes: a name for the file and its document, 标注, and a track's segments if it's one. */
private data class ExportOf(val name: String, val waypoints: List<Waypoint>, val segments: List<List<TrackPoint>> = emptyList(), val planned: Boolean = false)

/** An import's new [tracks] (none: its [waypoints] went into the new 标注组 [group]), and [distanceM] all told. */
data class Imported(val tracks: List<Long>, val waypoints: Int, val group: Long?, val distanceM: Double)

/** The file name; with several tracks, plus the track's own name or its number. Names inside files are mostly auto-generated (#123). */
fun importName(t: ParsedTrack, fileName: String, index: Int, count: Int): String {
  val base = fileName.substringBeforeLast('.')
  return when {
    count == 1 -> base
    t.name.isBlank() -> "$base ${index + 1}"
    else -> "$base · ${t.name}"
  }
}

/** 撤销 is on offer this long: as long as the 提示条 with it ([com.starsdom.trail.HINT_LONGEST_MS]) and a little after. */
const val UNDO_MS = 8_500L

/** A deletion that can be taken back, as it was, until [UNDO_MS] is over. */
fun interface Deleted {
  suspend fun undo()
}

/** The tracks here as of [version]: [all] this phone has (listed, being recorded, or [trashed] with 撤销 on offer). */
data class KnownTracks(val version: Long, val all: Set<Long>, val trashed: Set<Long>)

/**
 * 轨迹详情: [raw] its points as stored, [segments] as shown (corrected from [datum]); [planned] 计划轨迹, [source] where it
 * came from, [imported] it has a 坐标来源 to pick, [public] 公开轨迹, [uuid] its id on the server and between phones.
 */
data class TrackDetail(
  val id: Long, val name: String, val datum: Datum, val raw: List<List<TrackPoint>>, val planned: Boolean, val source: String?,
  val imported: Boolean, val public: Boolean, val uuid: String,
) {
  val segments = segmentsIn(datum)

  /** Its points as if they came from [d]. */
  fun segmentsIn(d: Datum): List<List<TrackPoint>> =
    if (d == Datum.WGS84) raw else raw.map { s -> s.map { p -> d.toWgs84(p.lat, p.lon).let { (lat, lon) -> p.copy(lat = lat, lon = lon) } } }
}
