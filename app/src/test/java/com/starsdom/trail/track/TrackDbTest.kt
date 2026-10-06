package com.starsdom.trail.track

import com.starsdom.trail.Datum
import com.starsdom.trail.PendingGroup
import com.starsdom.trail.PendingTrack
import com.starsdom.trail.Place
import com.starsdom.trail.SYNC_ALL
import com.starsdom.trail.SYNC_NAME
import com.starsdom.trail.SyncGroup
import com.starsdom.trail.SyncTrack
import com.starsdom.trail.SyncWaypoint
import com.starsdom.trail.defaultWaypointName
import com.starsdom.trail.nearestPlace
import com.starsdom.trail.trackChange
import com.starsdom.trail.waypointLine
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
    assertEquals(emptyList<Waypoint>(), db.waypoints().filter { it.id == w })
  }

  @Test fun recordingWithoutPointsGoesButItsWaypointsStay() {
    val id = db.startTrack(1000)
    val w = db.addWaypoint(id, 1500, 34.0, 108.0, null)
    db.discardTrack(id)
    assertEquals(null, db.openTrack())
    assertEquals(listOf(null), db.waypoints().filter { it.id == w }.map { it.trackId })
  }

  @Test fun importedWaypointsGoWithTheirTrack() {
    val w = Waypoint(0, null, 1500, 34.0, 108.0, null, "垭口", "", null)
    val id = db.importTrack(ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null)))), "t", listOf(w, w), 0)
    assertEquals(listOf(id, id), db.waypoints().map { it.trackId })
    db.deleteTrack(id)
    assertEquals(emptyList<Waypoint>(), db.waypoints())
  }

  @Test fun syncedTracksWaypointsLeaveMarkersThatStillPush() {
    val id = track()
    val w = db.addWaypoint(id, 1500, 34.0, 108.0, null)
    pushAll()
    for (p in db.pendingWaypoints()) db.pushed("waypoint", p.id, SYNC_ALL, p.edits)
    db.deleteTrack(id)
    assertEquals(emptyList<Waypoint>(), db.waypoints())
    pushAll()
    assertEquals(listOf(w to true), db.pendingWaypoints().map { it.id to it.deleted })
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

  // #89: an import's source goes up with it and comes down on the other phone.
  @Test fun sourceSyncs() {
    val id = db.importTrack(ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null))), "来自 佳明 fēnix 7"), "t", emptyList(), 0)
    assertEquals("来自 佳明 fēnix 7", db.source(id))
    assertEquals("来自 佳明 fēnix 7", db.pendingTracks().single().source)
    db.applyTrack(SyncTrack("u1", 500, 600, false, emptyList(), "pulled", Datum.WGS84, false, false, "来自 高驰"))
    assertEquals("来自 高驰", db.source(db.idOf("u1")!!))
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


  // §8.5 第 15 条: deleted, a track is hidden with its 标注 until the 提示条 is gone; 撤销 brings it all back.
  @Test fun trashedTrackHidesThenComesBackWhole() {
    val id = track()
    val photo = java.io.File.createTempFile("photo", ".jpg")
    val w = db.addWaypoint(id, 1500, 34.0, 108.0, null).also { db.updateWaypoint(it, "垭口", "", photo.path) }
    db.trash(Trash.Track, id, 42)
    assertEquals(emptyList<TrackSummary>(), db.tracks())
    assertEquals(emptyList<Waypoint>(), db.waypoints())
    db.untrash(Trash.Track, id)
    assertEquals(listOf(id), db.tracks().map { it.id })
    assertEquals(listOf(w to photo.path), db.waypoints().map { it.id to it.photo })
    assertTrue(photo.exists())
  }

  // Only what was trashed then goes when its time is up; on launch, everything trashed goes (no half-deleted state).
  @Test fun purgeDeletesForGoodWithPhotos() {
    val a = track()
    val b = track()
    val photo = java.io.File.createTempFile("photo", ".jpg")
    db.addWaypoint(a, 1500, 34.0, 108.0, null).also { db.updateWaypoint(it, "", "", photo.path) }
    db.trash(Trash.Track, a, 1)
    db.trash(Trash.Track, b, 2)
    db.purgeTrashed(1)
    assertFalse(photo.exists())
    assertEquals(emptyList<Any>(), db.rawPoints(a))
    assertEquals(listOf(b), db.trashedTracks())
    db.purgeTrashed(null)
    assertEquals(emptyList<Long>(), db.trashedTracks())
    db.untrash(Trash.Track, b)
    assertEquals(emptyList<TrackSummary>(), db.tracks())
  }

  // §8.2 第 8 条: 坐标来源 only for a file's track, or one a pick on another phone shifted.
  @Test fun onlyImportedTracksHaveADatumToPick() {
    val line = ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null))))
    val file = db.importTrack(line, "f", emptyList(), 0, imported = true)
    val copy = db.importTrack(line, "c", emptyList(), 0)
    assertTrue(db.imported(file))
    assertFalse(db.imported(copy))
    db.setDatum(copy, Datum.GCJ02)
    assertTrue(db.imported(copy))
  }

}

