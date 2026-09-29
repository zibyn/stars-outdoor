package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class KmMarksTest {
  private fun p(lat: Double, lon: Double) = TrackPoint(0, lat, lon, null)

  // Near 秦岭 one metre east is 1 / 92 332 °; 3 km east, 33 m north, 3 km back.
  private val east = 107.77 + 3000 / 92_332.0
  private val outAndBack = listOf(listOf(p(33.96, 107.77), p(33.96, east), p(33.9603, east), p(33.9603, 107.77)))

  @Test
  fun marksEveryStepButNotRightAtTheEnd() {
    val marks = kmMarks(kmPoints(outAndBack), 1, mergeM = 0.0)
    // 6.03 km: 6 is within 150 m of the end.
    assertEquals(listOf("1", "2", "3", "4", "5"), marks.map { it.label })
    assertEquals(107.77 + 1000 / 92_332.0, marks[0].lon, 3e-5) // within ~3 m
    assertEquals(33.96, marks[0].lat, 1e-9)
    assertEquals(listOf("5"), kmMarks(kmPoints(outAndBack), 5, mergeM = 0.0).map { it.label })
  }

  @Test
  fun outAndBackMarksAtOnePlaceMerge() {
    assertEquals(listOf("1 / 5", "2 / 4", "3"), kmMarks(kmPoints(outAndBack), 1, mergeM = 60.0).map { it.label })
  }

  @Test
  fun stepFollowsZoom() {
    // At the equator 1 km is 45 dp from about z11.8, 12 dp from about z9.9.
    assertEquals(1, kmStep(zoom = 12.0, lat = 0.0))
    assertEquals(5, kmStep(zoom = 10.0, lat = 0.0))
    assertEquals(10, kmStep(zoom = 9.0, lat = 0.0))
    // A 17 km track across a 330 dp profile: 19 dp a km.
    assertEquals(5, kmStep(dpPerKm = 330 / 17.0))
  }
}
