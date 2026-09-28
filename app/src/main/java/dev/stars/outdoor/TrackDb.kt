package dev.stars.outdoor

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

data class TrackSummary(val id: Long, val name: String, val planned: Boolean)

/** 标注. [trackId] is set when it was added while recording (or imported with a track); [photo] is a file path. */
data class Waypoint(
  val id: Long, val trackId: Long?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?,
)

class TrackDb(private val context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 6) {
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
  }

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

  fun startTrack(now: Long): Long = writableDatabase.insertOrThrow("track", null, ContentValues().apply { put("started_at", now) })

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

  /** Points in the order they were recorded or imported (imported times can be missing or out of order), 纠偏 applied. */
  fun segments(trackId: Long): List<List<TrackPoint>> {
    val datum = datum(trackId)
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

  /** Imports one track with its 标注 in a single transaction, so a failed import leaves nothing behind. */
  fun importTrack(track: ParsedTrack, name: String, waypoints: List<Waypoint>, now: Long): Long = writableDatabase.transaction {
    val times = track.segments.flatten().map { it.timeMs }.filter { it != 0L }
    val start = times.minOrNull() ?: now
    val id = insertOrThrow("track", null, ContentValues().apply {
      put("started_at", start)
      put("name", name)
      put("planned", track.planned)
    })
    track.segments.forEachIndexed { i, seg -> seg.forEach { addPoint(id, i, it) } }
    endTrack(id, times.maxOrNull() ?: start)
    for (w in waypoints) addWaypoint(id, w.timeMs, w.lat, w.lon, w.ele).also { updateWaypoint(it, w.name, w.description, w.photo) }
    id
  }.also { changed() }

  /** 我的轨迹: finished tracks, newest first. */
  fun tracks(): List<TrackSummary> =
    readableDatabase.rawQuery("SELECT id, started_at, name, planned FROM track WHERE ended_at IS NOT NULL ORDER BY started_at DESC", null).use { c ->
      buildList { while (c.moveToNext()) add(TrackSummary(c.getLong(0), c.getString(2) ?: startName(c.getLong(1)), c.getInt(3) != 0)) }
    }

  /** Imported name, or a recording's start time. */
  fun trackName(trackId: Long): String =
    readableDatabase.rawQuery("SELECT started_at, name FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c ->
      c.moveToFirst()
      c.getString(1) ?: startName(c.getLong(0))
    }

  fun planned(trackId: Long): Boolean =
    readableDatabase.rawQuery("SELECT planned FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

  /** Latest finished track that has at least one point. */
  fun lastEndedTrack(): Long? =
    readableDatabase.rawQuery(
      "SELECT id FROM track t WHERE ended_at IS NOT NULL AND EXISTS (SELECT 1 FROM point WHERE track_id = t.id) ORDER BY id DESC LIMIT 1",
      null,
    ).use { c ->
      if (c.moveToFirst()) c.getLong(0) else null
    }

  fun addWaypoint(trackId: Long?, timeMs: Long, lat: Double, lon: Double, ele: Double?): Long =
    writableDatabase.insertOrThrow("waypoint", null, ContentValues().apply {
      put("track_id", trackId)
      put("time", timeMs)
      put("lat", lat)
      put("lon", lon)
      put("ele", ele)
    }).also { changed() }

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

  /** Gone at once if the server never had it, else kept as a 删除标记 until the next push. */
  fun deleteWaypoint(id: Long) = writableDatabase.transaction {
    execSQL("UPDATE waypoint SET deleted = 1, photo = NULL, edits = edits + 1 WHERE id = ? AND synced = 1", arrayOf(id))
    delete("waypoint", "id = ? AND synced = 0", arrayOf(id.toString()))
    changed()
  }

  // 同步 (§2.12, Sync.kt): what to push, and what a pull brings.

  /** Ended tracks the server hasn't seen, or with changed attributes. */
  fun pendingTracks(): List<PendingTrack> =
    readableDatabase.rawQuery(
      "SELECT id, uuid, synced, dirty, edits, started_at, ended_at, planned, name, datum, public FROM track WHERE ended_at IS NOT NULL AND (NOT synced OR dirty <> 0)", null,
    ).use { c ->
      buildList {
        while (c.moveToNext()) add(PendingTrack(
          c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getLong(5), c.getLong(6), c.getInt(7) != 0,
          if (c.isNull(8)) null else c.getString(8), c.getString(9), c.getInt(10) != 0,
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
      "SELECT w.id, w.uuid, w.synced, w.dirty, w.edits, w.deleted, t.uuid, w.time, w.lat, w.lon, w.ele, w.name, w.description, w.photo, w.photo_id " +
        "FROM waypoint w LEFT JOIN track t ON t.id = w.track_id WHERE (NOT w.synced OR w.dirty <> 0 OR w.deleted) AND (w.track_id IS NULL OR t.synced)", null,
    ).use { c ->
      fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
      buildList {
        while (c.moveToNext()) add(PendingWaypoint(
          c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getInt(5) != 0, str(6), c.getLong(7), c.getDouble(8), c.getDouble(9),
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

  /**
   * Another account (or none) from now on: everything is new to the server, and pending deletions are moot.
   * 公开轨迹 go private: the next account publishes only what its owner chooses to.
   */
  fun resetSync() = writableDatabase.transaction {
    execSQL("DELETE FROM waypoint WHERE deleted")
    execSQL("UPDATE track SET public = 0")
    for (t in listOf("track", "waypoint")) execSQL("UPDATE $t SET synced = 0, dirty = $SYNC_ALL")
    execSQL("UPDATE waypoint SET photo_id = NULL")
  }

  /** Takes in a pulled track, keeping attributes changed here and not pushed yet; whether anything changed. */
  fun applyTrack(t: SyncTrack): Boolean = writableDatabase.transaction {
    data class Local(val id: Long, val dirty: Int, val name: String?, val datum: String, val public: Boolean)
    val local = rawQuery("SELECT id, dirty, name, datum, public FROM track WHERE uuid = ?", arrayOf(t.uuid)).use { c ->
      if (c.moveToFirst()) Local(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getString(2), c.getString(3), c.getInt(4) != 0) else null
    }
    if (local == null) {
      if (t.deleted) return@transaction false
      // Points go in before ended_at: after it they are immutable.
      val id = insertOrThrow("track", null, ContentValues().apply {
        put("uuid", t.uuid)
        put("started_at", t.startedAt)
        put("planned", t.planned)
        put("name", t.name)
        put("datum", t.datum.name)
        put("public", t.public)
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
    data class Local(val id: Long, val dirty: Int, val deleted: Boolean, val name: String, val description: String, val photo: String?, val photoId: String?)
    val local = readableDatabase.rawQuery("SELECT id, dirty, deleted, name, description, photo, photo_id FROM waypoint WHERE uuid = ?", arrayOf(w.uuid)).use { c ->
      if (c.moveToFirst()) Local(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getString(3), c.getString(4), c.getString(5), c.getString(6)) else null
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
    val photo = if (photoKept) local!!.photo else w.photo?.let(download)
    val photoId = if (photoKept) local!!.photoId else w.photo
    val trackId = w.track?.let { uuid ->
      readableDatabase.rawQuery("SELECT id FROM track WHERE uuid = ?", arrayOf(uuid)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }
    val values = ContentValues().apply {
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
      })
      return true
    }
    if (values.getAsString("name") == local.name && values.getAsString("description") == local.description && photo == local.photo) return false
    writableDatabase.update("waypoint", values, "id = ?", arrayOf(local.id.toString()))
    if (photo != local.photo) local.photo?.let { File(it).delete() }
    return true
  }

  /** All 标注, or only those of [trackId]; a track's 坐标纠偏 applies to its 标注 too. */
  fun waypoints(trackId: Long? = null): List<Waypoint> =
    readableDatabase.rawQuery(
      "SELECT w.id, w.track_id, w.time, w.lat, w.lon, w.ele, w.name, w.description, w.photo, coalesce(t.datum, 'WGS84') " +
        "FROM waypoint w LEFT JOIN track t ON t.id = w.track_id WHERE NOT w.deleted" + (if (trackId != null) " AND w.track_id = ?" else "") + " ORDER BY w.time, w.id",
      trackId?.let { arrayOf(it.toString()) },
    ).use { c ->
      buildList {
        while (c.moveToNext()) {
          val (lat, lon) = Datum.valueOf(c.getString(9)).toWgs84(c.getDouble(3), c.getDouble(4))
          add(Waypoint(
            c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getLong(2), lat, lon,
            if (c.isNull(5)) null else c.getDouble(5), c.getString(6), c.getString(7), c.getString(8),
          ))
        }
      }
    }
}

private fun startName(startedAt: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(startedAt))
