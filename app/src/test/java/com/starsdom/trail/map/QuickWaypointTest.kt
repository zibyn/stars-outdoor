package com.starsdom.trail.map

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickWaypointTest {
  @Test fun aGoodFixSavesAtOnce() {
    assertEquals(WaypointStep.Save, waypointStep(fixAccuracyM = 8.0, waitedMs = 0))
    assertEquals(WaypointStep.Save, waypointStep(fixAccuracyM = 50.0, waitedMs = 0))
  }

  @Test fun aPoorFixOrNoneWaitsUpTo60s() {
    assertEquals(WaypointStep.Wait, waypointStep(fixAccuracyM = 80.0, waitedMs = 0))
    assertEquals(WaypointStep.Wait, waypointStep(fixAccuracyM = null, waitedMs = 59_999))
    assertEquals(WaypointStep.Ask, waypointStep(fixAccuracyM = 80.0, waitedMs = 60_000))
    assertEquals(WaypointStep.Ask, waypointStep(fixAccuracyM = null, waitedMs = 60_000))
  }

  @Test fun aFixTurningGoodWhileWaitingSaves() {
    assertEquals(WaypointStep.Save, waypointStep(fixAccuracyM = 12.0, waitedMs = 30_000))
  }
}
