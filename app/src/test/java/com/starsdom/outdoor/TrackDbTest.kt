package com.starsdom.outdoor

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackDbTest {
  private val db = TrackDb(RuntimeEnvironment.getApplication())

  @After fun close() = db.close()

  private fun track(): Long =
    db.importTrack(ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null), TrackPoint(2000, 34.001, 108.0, null)))), "t", emptyList(), 0)

  private fun pushAll() {
    for (t in db.pendingTracks()) if (t.deleted) db.purgeTrack(t.id) else db.pushed("track", t.id, SYNC_ALL, t.edits)
  }

  @Test fun unsyncedTrackGoesAtOnce() {
    val id = track()
    val w = db.addWaypoint(id, 1500, 34.0, 108.0, null)
    db.deleteTrack(id)
    assertEquals(emptyList<TrackSummary>(), db.tracks())
    assertEquals(emptyList<Any>(), db.rawPoints(id))
    assertEquals(emptyList<PendingTrack>(), db.pendingTracks())
    assertEquals(listOf(null), db.waypoints().filter { it.id == w }.map { it.trackId })
  }

  @Test fun syncedTrackLeavesAMarkerUntilPushed() {
    val id = track()
    pushAll()
    db.deleteTrack(id)
    assertEquals(emptyList<TrackSummary>(), db.tracks())
    assertEquals(emptyList<Any>(), db.rawPoints(id))
    val pending = db.pendingTracks().single()
    assertTrue(pending.deleted)
    assertEquals(Json.parseToJsonElement("""{"id":"${db.uuid(id)}","deleted":true}"""), trackChange(pending, null).first)
    pushAll()
    assertEquals(emptyList<PendingTrack>(), db.pendingTracks())
    assertEquals(null, db.idOf(pending.uuid))
  }

  @Test fun resetSyncDropsMarkers() {
    val id = track()
    pushAll()
    db.deleteTrack(id)
    db.resetSync()
    assertEquals(emptyList<PendingTrack>(), db.pendingTracks())
  }

  @Test fun pullDoesNotBringAMarkedTrackBack() {
    val id = track()
    pushAll()
    val uuid = db.uuid(id)
    db.deleteTrack(id)
    assertFalse(db.applyTrack(SyncTrack(uuid, 1000, 2000, false, emptyList(), "renamed elsewhere", Datum.WGS84, false, false)))
    assertEquals(emptyList<TrackSummary>(), db.tracks())
    assertTrue(db.pendingTracks().single().deleted)
  }
}

class DeleteConfirmTest {
  @Test fun saysWhatElseGoes() {
    assertEquals("再点一次删除", deleteConfirm(synced = false, public = false))
    assertEquals("再点一次删除，其他手机上也会删除", deleteConfirm(synced = true, public = false))
    assertEquals("再点一次删除，其他手机上也会删除，并从周边路网撤下", deleteConfirm(synced = true, public = true))
  }
}
