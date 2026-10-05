package com.starsdom.outdoor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [timeMs] is 0 when unknown (an imported GPX <rte>, or a line without times). */
data class TrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?)

/** What can be deleted softly ([TrackDb.trash]), by its table. */
enum class Trash(val table: String) { Track("track"), Group("waypoint_group"), Waypoint("waypoint") }

/** A row of 我的轨迹: [startedMs] is when it was walked (its date), [public] 已公开. */
data class TrackSummary(val id: Long, val name: String, val planned: Boolean, val startedMs: Long = 0, val public: Boolean = false)

/**
 * 标注. [trackId] is set when it was added while recording (or imported with a track), else it may be in 标注组
 * [groupId]; [photo] is a file path. [shown]: 叠加 on, its group's if in one (a track's goes with the track).
 */
data class Waypoint(
  val id: Long, val trackId: Long?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val groupId: Long? = null, val shown: Boolean = true,
)

/** 标注组 with its [count] of 标注; [shown]: 叠加 on. */
data class WaypointGroup(val id: Long, val name: String, val shown: Boolean, val count: Int)

/** [name], or numbered with the first 「 (n)」 not [taken] (a number it had is replaced, not added to). */
fun uniqueName(name: String, taken: Set<String>): String {
  if (name !in taken) return name
  val base = name.replace(Regex(""" \(\d+\)$"""), "")
  return generateSequence(1) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
}

