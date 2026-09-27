package dev.stars.outdoor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?)

class TrackDb(context: Context) : SQLiteOpenHelper(context, "tracks.db", null, 1) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE track (id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER)")
    db.execSQL(
      "CREATE TABLE point (track_id INTEGER NOT NULL REFERENCES track(id), time INTEGER NOT NULL, " +
        "lat REAL NOT NULL, lon REAL NOT NULL, ele REAL)"
    )
    db.execSQL("CREATE INDEX point_track ON point(track_id, time)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  fun startTrack(now: Long): Long = writableDatabase.insertOrThrow("track", null, ContentValues().apply { put("started_at", now) })

  fun endTrack(id: Long, now: Long) {
    writableDatabase.update("track", ContentValues().apply { put("ended_at", now) }, "id = ?", arrayOf(id.toString()))
  }

  fun addPoint(trackId: Long, p: TrackPoint) {
    writableDatabase.insertOrThrow("point", null, ContentValues().apply {
      put("track_id", trackId)
      put("time", p.timeMs)
      put("lat", p.lat)
      put("lon", p.lon)
      put("ele", p.ele)
    })
  }

  fun points(trackId: Long): List<TrackPoint> =
    readableDatabase.rawQuery("SELECT time, lat, lon, ele FROM point WHERE track_id = ? ORDER BY time", arrayOf(trackId.toString())).use { c ->
      buildList {
        while (c.moveToNext()) add(TrackPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3)))
      }
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
