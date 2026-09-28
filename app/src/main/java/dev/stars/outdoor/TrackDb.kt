package dev.stars.outdoor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
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

class TrackDb(context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 4) {
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
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE point ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
      immutablePoints(db)
    }
    if (oldVersion < 3) createWaypoints(db)
    if (oldVersion < 4) trackAttributes(db)
  }

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
  }

  /** Ends [id] at its last point (for a recording whose process was killed). */
  fun endAtLastPoint(id: Long) {
    writableDatabase.execSQL(
      "UPDATE track SET ended_at = coalesce((SELECT max(time) FROM point WHERE track_id = id), started_at) WHERE id = ?",
      arrayOf(id),
    )
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
    writableDatabase.update("track", ContentValues().apply { put("datum", datum.name) }, "id = ?", arrayOf(trackId.toString()))
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
  }

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
    })

  fun updateWaypoint(id: Long, name: String, description: String, photo: String?) {
    writableDatabase.update("waypoint", ContentValues().apply {
      put("name", name)
      put("description", description)
      put("photo", photo)
    }, "id = ?", arrayOf(id.toString()))
  }

  fun deleteWaypoint(id: Long) {
    writableDatabase.delete("waypoint", "id = ?", arrayOf(id.toString()))
  }

  /** All 标注, or only those of [trackId]; a track's 坐标纠偏 applies to its 标注 too. */
  fun waypoints(trackId: Long? = null): List<Waypoint> =
    readableDatabase.rawQuery(
      "SELECT w.id, w.track_id, w.time, w.lat, w.lon, w.ele, w.name, w.description, w.photo, coalesce(t.datum, 'WGS84') " +
        "FROM waypoint w LEFT JOIN track t ON t.id = w.track_id" + (if (trackId != null) " WHERE w.track_id = ?" else "") + " ORDER BY w.time, w.id",
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
