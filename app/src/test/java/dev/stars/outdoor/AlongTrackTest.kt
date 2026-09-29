package dev.stars.outdoor

import org.junit.Assert.assertEquals
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
  fun outAndBackTrailheadStillGivesBothLegs() {
    // Its ends meet like a loop's, but the legs run opposite ways: 0.1 km out and 0.1 km before the end.
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
}
