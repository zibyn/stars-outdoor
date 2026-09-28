package dev.stars.outdoor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?)

class TrackDb(context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 2) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE track (id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER)")
    db.execSQL(
      "CREATE TABLE point (track_id INTEGER NOT NULL REFERENCES track(id), time INTEGER NOT NULL, " +
        "lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, segment INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("CREATE INDEX point_track ON point(track_id, time)")
    immutablePoints(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE point ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
      immutablePoints(db)
    }
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

  fun segments(trackId: Long): List<List<TrackPoint>> =
    readableDatabase.rawQuery("SELECT segment, time, lat, lon, ele FROM point WHERE track_id = ? ORDER BY segment, time", arrayOf(trackId.toString())).use { c ->
      buildMap<Int, MutableList<TrackPoint>> {
        while (c.moveToNext()) {
          getOrPut(c.getInt(0)) { mutableListOf() } += TrackPoint(c.getLong(1), c.getDouble(2), c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4))
        }
      }.values.toList()
    }

  /** Latest finished track that has at least one point. */
  fun lastEndedTrack(): Long? =
    readableDatabase.rawQuery(
      "SELECT id FROM track t WHERE ended_at IS NOT NULL AND EXISTS (SELECT 1 FROM point WHERE track_id = t.id) ORDER BY id DESC LIMIT 1",
      null,
    ).use { c ->
      if (c.moveToFirst()) c.getLong(0) else null
    }

  fun startedAt(trackId: Long): Long =
    readableDatabase.rawQuery("SELECT started_at FROM track WHERE id = ?", arrayOf(trackId.toString())).use { c ->
      c.moveToFirst()
      c.getLong(0)
    }
}
