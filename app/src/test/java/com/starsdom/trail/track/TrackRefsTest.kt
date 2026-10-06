package com.starsdom.trail.track

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackRefsTest {
  private val refs = TrackRefs(reference = 1, overlays = mapOf(1L to 0, 2L to 1), starts = setOf(1, 3), teamTrack = 3)

  // Deleted with 撤销 on offer, a track is still known: all it had stays, for 撤销 to bring back.
  @Test fun aTrackInTheTrashKeepsAll() {
    assertEquals(refs, knownRefs(refs, setOf(1, 2, 3)))
  }

  // Gone for good (撤销 over, or deleted on another phone): its 参考, 叠加, 起算点 and 队伍轨迹 go, the others' stay.
  @Test fun aTrackGoneTakesItsAlong() {
    assertEquals(TrackRefs(null, mapOf(2L to 1), setOf(3), 3), knownRefs(refs, setOf(2, 3)))
    assertEquals(TrackRefs(1, mapOf(1L to 0), setOf(1), null), knownRefs(refs, setOf(1)))
    assertEquals(TrackRefs(null, emptyMap(), emptySet(), null), knownRefs(refs, emptySet()))
  }
}
