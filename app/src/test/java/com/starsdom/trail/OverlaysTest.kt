package com.starsdom.trail

import com.starsdom.trail.track.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaysTest {
  // ux-v3 §2.4: colours in the order overlaid, kept when one before is taken off, starting over once none is left.
  @Test fun takesTheNextInOrderAndKeepsIt() {
    val three = mapOf<Long, Int>().overlay(10).overlay(20).overlay(30)
    assertEquals(mapOf(10L to 0, 20L to 1, 30L to 2), three)
    assertEquals(mapOf(10L to 0, 30L to 2, 40L to 3), (three - 20).overlay(40))
    assertEquals(mapOf(50L to 0), mapOf<Long, Int>().overlay(50))
  }

  @Test fun overlayingAgainChangesNothing() {
    assertEquals(mapOf(10L to 3), mapOf(10L to 3).overlay(10))
  }

  @Test fun noLimitAndTheFifthIsRoseAgain() {
    val seven = (1L..7L).fold(mapOf<Long, Int>()) { m, id -> m.overlay(id) }
    assertEquals(7, seven.size)
    for (s in listOf(LightSemantic, DarkSemantic)) {
      assertEquals(4, s.overlays.size)
      assertEquals(s.overlays[0], s.overlay(seven.getValue(5)))
    }
  }

  @Test fun survivesPrefs() {
    val m = mapOf(10L to 0, 30L to 2, 40L to 1)
    assertEquals(m, readOverlays(overlaysText(m)))
    assertEquals(emptyMap<Long, Int>(), readOverlays(null))
    assertEquals(emptyMap<Long, Int>(), readOverlays(""))
  }

  @Test fun waypointsShowWithTheirTrackOrByTheirOwnOverlay() {
    fun w(id: Long, track: Long?, shown: Boolean = true) = Waypoint(id, track, 0, 0.0, 0.0, null, "", "", null, shown = shown)
    val all = listOf(w(1, null), w(2, 10), w(3, 20), w(4, null, shown = false))
    assertEquals(listOf(1L, 2L), shownWaypoints(all, setOf(10L)).map { it.id })
    assertEquals(listOf(1L), shownWaypoints(all, emptySet()).map { it.id })
  }
}