class TrackDb(private val context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 12) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE track (id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER)")
    db.execSQL(
      "CREATE TABLE point (track_id INTEGER NOT NULL REFERENCES track(id), time INTEGER NOT NULL, " +
        "lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, segment INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("CREATE INDEX point_track ON point(track_id, time)")
    immutablePoints(db)
    createWaypoints(db)
    trackAttributes(db)
    syncColumns(db)
    publicColumn(db)
    sourceColumn(db)
    trackDeleted(db)
    waypointGroups(db)
    addedColumn(db)
    importedColumn(db)
    trashedColumns(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE point ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
      immutablePoints(db)
    }
    if (oldVersion < 3) createWaypoints(db)
    if (oldVersion < 4) trackAttributes(db)
    if (oldVersion < 5) syncColumns(db)
    if (oldVersion < 6) publicColumn(db)
    if (oldVersion < 7) sourceColumn(db)
    if (oldVersion < 8) trackDeleted(db)
    if (oldVersion < 9) waypointGroups(db)
    if (oldVersion < 10) addedColumn(db)
    if (oldVersion < 11) importedColumn(db)
    if (oldVersion < 12) trashedColumns(db)
  }

  // 软删除 (ux-v3 §8.5 第 15 条): when it was deleted (0: it wasn't). Hidden here at once, deleted for good — and so
  // synced — once its 撤销 is over ([purgeTrashed]). A track's or group's 标注 hide with it.
  private fun trashedColumns(db: SQLiteDatabase) {
    for (t in Trash.entries) db.execSQL("ALTER TABLE ${t.table} ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
  }

  /** Hides [id] of [kind], deleted at [at] (its key for [purgeTrashed]). */
  fun trash(kind: Trash, id: Long, at: Long) =
    writableDatabase.execSQL("UPDATE ${kind.table} SET trashed = ? WHERE id = ?", arrayOf<Any?>(at, id))

  /** 撤销: back as it was, if not purged yet. */
  fun untrash(kind: Trash, id: Long) =
    writableDatabase.execSQL("UPDATE ${kind.table} SET trashed = 0 WHERE id = ?", arrayOf<Any?>(id))

  fun trashedTracks(): List<Long> =
    readableDatabase.rawQuery("SELECT id FROM track WHERE trashed <> 0", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

  /** Deletes for good what was trashed at [at], or (null) everything trashed: what a killed app left hidden. The tracks gone. */
  fun purgeTrashed(at: Long?): List<Long> {
    fun ids(kind: Trash) = readableDatabase.rawQuery(
      "SELECT id FROM ${kind.table} WHERE trashed <> 0" + if (at != null) " AND trashed = ?" else "", at?.let { arrayOf(it.toString()) },
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    ids(Trash.Waypoint).forEach(::deleteWaypoint)
    ids(Trash.Group).forEach(::deleteGroup)
    return ids(Trash.Track).onEach(::deleteTrack)
  }

  // From a file the user imported: only those have a 坐标来源 to pick (§8.2 第 8 条). Kept here only; tracks from before
  // count by their name (a recording has none unless renamed, as do 队伍 ones), and a pulled one by its 纠偏 ([imported]).
  private fun importedColumn(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE track ADD COLUMN imported INTEGER NOT NULL DEFAULT 0")
    db.execSQL("UPDATE track SET imported = 1 WHERE name IS NOT NULL AND source IS NULL")
  }

  // 加入时间 (ux-v3 §10): when it came onto this phone, which 我的轨迹 lists by. A recording's start; an import, sync or
  // 队伍 copy's arrival. Kept here only, not synced; tracks from before go by their start.
  private fun addedColumn(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE track ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
    db.execSQL("UPDATE track SET added_at = started_at")
  }

  // 标注组 (#121): synced like 标注, names unique among the live ones. shown is 叠加, kept on this phone only (not
  // synced): on for what was made here, off for what a pull brings. A 标注's own shown counts only outside a group.
  private fun waypointGroups(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE waypoint_group (id INTEGER PRIMARY KEY, uuid TEXT, name TEXT NOT NULL, shown INTEGER NOT NULL DEFAULT 1, " +
        "synced INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT $SYNC_ALL, edits INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("CREATE UNIQUE INDEX waypoint_group_uuid ON waypoint_group(uuid)")
    db.execSQL("CREATE TRIGGER waypoint_group_uuid AFTER INSERT ON waypoint_group WHEN NEW.uuid IS NULL BEGIN UPDATE waypoint_group SET uuid = lower(hex(randomblob(16))) WHERE id = NEW.id; END")
    db.execSQL("CREATE UNIQUE INDEX waypoint_group_name ON waypoint_group(name) WHERE NOT deleted")
    db.execSQL("ALTER TABLE waypoint ADD COLUMN group_id INTEGER REFERENCES waypoint_group(id)")
    db.execSQL("ALTER TABLE waypoint ADD COLUMN shown INTEGER NOT NULL DEFAULT 1")
  }

  // A synced 轨迹 deleted here stays, without its points, as a 删除标记 until the server has heard (like a 标注's).
  private fun trackDeleted(db: SQLiteDatabase) = db.execSQL("ALTER TABLE track ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")

  // Where the track came from, shown small under its name (ux-v3 §8.2 第 5 条): 「由队伍位置共享生成」, 「来自 佳明 fēnix 7」 (#89); null for most.
  private fun sourceColumn(db: SQLiteDatabase) = db.execSQL("ALTER TABLE track ADD COLUMN source TEXT")

  // 公开轨迹 (§2.8): the owner made it public; a synced attribute like the name.
  private fun publicColumn(db: SQLiteDatabase) = db.execSQL("ALTER TABLE track ADD COLUMN public INTEGER NOT NULL DEFAULT 0")

  // 同步 (§2.12): uuid is the id on the server; synced: the server has the row; dirty: attribute bits (SYNC_*)
  // changed since the last push; edits counts local changes, so a push only clears what it sent. A new row is
  // all dirty. A synced 标注 deleted here stays as a deleted row until the server has heard.
  private fun syncColumns(db: SQLiteDatabase) {
    for (t in listOf("track", "waypoint")) {
      db.execSQL("ALTER TABLE $t ADD COLUMN uuid TEXT")
      db.execSQL("UPDATE $t SET uuid = lower(hex(randomblob(16)))")
      db.execSQL("CREATE UNIQUE INDEX ${t}_uuid ON $t(uuid)")
      db.execSQL("CREATE TRIGGER ${t}_uuid AFTER INSERT ON $t WHEN NEW.uuid IS NULL BEGIN UPDATE $t SET uuid = lower(hex(randomblob(16))) WHERE id = NEW.id; END")
      db.execSQL("ALTER TABLE $t ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE $t ADD COLUMN dirty INTEGER NOT NULL DEFAULT $SYNC_ALL")
      db.execSQL("ALTER TABLE $t ADD COLUMN edits INTEGER NOT NULL DEFAULT 0")
    }
    db.execSQL("ALTER TABLE waypoint ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    // The server's id for the photo in `photo`; '' when it stays on this phone (over quota, or unreadable).
    db.execSQL("ALTER TABLE waypoint ADD COLUMN photo_id TEXT")
  }

  /** A local change to be synced after a short delay (§2.12). */
  private fun changed() = CloudSync.request(context, 5_000)

  // name: null for recordings (shown by start time); planned: an imported GPX <rte>; datum: 坐标纠偏 (§2.6).
  private fun trackAttributes(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE track ADD COLUMN name TEXT")
    db.execSQL("ALTER TABLE track ADD COLUMN planned INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE track ADD COLUMN datum TEXT NOT NULL DEFAULT 'WGS84'")
  }

  // Unlike points, 标注 stay editable after the track ends: name and photo are filled in afterwards (§2.4).
  private fun createWaypoints(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE waypoint (id INTEGER PRIMARY KEY, track_id INTEGER REFERENCES track(id), time INTEGER NOT NULL, " +
        "lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, name TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '', photo TEXT)"
    )
  }

  // §2.5: once a track has ended its points are fixed; only its attributes may change.
  // DELETE stays allowed so a whole track (or account) can still be deleted.
  private fun immutablePoints(db: SQLiteDatabase) {
    for ((event, row) in listOf("INSERT" to "NEW", "UPDATE" to "OLD")) {
      db.execSQL(
        "CREATE TRIGGER point_immutable_${event.lowercase()} BEFORE $event ON point " +
          "WHEN (SELECT ended_at FROM track WHERE id = $row.track_id) IS NOT NULL " +
          "BEGIN SELECT RAISE(ABORT, 'track ended: points are immutable'); END"
      )
    }
  }

  fun startTrack(now: Long): Long = writableDatabase.insertOrThrow("track", null, ContentValues().apply { put("started_at", now); put("added_at", now) })

  fun endTrack(id: Long, now: Long) {
    writableDatabase.update("track", ContentValues().apply { put("ended_at", now) }, "id = ?", arrayOf(id.toString()))
    changed()
  }

  /** Ends [id] at its last point (for a recording whose process was killed). */
  fun endAtLastPoint(id: Long) {
    writableDatabase.execSQL(
      "UPDATE track SET ended_at = coalesce((SELECT max(time) FROM point WHERE track_id = id), started_at) WHERE id = ?",
      arrayOf(id),
    )
    changed()
  }

  /** A recording that never got a point (C3-28): gone, its 标注 kept on their own. */
  fun discardTrack(id: Long) = writableDatabase.transaction {
    execSQL("UPDATE waypoint SET track_id = NULL WHERE track_id = ?", arrayOf(id))
    delete("point", "track_id = ?", arrayOf(id.toString()))
    delete("track", "id = ?", arrayOf(id.toString()))
  }.also { changed() }

  /** A track never ended: its recording was killed, or is running now. */
  fun openTrack(): Long? =
    readableDatabase.rawQuery("SELECT id FROM track WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1", null).use { c ->
      if (c.moveToFirst()) c.getLong(0) else null
    }

  fun lastSegment(trackId: Long): Int =
    readableDatabase.rawQuery("SELECT coalesce(max(segment), 0) FROM point WHERE track_id = ?", arrayOf(trackId.toString())).use { c ->
      c.moveToFirst()
      c.getInt(0)
    }

  fun addPoint(trackId: Long, segment: Int, p: TrackPoint) {
    writableDatabase.insertOrThrow("point", null, ContentValues().apply {
      put("track_id", trackId)
      put("segment", segment)
      put("time", p.timeMs)
      put("lat", p.lat)
      put("lon", p.lon)
      put("ele", p.ele)
    })
  }

  /** Points in the order they were recorded or imported (imported times can be missing or out of order), corrected from [datum] (the track's own 坐标纠偏 unless given). */
  fun segments(trackId: Long, datum: Datum = datum(trackId)): List<List<TrackPoint>> {
    return readableDatabase.rawQuery("SELECT segment, time, lat, lon, ele FROM point WHERE track_id = ? ORDER BY segment, rowid", arrayOf(trackId.toString())).use { c ->
      buildMap<Int, MutableList<TrackPoint>> {
        while (c.moveToNext()) {
          val (lat, lon) = datum.toWgs84(c.getDouble(2), c.getDouble(3))
          getOrPut(c.getInt(0)) { mutableListOf() } += TrackPoint(c.getLong(1), lat, lon, if (c.isNull(4)) null else c.getDouble(4))
        }
      }.values.toList()
    }
  }

  fun datum(trackId: Long): Datum =
    readableDatabase.rawQuery("SELECT datum FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c ->
      if (c.moveToFirst()) Datum.valueOf(c.getString(0)) else Datum.WGS84
    }

  fun setDatum(trackId: Long, datum: Datum) {
    writableDatabase.execSQL("UPDATE track SET datum = ?, dirty = dirty | $SYNC_DATUM, edits = edits + 1 WHERE id = ?", arrayOf<Any?>(datum.name, trackId))
    changed()
  }

  fun isPublic(trackId: Long): Boolean =
    readableDatabase.rawQuery("SELECT public FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

  /** 公开 or 撤回 (§2.8); the server does it on the next push. */
  fun setPublic(trackId: Long, public: Boolean) {
    writableDatabase.execSQL("UPDATE track SET public = ?, dirty = dirty | $SYNC_PUBLIC, edits = edits + 1 WHERE id = ?", arrayOf<Any?>(public, trackId))
    changed()
  }

  /** Renames the track; blank goes back to the start time. */
  fun setName(trackId: Long, name: String) {
    writableDatabase.execSQL("UPDATE track SET name = ?, dirty = dirty | $SYNC_NAME, edits = edits + 1 WHERE id = ?", arrayOf<Any?>(name.trim().ifEmpty { null }, trackId))
    changed()
  }

  /**
   * Imports one track with its 标注 in a single transaction, so a failed import leaves nothing behind. [name] null
   * shows it by its start time, as a recording; [uuid] its sync id if given.
   */
  fun importTrack(track: ParsedTrack, name: String?, waypoints: List<Waypoint>, now: Long, uuid: String? = null, imported: Boolean = false): Long = writableDatabase.transaction {
    val times = track.segments.flatten().map { it.timeMs }.filter { it != 0L }
    val start = times.minOrNull() ?: now
    val id = insertOrThrow("track", null, ContentValues().apply {
      put("started_at", start)
      put("added_at", now)
      put("imported", imported)
      put("name", name)
      put("planned", track.planned)
      put("source", track.source)
      uuid?.let { put("uuid", it) }
    })
    track.segments.forEachIndexed { i, seg -> seg.forEach { addPoint(id, i, it) } }
    endTrack(id, times.maxOrNull() ?: start)
    for (w in waypoints) addWaypoint(id, w.timeMs, w.lat, w.lon, w.ele).also { updateWaypoint(it, w.name, w.description, w.photo) }
    id
  }.also { changed() }

  /**
   * 截取 (#88): points [range] of [trackId] (as [trimSegments]) as stored, into a new private track with its 坐标纠偏, plan
   * and the 标注 [trimWaypoints] picks, each with its own copy of the photo. The original stays as it was; the new id.
   */
  fun trimTrack(trackId: Long, range: IntRange, name: String, source: String, now: Long): Long = writableDatabase.transaction {
    val piece = trimSegments(segments(trackId, Datum.WGS84), range)
    // As stored too, to go by the same points.
    val copies = withOwnPhotos(trimWaypoints(rawWaypoints(trackId), piece))
    val id = importTrack(ParsedTrack(name, planned(trackId), piece, source), name, copies, now, imported = imported(trackId))
    execSQL("UPDATE track SET datum = ? WHERE id = ?", arrayOf<Any?>(datum(trackId).name, id))
    id
  }

  /**
   * 合并 (#189): [ids] one after another ([mergeSegments]) into a new private track, a plan if all were, with all their
   * 标注 (each with its own copy of the photo). One 坐标纠偏 among them is kept, the points as stored; mixed, all go to
   * WGS-84 first. The originals stay as they were; the new id.
   */
  fun mergeTracks(ids: List<Long>, name: String, source: String, now: Long): Long = writableDatabase.transaction {
    val datum = ids.map(::datum).distinct().singleOrNull()
    val planned = ids.all(::planned)
    val segments = mergeSegments(ids.map { if (datum != null) segments(it, Datum.WGS84) else segments(it) }, planned)
    val waypoints = withOwnPhotos(ids.flatMap { if (datum != null) rawWaypoints(it) else waypoints(it) })
    val id = importTrack(ParsedTrack(name, planned, segments, source), name, waypoints, now, imported = datum != null && ids.any(::imported))
    execSQL("UPDATE track SET datum = ? WHERE id = ?", arrayOf<Any?>((datum ?: Datum.WGS84).name, id))
    id
  }

  /** Tracks (not plans) whose times overlap, as the top-level timesOverlap: they can't be 合并ed. */
  fun mergeOverlaps(ids: List<Long>): Boolean = !ids.all(::planned) && timesOverlap(ids.map { segments(it, Datum.WGS84) })

  /** A track's 标注 as stored (no 纠偏). */
  private fun rawWaypoints(trackId: Long): List<Waypoint> =
    readableDatabase.rawQuery("SELECT time, lat, lon, ele, name, description, photo FROM waypoint WHERE track_id = ? AND NOT deleted AND trashed = 0", arrayOf(trackId.toString())).use { c ->
      buildList { while (c.moveToNext()) add(Waypoint(0, null, c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3), c.getString(4), c.getString(5), c.getString(6))) }
    }

  /** Each with a copy of its photo, so deleting one's leaves the other's. */
  private fun withOwnPhotos(waypoints: List<Waypoint>) = waypoints.map { w ->
    w.copy(photo = w.photo?.let(::File)?.takeIf { it.isFile }?.let { it.copyTo(File(it.parentFile, "copy-${System.nanoTime()}-${it.name}")).path })
  }

  /** 我的轨迹: finished tracks, the last to come onto this phone first (ux-v3 §8.5 第 2 条). */
  fun tracks(): List<TrackSummary> =
    readableDatabase.rawQuery("SELECT id, started_at, name, planned, public FROM track WHERE ended_at IS NOT NULL AND NOT deleted AND trashed = 0 ORDER BY added_at DESC, id DESC", null).use { c ->
      buildList { while (c.moveToNext()) add(TrackSummary(c.getLong(0), c.getString(2) ?: startName(c.getLong(1)), c.getInt(3) != 0, c.getLong(1), c.getInt(4) != 0)) }
    }

  /** Imported name, or a recording's start time. */
  fun trackName(trackId: Long): String =
    readableDatabase.rawQuery("SELECT started_at, name FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c ->
      c.moveToFirst()
      c.getString(1) ?: startName(c.getLong(0))
    }

  /** Its id on the server and between phones (a 队伍轨迹 names the 发起人's track by it). */
  fun uuid(trackId: Long): String =
    readableDatabase.rawQuery("SELECT uuid FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> c.moveToFirst(); c.getString(0) }

  /** The track with sync id [uuid], if here. */
  fun idOf(uuid: String): Long? =
    readableDatabase.rawQuery("SELECT id FROM track WHERE uuid = ?", arrayOf(uuid)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

  fun source(trackId: Long): String? =
    readableDatabase.rawQuery("SELECT source FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

  /** From a file, or shifted by a 坐标来源 picked on another phone: it has a 坐标来源 to pick. */
  fun imported(trackId: Long): Boolean =
    readableDatabase.rawQuery("SELECT imported OR datum <> 'WGS84' FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

  fun planned(trackId: Long): Boolean =
    readableDatabase.rawQuery("SELECT planned FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

  fun addWaypoint(trackId: Long?, timeMs: Long, lat: Double, lon: Double, ele: Double?): Long =
    writableDatabase.insertOrThrow("waypoint", null, ContentValues().apply {
      put("track_id", trackId)
      put("time", timeMs)
      put("lat", lat)
      put("lon", lon)
      put("ele", ele)
    }).also { changed() }

  /** A 标注's name and description as typed (C5-22: saved as they change), its photo left as it is. */
  fun setWaypointText(id: Long, name: String, description: String) =
    updateWaypoint(id, name, description, readableDatabase.rawQuery("SELECT photo FROM waypoint WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null })

  fun updateWaypoint(id: Long, name: String, description: String, photo: String?) {
    // Only what differs is marked dirty; the right-hand sides all see the old row.
    writableDatabase.execSQL(
      "UPDATE waypoint SET dirty = dirty | (CASE WHEN name IS NOT ?1 THEN $SYNC_NAME ELSE 0 END) | " +
        "(CASE WHEN description IS NOT ?2 THEN $SYNC_DESCRIPTION ELSE 0 END) | (CASE WHEN photo IS NOT ?3 THEN $SYNC_PHOTO ELSE 0 END), " +
        "photo_id = CASE WHEN photo IS NOT ?3 THEN NULL ELSE photo_id END, edits = edits + 1, name = ?1, description = ?2, photo = ?3 WHERE id = ?4",
      arrayOf<Any?>(name, description, photo, id),
    )
    changed()
  }

  /** Gone at once (its photo too) if the server never had it, else kept as a 删除标记 until the next push. */
  fun deleteWaypoint(id: Long) = writableDatabase.transaction {
    rawQuery("SELECT photo FROM waypoint WHERE id = ? AND photo IS NOT NULL", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) File(c.getString(0)).delete() }
    execSQL("UPDATE waypoint SET deleted = 1, photo = NULL, edits = edits + 1 WHERE id = ? AND synced = 1", arrayOf(id))
    delete("waypoint", "id = ? AND synced = 0", arrayOf(id.toString()))
    changed()
  }

  /** Whether the server has track [id], so deleting it deletes it on other phones too. */
  fun synced(id: Long): Boolean =
    readableDatabase.rawQuery("SELECT synced FROM track WHERE id = ?", arrayOf(id.toString())).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

  /**
   * Its 标注 go with it; all goes as [deleteWaypoint] does. They let go of the track, so a 删除标记 still pushes
   * once the track's is purged.
   */
  fun deleteTrack(id: Long) = writableDatabase.transaction {
    rawQuery("SELECT photo FROM waypoint WHERE track_id = ? AND photo IS NOT NULL", arrayOf(id.toString())).use { c -> while (c.moveToNext()) File(c.getString(0)).delete() }
    execSQL("UPDATE waypoint SET deleted = 1, photo = NULL, edits = edits + 1, track_id = NULL WHERE track_id = ? AND synced = 1", arrayOf(id))
    delete("waypoint", "track_id = ? AND synced = 0", arrayOf(id.toString()))
    delete("point", "track_id = ?", arrayOf(id.toString()))
    execSQL("UPDATE track SET deleted = 1, edits = edits + 1 WHERE id = ? AND synced = 1", arrayOf(id))
    delete("track", "id = ? AND synced = 0", arrayOf(id.toString()))
    changed()
  }

  // 标注组 (#121).

  fun groups(): List<WaypointGroup> =
    readableDatabase.rawQuery(
      "SELECT g.id, g.name, g.shown, (SELECT count(*) FROM waypoint w WHERE w.group_id = g.id AND NOT w.deleted AND w.trashed = 0) FROM waypoint_group g WHERE NOT g.deleted AND g.trashed = 0 ORDER BY g.name", null,
    ).use { c -> buildList { while (c.moveToNext()) add(WaypointGroup(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3))) } }

  /** Names a new group can't take: every live group's, those whose 撤销 is still on offer included. */
  fun groupNames(): Set<String> =
    readableDatabase.rawQuery("SELECT name FROM waypoint_group WHERE NOT deleted", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

  /** A new empty group; null if [name] is taken. */
  fun addGroup(name: String): Long? =
    writableDatabase.insertWithOnConflict("waypoint_group", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
      .takeIf { it != -1L }?.also { changed() }

  /** A file of only 标注: a group named after it (numbered if taken) holding them all; its id. */
  fun importGroup(name: String, waypoints: List<Waypoint>): Long = writableDatabase.transaction {
    val id = insertOrThrow("waypoint_group", null, ContentValues().apply { put("name", uniqueName(name, groupNames())) })
    for (w in waypoints) addWaypoint(null, w.timeMs, w.lat, w.lon, w.ele).also {
      updateWaypoint(it, w.name, w.description, w.photo)
      execSQL("UPDATE waypoint SET group_id = ? WHERE id = ?", arrayOf(id, it))
    }
    id
  }.also { changed() }

  /** False if another group has [name]. */
  fun renameGroup(id: Long, name: String): Boolean = runCatching {
    writableDatabase.execSQL("UPDATE waypoint_group SET name = ?, dirty = dirty | $SYNC_NAME, edits = edits + 1 WHERE id = ?", arrayOf<Any?>(name, id))
  }.onSuccess { changed() }.isSuccess

  fun setGroupShown(id: Long, shown: Boolean) =
    writableDatabase.execSQL("UPDATE waypoint_group SET shown = ? WHERE id = ?", arrayOf<Any?>(shown, id))

  /** 叠加 of a 标注 outside groups. */
  fun setWaypointShown(id: Long, shown: Boolean) =
    writableDatabase.execSQL("UPDATE waypoint SET shown = ? WHERE id = ?", arrayOf<Any?>(shown, id))

  /** Moves a 标注 not on a track into [groupId] (null: out of groups); where it goes, it is shown (a hidden group too). */
  fun setWaypointGroup(id: Long, groupId: Long?) = writableDatabase.transaction {
    execSQL("UPDATE waypoint SET group_id = ?1, shown = 1, dirty = dirty | $SYNC_GROUP, edits = edits + 1 WHERE id = ?2 AND track_id IS NULL AND group_id IS NOT ?1", arrayOf<Any?>(groupId, id))
    groupId?.let { execSQL("UPDATE waypoint_group SET shown = 1 WHERE id = ?", arrayOf(it)) }
    changed()
  }

  /** Its 标注 and their photos go with it; all as [deleteWaypoint] does. */
  fun deleteGroup(id: Long) = writableDatabase.transaction {
    dropGroupWaypoints(id)
    execSQL("UPDATE waypoint_group SET deleted = 1, edits = edits + 1 WHERE id = ? AND synced = 1", arrayOf(id))
    delete("waypoint_group", "id = ? AND synced = 0", arrayOf(id.toString()))
    changed()
  }

  private fun SQLiteDatabase.dropGroupWaypoints(id: Long) {
    rawQuery("SELECT photo FROM waypoint WHERE group_id = ? AND photo IS NOT NULL", arrayOf(id.toString())).use { c -> while (c.moveToNext()) File(c.getString(0)).delete() }
    execSQL("UPDATE waypoint SET deleted = 1, photo = NULL, edits = edits + 1, group_id = NULL WHERE group_id = ? AND synced = 1", arrayOf(id))
    delete("waypoint", "group_id = ? AND synced = 0", arrayOf(id.toString()))
  }

  // 同步 (§2.12, Sync.kt): what to push, and what a pull brings.

  fun pendingGroups(): List<PendingGroup> =
    readableDatabase.rawQuery("SELECT id, uuid, synced, dirty, edits, name, deleted FROM waypoint_group WHERE trashed = 0 AND (NOT synced OR dirty <> 0 OR deleted)", null).use { c ->
      buildList { while (c.moveToNext()) add(PendingGroup(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getString(5), c.getInt(6) != 0)) }
    }

  /** The server has heard of the group's 删除标记. */
  fun purgeGroup(id: Long) {
    writableDatabase.delete("waypoint_group", "id = ? AND deleted", arrayOf(id.toString()))
  }

  /**
   * Takes in a pulled 标注组 as [applyTrack] does. New here it starts without 叠加. Deleted there, its 标注 go too.
   * A name another group here has: of the two, the one with the larger uuid gets numbered, and that goes back up.
   * Every phone picks the same one, so two phones naming groups alike settle instead of renaming each other's.
   */
  fun applyGroup(g: SyncGroup): Boolean = writableDatabase.transaction {
    data class Local(val id: Long, val dirty: Int, val name: String, val deleted: Boolean)
    val local = rawQuery("SELECT id, dirty, name, deleted FROM waypoint_group WHERE uuid = ?", arrayOf(g.uuid)).use { c ->
      if (c.moveToFirst()) Local(c.getLong(0), c.getInt(1), c.getString(2), c.getInt(3) != 0) else null
    }
    if (local?.deleted == true) return@transaction false // our 删除标记 goes up next
    if (g.deleted) {
      if (local == null) return@transaction false
      dropGroupWaypoints(local.id)
      delete("waypoint_group", "id = ?", arrayOf(local.id.toString()))
      return@transaction true
    }
    if (local != null && (local.dirty and SYNC_NAME != 0 || local.name == g.name)) return@transaction false
    val clash = rawQuery("SELECT id, uuid FROM waypoint_group WHERE name = ? AND uuid <> ? AND NOT deleted", arrayOf(g.name, g.uuid)).use { c ->
      if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
    }
    val numbered = uniqueName(g.name, groupNames() - setOfNotNull(local?.name))
    val renamed = if (clash != null && clash.second < g.uuid) numbered else g.name
    if (clash != null && renamed == g.name) execSQL(
      "UPDATE waypoint_group SET name = ?, dirty = dirty | $SYNC_NAME, edits = edits + 1 WHERE id = ?", arrayOf<Any?>(numbered, clash.first),
    )
    val id = local?.id ?: insertOrThrow("waypoint_group", null, ContentValues().apply {
      put("uuid", g.uuid)
      put("name", "")
      put("shown", 0)
      put("synced", 1)
      put("dirty", 0)
    })
    execSQL(
      "UPDATE waypoint_group SET name = ?, dirty = dirty | ?, edits = edits + ? WHERE id = ?",
      arrayOf<Any?>(renamed, if (renamed != g.name) SYNC_NAME else 0, if (renamed != g.name) 1 else 0, id),
    )
    if (clash != null) changed()
    true
  }

  /** Ended tracks the server hasn't seen, with changed attributes, or deleted; none whose 撤销 is still on offer. */
  fun pendingTracks(): List<PendingTrack> =
    readableDatabase.rawQuery(
      "SELECT id, uuid, synced, dirty, edits, started_at, ended_at, planned, name, datum, public, deleted, source FROM track WHERE ended_at IS NOT NULL AND trashed = 0 AND (NOT synced OR dirty <> 0 OR deleted)", null,
    ).use { c ->
      buildList {
        while (c.moveToNext()) add(PendingTrack(
          c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getLong(5), c.getLong(6), c.getInt(7) != 0,
          if (c.isNull(8)) null else c.getString(8), c.getString(9), c.getInt(10) != 0, c.getInt(11) != 0, if (c.isNull(12)) null else c.getString(12),
        ))
      }
    }

  /** Points as stored (no 纠偏), in order. */
  fun rawPoints(trackId: Long): List<SyncPoint> =
    readableDatabase.rawQuery("SELECT segment, time, lat, lon, ele FROM point WHERE track_id = ? ORDER BY segment, rowid", arrayOf(trackId.toString())).use { c ->
      buildList { while (c.moveToNext()) add(SyncPoint(c.getInt(0), TrackPoint(c.getLong(1), c.getDouble(2), c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4)))) }
    }

  /** 标注 to push; those on a track wait until the server has the track. */
  fun pendingWaypoints(): List<PendingWaypoint> =
    readableDatabase.rawQuery(
      "SELECT w.id, w.uuid, w.synced, w.dirty, w.edits, w.deleted, t.uuid, w.time, w.lat, w.lon, w.ele, w.name, w.description, w.photo, w.photo_id, g.uuid " +
        "FROM waypoint w LEFT JOIN track t ON t.id = w.track_id LEFT JOIN waypoint_group g ON g.id = w.group_id WHERE w.trashed = 0 AND (NOT w.synced OR w.dirty <> 0 OR w.deleted) AND (w.track_id IS NULL OR t.synced)", null,
    ).use { c ->
      fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
      buildList {
        while (c.moveToNext()) add(PendingWaypoint(
          c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getInt(5) != 0, str(6), str(15), c.getLong(7), c.getDouble(8), c.getDouble(9),
          if (c.isNull(10)) null else c.getDouble(10), c.getString(11), c.getString(12), str(13), str(14),
        ))
      }
    }

  /** The server has row [id] of [table] with the attributes in [bits]; changes made since ([edits] moved on) stay dirty. */
  fun pushed(table: String, id: Long, bits: Int, edits: Int) {
    writableDatabase.execSQL("UPDATE $table SET synced = 1, dirty = CASE WHEN edits = ? THEN dirty & ~? ELSE dirty END WHERE id = ?", arrayOf<Any?>(edits, bits, id))
  }

  /** [photoId] is the server's for the 标注's photo, if that is still [photo]. */
  fun setPhotoId(id: Long, photo: String, photoId: String) {
    writableDatabase.execSQL("UPDATE waypoint SET photo_id = ? WHERE id = ? AND photo = ?", arrayOf<Any?>(photoId, id, photo))
  }

  /** The server has heard of the 删除标记. */
  fun purgeWaypoint(id: Long) {
    writableDatabase.delete("waypoint", "id = ? AND deleted", arrayOf(id.toString()))
  }

  /** The server has heard of the track's 删除标记. */
  fun purgeTrack(id: Long) {
    writableDatabase.delete("track", "id = ? AND deleted", arrayOf(id.toString()))
  }

  /**
   * Another account (or none) from now on: everything is new to the server, and pending deletions are moot.
   * 公开轨迹 go private: the next account publishes only what its owner chooses to.
   */
  fun resetSync() = writableDatabase.transaction {
    for (t in listOf("track", "waypoint", "waypoint_group")) execSQL("DELETE FROM $t WHERE deleted")
    execSQL("UPDATE track SET public = 0")
    for (t in listOf("track", "waypoint", "waypoint_group")) execSQL("UPDATE $t SET synced = 0, dirty = $SYNC_ALL")
    execSQL("UPDATE waypoint SET photo_id = NULL")
  }

  /** Takes in a pulled track, keeping attributes changed here and not pushed yet; whether anything changed. */
  fun applyTrack(t: SyncTrack): Boolean = writableDatabase.transaction {
    data class Local(val id: Long, val dirty: Int, val name: String?, val datum: String, val public: Boolean, val deleted: Boolean)
    val local = rawQuery("SELECT id, dirty, name, datum, public, deleted FROM track WHERE uuid = ?", arrayOf(t.uuid)).use { c ->
      if (c.moveToFirst()) Local(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getString(2), c.getString(3), c.getInt(4) != 0, c.getInt(5) != 0) else null
    }
    if (local?.deleted == true) return@transaction false // our 删除标记 goes up next
    if (local == null) {
      if (t.deleted) return@transaction false
      // Points go in before ended_at: after it they are immutable.
      val id = insertOrThrow("track", null, ContentValues().apply {
        put("uuid", t.uuid)
        put("started_at", t.startedAt)
        put("added_at", System.currentTimeMillis())
        put("planned", t.planned)
        put("name", t.name)
        put("datum", t.datum.name)
        put("public", t.public)
        put("source", t.source)
        put("synced", 1)
        put("dirty", 0)
      })
      for ((s, p) in t.points) addPoint(id, s, p)
      update("track", ContentValues().apply { put("ended_at", t.endedAt) }, "id = ?", arrayOf(id.toString()))
      return@transaction true
    }
    if (t.deleted) {
      execSQL("UPDATE waypoint SET track_id = NULL WHERE track_id = ?", arrayOf(local.id))
      delete("point", "track_id = ?", arrayOf(local.id.toString()))
      delete("track", "id = ?", arrayOf(local.id.toString()))
      return@transaction true
    }
    val name = if (local.dirty and SYNC_NAME == 0) t.name else local.name
    val datum = if (local.dirty and SYNC_DATUM == 0) t.datum.name else local.datum
    val public = if (local.dirty and SYNC_PUBLIC == 0) t.public else local.public
    if (name == local.name && datum == local.datum && public == local.public) return@transaction false
    update("track", ContentValues().apply { put("name", name); put("datum", datum); put("public", public) }, "id = ?", arrayOf(local.id.toString()))
    true
  }

  /**
   * Takes in a pulled 标注 as [applyTrack] does. A photo new to this phone is fetched by [download] (its
   * server id → the local file); one replaced or removed is deleted here too.
   */
  fun applyWaypoint(w: SyncWaypoint, download: (String) -> String): Boolean {
    data class Local(val id: Long, val dirty: Int, val deleted: Boolean, val name: String, val description: String, val photo: String?, val photoId: String?, val groupId: Long?)
    val local = readableDatabase.rawQuery("SELECT id, dirty, deleted, name, description, photo, photo_id, group_id FROM waypoint WHERE uuid = ?", arrayOf(w.uuid)).use { c ->
      if (c.moveToFirst()) Local(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getLong(7)) else null
    }
    if (local?.deleted == true) return false // our 删除标记 goes up next
    if (w.deleted) {
      if (local == null) return false
      writableDatabase.delete("waypoint", "id = ?", arrayOf(local.id.toString()))
      local.photo?.let { File(it).delete() }
      return true
    }
    // A photo kept only here ('') matches the server's none.
    val photoKept = local != null && (local.dirty and SYNC_PHOTO != 0 || local.photoId.orEmpty() == w.photo.orEmpty())
    val photo = if (photoKept) local.photo else w.photo?.let(download)
    val photoId = if (photoKept) local.photoId else w.photo
    val trackId = w.track?.let { uuid ->
      readableDatabase.rawQuery("SELECT id FROM track WHERE uuid = ? AND NOT deleted", arrayOf(uuid)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }
    val groupId = if (local != null && local.dirty and SYNC_GROUP != 0) local.groupId else w.group?.let { uuid ->
      readableDatabase.rawQuery("SELECT id FROM waypoint_group WHERE uuid = ? AND NOT deleted", arrayOf(uuid)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }
    val values = ContentValues().apply {
      put("group_id", groupId)
      put("name", if (local == null || local.dirty and SYNC_NAME == 0) w.name else local.name)
      put("description", if (local == null || local.dirty and SYNC_DESCRIPTION == 0) w.description else local.description)
      put("photo", photo)
      put("photo_id", photoId)
    }
    if (local == null) {
      writableDatabase.insertOrThrow("waypoint", null, values.apply {
        put("uuid", w.uuid)
        put("track_id", trackId)
        put("time", w.timeMs)
        put("lat", w.lat)
        put("lon", w.lon)
        put("ele", w.ele)
        put("synced", 1)
        put("dirty", 0)
        put("shown", 0)
      })
      return true
    }
    if (groupId == local.groupId && values.getAsString("name") == local.name && values.getAsString("description") == local.description && photo == local.photo) return false
    writableDatabase.update("waypoint", values, "id = ?", arrayOf(local.id.toString()))
    if (photo != local.photo) local.photo?.let { File(it).delete() }
    return true
  }

  /** All 标注, or only those of [trackId]; a track's 坐标纠偏 applies to its 标注 too. */
  fun waypoints(trackId: Long? = null): List<Waypoint> =
    readableDatabase.rawQuery(
      "SELECT w.id, w.track_id, w.time, w.lat, w.lon, w.ele, w.name, w.description, w.photo, coalesce(t.datum, 'WGS84'), w.group_id, coalesce(g.shown, w.shown) " +
        "FROM waypoint w LEFT JOIN track t ON t.id = w.track_id LEFT JOIN waypoint_group g ON g.id = w.group_id " +
        "WHERE NOT w.deleted AND w.trashed = 0 AND coalesce(t.trashed, 0) = 0 AND coalesce(g.trashed, 0) = 0" + (if (trackId != null) " AND w.track_id = ?" else "") + " ORDER BY w.time, w.id",
      trackId?.let { arrayOf(it.toString()) },
    ).use { c ->
      buildList {
        while (c.moveToNext()) {
          val (lat, lon) = Datum.valueOf(c.getString(9)).toWgs84(c.getDouble(3), c.getDouble(4))
          add(Waypoint(
            c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getLong(2), lat, lon,
            if (c.isNull(5)) null else c.getDouble(5), c.getString(6), c.getString(7), c.getString(8),
            if (c.isNull(10)) null else c.getLong(10), c.getInt(11) != 0,
          ))
        }
      }
    }
}

private fun startName(startedAt: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(startedAt))
