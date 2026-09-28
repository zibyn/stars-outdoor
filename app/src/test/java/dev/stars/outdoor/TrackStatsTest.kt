package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackStatsTest {
  // 0.001° of latitude ≈ 111.2 m.
  private fun p(sec: Long, lat: Double, ele: Double?) = TrackPoint(sec * 1000, lat, 107.77, ele)

  @Test
  fun pausedGapCountsNeitherDistanceNorTime() {
    val stats = trackStats(
      listOf(
        listOf(p(0, 33.000, 100.0), p(60, 33.001, 100.0)),
        // Resumed 1 h later, 1.1 km away: the gap is not part of the track.
        listOf(p(3660, 33.011, 100.0), p(3720, 33.012, 100.0)),
      )
    )
    assertEquals(222.4, stats.distanceM, 0.5)
    assertEquals(120_000L, stats.durationMs)
  }

  @Test
  fun ascentIgnoresGpsJitter() {
    val eles = listOf(100.0, 102.0, 99.0, 101.0, 110.0, 108.0, 120.0, null, 118.0, 130.0)
    val stats = trackStats(listOf(eles.mapIndexed { i, e -> p(i * 5L, 33.0 + i * 0.0001, e) }))
    // 100 → 110 → 120 → 130; the ±2–3 m wiggles in between don't count.
    assertEquals(30.0, stats.ascentM, 0.01)
  }

  @Test
  fun climbDuringPauseIsNotAscent() {
    // Paused at 100 m, took the cable car, resumed at 600 m.
    val stats = trackStats(listOf(listOf(p(0, 33.000, 100.0)), listOf(p(600, 33.010, 600.0), p(660, 33.011, 610.0))))
    assertEquals(10.0, stats.ascentM, 0.01)
  }

  @Test
  fun profileIsElevationAgainstCumulativeDistance() {
    val stats = trackStats(
      listOf(
        listOf(p(0, 33.000, 100.0), p(5, 33.001, null), p(10, 33.002, 120.0)),
        listOf(p(100, 33.010, 130.0)),
      )
    )
    assertEquals(listOf(0.0, 222.4, 222.4), stats.profile.map { Math.round(it.first * 10) / 10.0 })
    assertEquals(listOf(100.0, 120.0, 130.0), stats.profile.map { it.second })
  }

  @Test
  fun emptyTrack() {
    assertEquals(TrackStats(0.0, 0.0, 0L, emptyList()), trackStats(emptyList()))
  }
}
