package com.starsdom.trail.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrimTest {
  private fun p(t: Long, lat: Double) = TrackPoint(t, lat, 108.0, null)
  // Two segments, a pause between: 0..2 and 3..4 flat.
  private val segments = listOf(listOf(p(1, 34.0), p(2, 34.001), p(3, 34.002)), listOf(p(10, 34.003), p(11, 34.004)))

  @Test fun pieceKeepsItsSegments() {
    assertEquals(listOf(listOf(p(2, 34.001), p(3, 34.002)), listOf(p(10, 34.003))), trimSegments(segments, 1..3))
    assertEquals(listOf(listOf(p(10, 34.003), p(11, 34.004))), trimSegments(segments, 3..4))
  }

  @Test fun distancesSkipThePause() {
    val d = alongDistances(segments)
    assertEquals(5, d.size)
    assertEquals(d[2], d[3], 0.0)
    assertEquals(trackStats(segments).distanceM, d.last(), 1e-6)
  }

  @Test fun handleSnapsToTheNearestPoint() {
    val d = doubleArrayOf(0.0, 100.0, 200.0, 200.0, 300.0)
    assertEquals(0, nearestIndex(d, -5.0))
    assertEquals(1, nearestIndex(d, 140.0))
    assertEquals(4, nearestIndex(d, 999.0))
  }

  @Test fun savableOnlyForAPartOfTwoPointsOrMore() {
    assertTrue(canSaveTrim(5, 1..3))
    assertFalse(canSaveTrim(5, 0..4))
    assertFalse(canSaveTrim(5, 2..2))
  }

  @Test fun timedWaypointsGoByTime() {
    fun w(t: Long) = Waypoint(t, null, t, 0.0, 0.0, null, "", "", null)
    assertEquals(listOf(2L, 10L), trimWaypoints(listOf(w(1), w(2), w(10), w(11)), trimSegments(segments, 1..3)).map { it.id })
  }

  @Test fun plannedWaypointsGoWithin50m() {
    val plan = listOf(listOf(TrackPoint(0, 34.0, 108.0, null), TrackPoint(0, 34.01, 108.0, null), TrackPoint(0, 34.02, 108.0, null)))
    fun w(id: Long, lat: Double, lon: Double) = Waypoint(id, null, 0, lat, lon, null, "", "", null)
    // 1: on the piece; 2: ~37 m off it; 3: ~92 m off; 4: past its end.
    val ws = listOf(w(1, 34.005, 108.0), w(2, 34.005, 108.0004), w(3, 34.005, 108.001), w(4, 34.015, 108.0))
    assertEquals(listOf(1L, 2L), trimWaypoints(ws, trimSegments(plan, 0..1)).map { it.id })
  }
}
