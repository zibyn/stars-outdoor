package com.starsdom.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffTrackTest {
  // A 1 km east–west line near 秦岭; 0.0001° of latitude ≈ 11.1 m.
  private val line = listOf(listOf(TrackPoint(0, 33.96, 107.77, null), TrackPoint(0, 33.96, 107.7808, null)))

  @Test
  fun distanceIsToTheNearestSegmentNotVertex() {
    assertEquals(55.6, distanceToTrackM(33.9605, 107.775, line), 0.5)
    assertEquals(0.0, distanceToTrackM(33.96, 107.775, line), 0.01)
  }

  @Test
  fun distanceBeyondTheEndIsToTheEndpoint() {
    assertEquals(92.3, distanceToTrackM(33.96, 107.769, line), 0.5)
  }

  @Test
  fun offBeyondThresholdAndBackOnlyWellInside() {
    val m = OffTrackMonitor(line)
    m.update(33.9604, 107.775) // 44 m
    assertFalse(m.off)
    m.update(33.9605, 107.775) // 56 m
    assertTrue(m.off)
    m.update(33.9604, 107.775) // 44 m: GPS jitter around the threshold keeps it off
    assertTrue(m.off)
    m.update(33.9603, 107.775) // 33 m: back
    assertFalse(m.off)
  }
}
