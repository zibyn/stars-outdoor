package com.starsdom.trail.track

import com.starsdom.trail.Datum
import com.starsdom.trail.SyncGroup
import com.starsdom.trail.SyncTrack
import com.starsdom.trail.SyncWaypoint
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLibraryTest {
  private val db = TrackDb(RuntimeEnvironment.getApplication())

  @After fun close() = db.close()

  /** A library on the test's clock, its lists watched as the screen would. */
  private fun TestScope.library(): TrackLibrary = TrackLibrary(db, backgroundScope, StandardTestDispatcher(testScheduler)).also { lib ->
    backgroundScope.launch { merge(lib.tracks, lib.waypoints, lib.groups).collect {} }
  }

  private val line = ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null))))

  // ux-v3 §8.5 第 2 条: newest on this phone first, whenever it was walked; the row's date is still the walk's.
  @Test fun listedByWhenTheyCameHere() = runTest {
    val lib = library()
    val recorded = db.startTrack(5_000).also { db.endTrack(it, 6_000) }
    val old = db.importTrack(line, "老", emptyList(), 7_000)
    db.applyTrack(SyncTrack("u1", 500, 600, false, emptyList(), "pulled", Datum.WGS84, false, false))
    runCurrent()
    assertEquals(listOf(db.idOf("u1"), old, recorded), lib.tracks.value.map { it.id })
    assertEquals(listOf(500L, 1000L, 5_000L), lib.tracks.value.map { it.startedMs })
  }

  // The recording service writes on its own: the track it ends is listed, however late that comes.
  @Test fun aRecordingShowsOnceTheServiceEndsIt() = runTest {
    val lib = library()
    val id = db.startTrack(1_000)
    db.addPoint(id, 0, TrackPoint(1_000, 34.0, 108.0, null))
    runCurrent()
    assertEquals(emptyList<TrackSummary>(), lib.tracks.value)
    db.endTrack(id, 2_000)
    runCurrent()
    assertEquals(listOf(id), lib.tracks.value.map { it.id })
  }

  // So is a 标注 from the notification (C7-27).
  @Test fun aMarkFromTheServiceShows() = runTest {
    val lib = library()
    val w = db.addWaypoint(null, 1_000, 34.0, 108.0, null)
    runCurrent()
    assertEquals(listOf(w), lib.waypoints.value.map { it.id })
  }

  // What a pull brings shows at once: tracks, 标注组, their 标注.
  @Test fun aPullShows() = runTest {
    val lib = library()
    db.applyTrack(SyncTrack("u1", 500, 600, false, emptyList(), "pulled", Datum.WGS84, false, false))
    db.applyGroup(SyncGroup("g1", "水源", false))
    db.applyWaypoint(SyncWaypoint("w1", null, "g1", 0, 34.0, 108.0, null, "a", "", null, false)) { "" }
    runCurrent()
    assertEquals(listOf("pulled"), lib.tracks.value.map { it.name })
    assertEquals(listOf("水源" to 1), lib.groups.value.map { it.name to it.count })
    assertEquals(listOf("a"), lib.waypoints.value.map { it.name })
  }

  // C2-73: 「已公开」 shows or goes in place.
  @Test fun publicShowsAtOnce() = runTest {
    val lib = library()
    val id = db.importTrack(line, "t", emptyList(), 0)
    runCurrent()
    db.setPublic(id, true)
    runCurrent()
    assertTrue(lib.tracks.value.single().public)
  }
}
