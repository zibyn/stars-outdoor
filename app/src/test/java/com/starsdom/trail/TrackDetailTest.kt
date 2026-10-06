package com.starsdom.trail

import com.starsdom.trail.track.TrackStats
import com.starsdom.trail.track.exportFileName
import org.junit.Assert.assertEquals
import org.junit.Test

// ux-v3 §8.2 第 5 条, C2-43…46, R4 / R5.
class TrackDetailTest {
  private val stats = TrackStats(12_400.0, 860.4, (5 * 60 + 32) * 60_000L, listOf(0.0 to 1500.0, 12_400.0 to 2310.4), descentM = 850.0)
  private fun List<Cell>.texts() = map { it.label to it.value }

  @Test fun fourNumbers() = assertEquals(
    listOf(R.string.cell_distance to "12 km", R.string.cell_ascent to "↑860 m", R.string.cell_descent to "↓850 m", R.string.cell_time to "5 h 32 min"),
    detailCells(stats, planned = false).texts(),
  )

  // #148: a plan, or a track with no times, has no 用时 to show.
  @Test fun aPlanOrATrackWithoutTimesShowsTheHighest() {
    assertEquals(R.string.cell_max_altitude to "2310 m", detailCells(stats, planned = true).texts().last())
    assertEquals(R.string.cell_max_altitude to "2310 m", detailCells(stats.copy(durationMs = 0), planned = false).texts().last())
    assertEquals(R.string.cell_max_altitude to "—", detailCells(stats.copy(durationMs = 0, profile = emptyList()), planned = false).texts().last())
  }

  @Test fun finishedDurations() {
    assertEquals("32 min", hoursMinutes(32 * 60_000L))
    assertEquals("5 h 0 min", hoursMinutes(5 * 3_600_000L))
  }

  // #146: the track's name, without what a file system won't take.
  @Test fun exportsUnderTheTracksName() {
    assertEquals("崇礼区 10月5日.gpx", exportFileName("崇礼区 10月5日", "gpx"))
    assertEquals("a_b_c_.kml", exportFileName("a/b:c?", "kml"))
    assertEquals("轨迹.zip", exportFileName("  ", "zip"))
    assertEquals(80 + 4, exportFileName("长".repeat(200), "gpx").length)
  }
}
