package com.starsdom.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
  private fun p(t: Long) = TrackPoint(t, 34.0, 108.0, null)

  @Test fun tracksGoByStartPlansByPick() {
    val a = TrackSummary(1, "a", false, startedMs = 200)
    val b = TrackSummary(2, "b", false, startedMs = 100)
    assertEquals(listOf(2L, 1L), mergeOrder(listOf(a, b)).map { it.id })
    val x = TrackSummary(3, "x", true)
    val y = TrackSummary(4, "y", true, startedMs = -1)
    assertEquals(listOf(3L, 4L), mergeOrder(listOf(x, y)).map { it.id })
  }

  @Test fun overlappingTimes() {
    val a = listOf(listOf(p(1), p(5)))
    val b = listOf(listOf(p(5), p(9)))
    val c = listOf(listOf(p(4), p(6)))
    val plan = listOf(listOf(p(0), p(0)))
    assertFalse(timesOverlap(listOf(a, b)))
    assertFalse(timesOverlap(listOf(b, a, plan)))
    assertTrue(timesOverlap(listOf(b, c)))
    // A pause doesn't open a gap another track can fill.
    assertTrue(timesOverlap(listOf(listOf(listOf(p(1), p(2)), listOf(p(8), p(9))), listOf(listOf(p(4), p(5))))))
  }

  @Test fun tracksKeepTheirSegmentsPlansJoinIntoOne() {
    val a = listOf(listOf(p(1), p(2)), listOf(p(3)))
    val b = listOf(listOf(p(4), p(5)))
    assertEquals(listOf(listOf(p(1), p(2)), listOf(p(3)), listOf(p(4), p(5))), mergeSegments(listOf(a, b), planned = false))
    assertEquals(listOf(listOf(p(1), p(2), p(3), p(4), p(5))), mergeSegments(listOf(a, b), planned = true))
  }
}
