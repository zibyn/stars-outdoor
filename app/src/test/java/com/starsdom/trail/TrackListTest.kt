package com.starsdom.trail

import com.starsdom.trail.track.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

// ux-v3 §8.5 第 1 条, C5-06, R4 / R5.
class TrackListTest {
  private fun at(year: Int, month: Int, day: Int) = Calendar.getInstance().apply { clear(); set(year, month - 1, day, 9, 0) }.timeInMillis
  private val now = at(2026, 10, 5)
  private val stats = TrackStats(3_215.0, 860.4, 3_600_000, emptyList())

  @Test fun dateDistanceAndAscent() = assertEquals("10月5日 · 3.2 km · ↑860 m", trackLine(at(2026, 10, 5), planned = false, stats, now))

  @Test fun anotherYearSaysIt() = assertEquals("2025年10月5日 · 3.2 km · ↑860 m", trackLine(at(2025, 10, 5), planned = false, stats, now))

  @Test fun aPlanHasNoDate() = assertEquals("3.2 km · ↑860 m", trackLine(at(2026, 10, 5), planned = true, stats, now))

  @Test fun dateAloneUntilTheNumbersAreIn() = assertEquals("10月5日", trackLine(at(2026, 10, 5), planned = false, null, now))
}