// 标注组 (#121).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaypointGroupTest {
  private val db = TrackDb(RuntimeEnvironment.getApplication())

  @After fun close() = db.close()

  private val w = Waypoint(0, null, 0, 34.0, 108.0, null, "水源", "", null)
  private fun loose() = db.addWaypoint(null, 0, 34.0, 108.0, null)
  private fun shown() = db.waypoints().filter { it.shown }.map { it.id }.toSet()

  @Test fun aTrashedGroupHidesItsWaypointsAndATrashedWaypointLeavesItsCount() {
    val g = db.importGroup("X", listOf(w, w))
    val loose = loose()
    db.trash(Trash.Waypoint, db.waypoints().first { it.groupId == g }.id, 1)
    assertEquals(listOf(1), db.groups().map { it.count })
    db.trash(Trash.Group, g, 2)
    assertEquals(emptyList<WaypointGroup>(), db.groups())
    assertEquals(listOf(loose), db.waypoints().map { it.id })
    db.untrash(Trash.Group, g)
    db.purgeTrashed(null)
    assertEquals(listOf(g to 1), db.groups().map { it.id to it.count })
  }

  // C5-14 and C5-17.
  @Test fun waypointLines() {
    val now = java.util.Calendar.getInstance().apply { clear(); set(2026, 9, 5, 9, 0) }.timeInMillis
    val aug = java.util.Calendar.getInstance().apply { clear(); set(2026, 7, 10, 9, 0) }.timeInMillis
    val x = w.copy(timeMs = aug, ele = 2600.4)
    assertEquals("8月10日 · 海拔 2600 m", waypointLine(x, now))
    assertEquals("海拔 2600 m · 8月10日", waypointLine(x, now, eleFirst = true))
    assertEquals("8月10日", waypointLine(x.copy(ele = null), now))
    assertEquals("海拔 2600 m", waypointLine(x.copy(timeMs = 0), now))
  }

  // R13: 「{最近地名}附近」 within 5 km, else when it was made.
  @Test fun defaultNames() {
    val at = java.util.Calendar.getInstance().apply { clear(); set(2026, 9, 3, 14, 32) }.timeInMillis
    val places = listOf(Place("远村", "village", 34.1, 108.0), Place("近村", "village", 34.01, 108.0))
    assertEquals("近村附近", defaultWaypointName(nearestPlace(places, 34.0, 108.0), at, at))
    assertEquals("10月3日 14:32", defaultWaypointName(nearestPlace(places, 35.0, 108.0), at, at))
  }

  @Test fun importsWithTheSameNameGetNumberedAndDeleteOnlyTheirOwn() {
    val x = db.importGroup("X", listOf(w, w))
    val x1 = db.importGroup("X", listOf(w))
    assertEquals(listOf("X" to 2, "X (1)" to 1), db.groups().map { it.name to it.count })
    db.deleteGroup(x)
    assertEquals(listOf(x1), db.groups().map { it.id })
    assertEquals(listOf(x1), db.waypoints().map { it.groupId })
  }

  @Test fun pulledGroupsStartHiddenAndGoWithTheirWaypointsWhenDeletedElsewhere() {
    assertTrue(db.applyGroup(SyncGroup("g1", "水源", false)))
    assertTrue(db.applyWaypoint(SyncWaypoint("w1", null, "g1", 0, 34.0, 108.0, null, "a", "", null, false)) { "" })
    assertTrue(db.applyWaypoint(SyncWaypoint("w2", null, null, 0, 34.0, 108.0, null, "b", "", null, false)) { "" })
    assertEquals(emptySet<Long>(), shown())
    assertEquals(listOf("水源" to 1), db.groups().map { it.name to it.count })
    assertTrue(db.applyGroup(SyncGroup("g1", "", true)))
    assertEquals(emptyList<WaypointGroup>(), db.groups())
    assertEquals(listOf("b"), db.waypoints().map { it.name })
  }

  // Hex uuids made here sort before "g1" and after "0".
  @Test fun ofTwoAlikeTheLargerUuidGetsNumberedAndPushedBack() {
    val here = db.addGroup("水源")!!
    db.applyGroup(SyncGroup("g1", "水源", false))
    assertEquals(listOf("水源", "水源 (1)"), db.groups().map { it.name })
    assertEquals(listOf("水源 (1)"), db.pendingGroups().filter { it.uuid == "g1" }.map { it.name })
    db.applyGroup(SyncGroup("0", "水源", false))
    // Now the one here is the larger: it moves over, and "0" keeps the name.
    assertEquals(listOf("水源", "水源 (1)", "水源 (2)"), db.groups().map { it.name })
    assertEquals("水源 (2)", db.groups().single { it.id == here }.name)
    assertTrue(db.pendingGroups().single { it.id == here }.dirty and SYNC_NAME != 0)
  }

  @Test fun groupsPushAndTheirDeletionLeavesMarkers() {
    val g = db.importGroup("X", listOf(w))
    for (p in db.pendingGroups()) db.pushed("waypoint_group", p.id, SYNC_ALL, p.edits)
    for (p in db.pendingWaypoints()) db.pushed("waypoint", p.id, SYNC_ALL, p.edits)
    assertEquals(emptyList<PendingGroup>(), db.pendingGroups())
    db.deleteGroup(g)
    assertEquals(listOf(true), db.pendingGroups().map { it.deleted })
    assertEquals(listOf(true), db.pendingWaypoints().map { it.deleted })
  }
}

class UniqueNameTest {
  @Test fun numbersFromOne() {
    assertEquals("X", uniqueName("X", emptySet()))
    assertEquals("X (2)", uniqueName("X", setOf("X", "X (1)")))
    assertEquals("X (2)", uniqueName("X (1)", setOf("X", "X (1)")))
  }
}
