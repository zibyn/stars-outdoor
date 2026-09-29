package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlongTrackTest {
  // Near 秦岭: 0.0001° of latitude ≈ 11.1 m, 0.001° of longitude ≈ 92.3 m.
  private fun p(lat: Double, lon: Double) = TrackPoint(0, lat, lon, null)

  // 2.77 km east, then back 30 m to the north.
  private val outAndBack = listOf(listOf(p(33.96, 107.77), p(33.96, 107.80), p(33.9603, 107.80), p(33.9603, 107.77)))

  // A 1 km square.
  private val loop = listOf(listOf(p(33.96, 107.77), p(33.96, 107.7808), p(33.969, 107.7808), p(33.969, 107.77), p(33.96, 107.77)))

  @Test
  fun outAndBackGivesBothLegs() {
    val r = alongTrack(33.96015, 107.785, outAndBack)
    assertEquals(2, r.atM.size)
    assertEquals(1385.0, r.atM[0], 5.0)
    assertEquals(2770 + 33 + 1385.0, r.atM[1], 10.0)
  }

  @Test
  fun aLoopGoesRoundAnOutAndBackComesBack() {
    assertTrue(isLoop(loop))
    // Its ends are 33 m apart, but it comes back the way it went.
    assertFalse(isLoop(outAndBack))
    assertFalse(isLoop(line))
  }

  @Test
  fun outAndBackTrailheadStillGivesBothLegs() {
    // 0.1 km out and 0.1 km before the end.
    val r = alongTrack(33.96015, 107.771, outAndBack)
    assertEquals(2, r.atM.size)
  }

  @Test
  fun loopStartIsOneValue() {
    // Just past the start, 20 m along the first side; the closing side ends here too.
    val r = alongTrack(33.96, 107.77022, loop)
    assertEquals(1, r.atM.size)
    assertEquals(20.0, r.atM[0], 2.0)
  }

  @Test
  fun offTrackGivesNoValueButTheDistance() {
    val r = alongTrack(33.9609, 107.775, loop)
    assertTrue(r.atM.isEmpty())
    assertEquals(100.0, r.offM, 1.0)
  }

  @Test
  fun barWording() {
    assertEquals(ReferenceBarText("沿轨里程 · 正向", "3.0 / 13.8 km", "±8 m · 全长 16.9 km", grey = false),
      referenceBarText(AlongTrack(listOf(3_020.0, 13_760.0), 5.0), 8.0, 16_900.0))
    assertEquals(ReferenceBarText("沿轨里程 · 正向", "3.0 km", "精度差 ±80 m · 全长 16.9 km", grey = true),
      referenceBarText(AlongTrack(listOf(3_020.0), 5.0), 80.0, 16_900.0))
    // A fix that doesn't say how good it is doesn't pass.
    assertEquals(true, referenceBarText(AlongTrack(listOf(3_020.0), 5.0), null, 16_900.0).grey)
    assertEquals(ReferenceBarText("沿轨里程 · 正向", "不在轨迹上", "离轨迹 150 m · ±8 m · 全长 16.9 km", grey = false),
      referenceBarText(AlongTrack(emptyList(), 150.0), 8.0, 16_900.0))
    assertEquals(ReferenceBarText("沿轨里程 · 正向", "—", "全长 16.9 km", grey = false), referenceBarText(null, null, 16_900.0))
  }

  // 1 km east.
  private val line = listOf(listOf(p(33.96, 107.77), p(33.96, 107.77 + 1000 / 92_332.0)))

  @Test
  fun reversedCountsFromTheOtherEnd() {
    val at = 107.77 + 300 / 92_332.0
    assertEquals(300.0, alongTrack(33.96, at, line).atM.single(), 3.0)
    assertEquals(700.0, alongTrack(33.96, at, oriented(line, TrackStart(reversed = true))).atM.single(), 3.0)
  }

  @Test
  fun loopStartMovesZero() {
    val length = trackStats(loop).distanceM
    // Halfway up the east side, 1.5 km round; 20 m further on.
    val moved = oriented(loop, TrackStart(startM = 1500.0))
    assertEquals(length, trackStats(moved).distanceM, 0.01)
    assertEquals(20.0, alongTrack(33.964711, 107.7808, moved).atM.single(), 3.0)
    // The old start is now 1.5 km before the end.
    assertEquals(length - 1500 + 20, alongTrack(33.96, 107.77022, moved).atM.single(), 3.0)
    // Reversed from there: 20 m back towards the old start is 20.
    assertEquals(20.0, alongTrack(33.964352, 107.7808, oriented(loop, TrackStart(reversed = true, startM = 1500.0))).atM.single(), 3.0)
  }

  @Test
  fun nearestIsWhereATapLands() {
    val r = alongTrack(33.96005, 107.775, loop)
    assertEquals(461.0, r.nearestM, 3.0)
  }
}
