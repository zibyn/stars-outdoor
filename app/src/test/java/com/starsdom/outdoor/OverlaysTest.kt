package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlaysTest {
  @Test fun takesTheSmallestFreeColourAndKeepsIt() {
    val three = mapOf<Long, Int>().overlay(10)!!.overlay(20)!!.overlay(30)!!
    assertEquals(mapOf(10L to 0, 20L to 1, 30L to 2), three)
    assertEquals(mapOf(10L to 0, 30L to 2, 40L to 1), (three - 20).overlay(40))
  }

  @Test fun overlayingAgainChangesNothing() {
    assertEquals(mapOf(10L to 3), mapOf(10L to 3).overlay(10))
  }

  @Test fun theSeventhIsRefusedAndNothingIsPushedOut() {
    val six = (1L..6L).fold(mapOf<Long, Int>()) { m, id -> m.overlay(id)!! }
    assertNull(six.overlay(7))
  }

  @Test fun survivesPrefsAndDropsTracksThatAreGone() {
    val m = mapOf(10L to 0, 30L to 2, 40L to 1)
    assertEquals(m, readOverlays(overlaysText(m), setOf(10L, 30L, 40L)))
    assertEquals(mapOf(10L to 0, 40L to 1), readOverlays(overlaysText(m), setOf(10L, 40L, 99L)))
    assertEquals(emptyMap<Long, Int>(), readOverlays(null, setOf(10L)))
    assertEquals(emptyMap<Long, Int>(), readOverlays("", setOf(10L)))
  }

  @Test fun waypointsShowWithTheirTrackOrByTheirOwnOverlay() {
    fun w(id: Long, track: Long?, shown: Boolean = true) = Waypoint(id, track, 0, 0.0, 0.0, null, "", "", null, shown = shown)
    val all = listOf(w(1, null), w(2, 10), w(3, 20), w(4, null, shown = false))
    assertEquals(listOf(1L, 2L), shownWaypoints(all, setOf(10L)).map { it.id })
    assertEquals(listOf(1L), shownWaypoints(all, emptySet()).map { it.id })
  }
}
