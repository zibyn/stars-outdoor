package com.starsdom.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ux-v3 §8.3 第 7–11、13、17 条, R4 / R5 / R18.
class DataStripTest {
  private val walked = TrackStats(3_215.0, 320.4, 3_932_000, listOf(0.0 to 1500.0, 3_215.0 to 1820.0), descentM = 45.0)
  // A 10 km track climbing 400 m in its second half.
  private val profile = listOf(0.0 to 1000.0, 5_000.0 to 1000.0, 10_000.0 to 1400.0)
  private fun ref(at: AlongTrack?, offTrack: Boolean = false) = Reference(10_000.0, at, profile, offTrack)
  private fun Strip.texts() = cells.map { it.label to it.value }

  @Test fun formats() {
    assertEquals("850 m", distanceValue(849.6))
    assertEquals("1.0 km", distanceValue(999.6))
    assertEquals("3.2 km", distanceValue(3_215.0))
    assertEquals("10 km", distanceValue(9_960.0))
    assertEquals("12 km", distanceValue(12_400.0))
    assertEquals("0:05:32", clock(332_400))
    assertEquals("1:05:32", clock(3_932_000))
    assertEquals("8′30″ /km", paceValue(510_000, 1_000.0))
    assertEquals("3.2 km/h", speedValue(3_600_000, 3_200.0))
  }

  @Test fun nothingWithoutRecordingOrReference() = assertNull(strip(null, null, null, fixAccuracyM = 5.0))

  @Test fun recordingWithoutReference() {
    val s = strip(walked, null, null, fixAccuracyM = 5.0)!!
    assertEquals(listOf(R.string.cell_walked to "3.2 km", R.string.cell_time to "1:05:32", R.string.cell_ascent to "↑320 m"), s.texts())
    assertFalse(s.closable || s.alert || s.dim)
    assertEquals(Speech.Distance(3_215.0), s.cells[0].speech)
  }

  @Test fun recordingWithReference() {
    val s = strip(walked, null, ref(AlongTrack(listOf(6_000.0), 5.0)), fixAccuracyM = 5.0)!!
    assertEquals(listOf(R.string.cell_left to "4.0 km", R.string.cell_left_ascent to "↑320 m", R.string.cell_time to "1:05:32"), s.texts())
    assertFalse(s.closable)
  }

  @Test fun referenceOnlyHasAlongAndClose() {
    val s = strip(null, null, ref(AlongTrack(listOf(2_000.0), 5.0)), fixAccuracyM = 5.0)!!
    assertEquals(listOf(R.string.cell_along to "2.0 km", R.string.cell_left to "8.0 km", R.string.cell_left_ascent to "↑400 m"), s.texts())
    assertTrue(s.closable)
    // C2-79: off the track, how far.
    val off = strip(null, null, ref(AlongTrack(emptyList(), 120.0)), 5.0)!!.cells[0]
    assertEquals("120 m" to R.string.off_track_by, off.value to off.format)
    // Two legs of an out-and-back: both, and 剩余 unknown.
    val both = strip(null, null, ref(AlongTrack(listOf(3_100.0, 13_800.0), 5.0)), 5.0)!!
    assertEquals("3.1 / 14 km", both.cells[0].value)
    assertEquals("—", both.cells[1].value)
  }

  @Test fun noFixDashesWhatNeedsOneAndTimeGoesOn() {
    val s = strip(walked, null, ref(null), fixAccuracyM = null)!!
    assertEquals(listOf("—", "—", "1:05:32"), s.cells.map { it.value })
    assertFalse(s.dim)
  }

  @Test fun poorFixDims() {
    assertTrue(strip(walked, null, null, fixAccuracyM = POOR_FIX_M + 1)!!.dim)
  }

  @Test fun offTrackWarnsInThe剩余Cell() {
    val s = strip(walked, null, ref(AlongTrack(emptyList(), 152.4), offTrack = true), 5.0)!!
    assertTrue(s.alert)
    assertEquals(R.string.cell_off to "152 m", s.texts()[0])
  }

  @Test fun offTrackWithoutAFixHasNoNumber() {
    val s = strip(walked, null, ref(null, offTrack = true), null)!!
    assertEquals(R.string.cell_off to "—", s.texts()[0])
  }

  // C3-19: recording, off the track is just that.
  @Test fun alongWhileRecordingOffTheTrack() {
    val along = panel(walked, null, null, ref(AlongTrack(emptyList(), 120.0))).first { it.label == R.string.cell_along }
    assertEquals(R.string.not_on_track, along.format)
  }

  @Test fun ascentLeftCountsFromHalfwayAlongAStretch() {
    assertEquals(320.0, ascentAfter(profile, 6_000.0), 0.01)
    assertEquals(400.0, ascentAfter(profile, 0.0), 0.01)
    assertEquals(0.0, ascentAfter(profile, 12_000.0), 0.01)
    // A 3 m wobble isn't a climb.
    assertEquals(0.0, ascentAfter(listOf(0.0 to 100.0, 10.0 to 103.0, 20.0 to 100.0), 0.0), 0.01)
  }

  // §8.3 第 11、13 条: time goes on between points and before the first, not while paused.
  @Test fun liveTimeRunsOnAndStopsWhenPaused() {
    val none = TrackStats(0.0, 0.0, 0, emptyList())
    assertEquals(30_000L, liveStats(none, null, since = 1_000, pausedAt = null, now = 31_000).first.durationMs)
    val some = none.copy(durationMs = 60_000)
    assertEquals(65_000L, liveStats(some, lastPointMs = 100_000, since = 40_000, pausedAt = null, now = 105_000).first.durationMs)
    val (paused, pausedMs) = liveStats(some, lastPointMs = 100_000, since = 40_000, pausedAt = 102_000, now = 400_000)
    assertEquals(62_000L, paused.durationMs)
    assertEquals(298_000L, pausedMs)
    // Back on after the pause, time counts from then, not from the last point before it.
    assertEquals(64_000L, liveStats(paused, lastPointMs = 100_000, since = 500_000, pausedAt = null, now = 502_000).first.durationMs)
  }

  @Test fun pausedDimsAndShowsHowLong() {
    val s = strip(walked, pausedMs = 332_400, null, 5.0)!!
    assertTrue(s.dim)
    assertEquals(R.string.cell_paused to "0:05:32", s.texts()[1])
  }

  @Test fun panelHasNineAndTheReferenceThree() {
    val cells = panel(walked, altitudeM = 1820.4, battery = 85, ref = null)
    assertEquals(
      listOf(
        R.string.cell_walked to "3.2 km", R.string.cell_time to "1:05:32", R.string.cell_ascent to "↑320 m",
        R.string.cell_descent to "↓45 m", R.string.cell_altitude to "1820 m", R.string.cell_max_altitude to "1820 m",
        R.string.cell_pace to "20′23″ /km", R.string.cell_speed to "2.9 km/h", R.string.cell_battery to "85%",
      ),
      cells.map { it.label to it.value },
    )
    assertEquals(12, panel(walked, 1820.4, 85, ref(AlongTrack(listOf(6_000.0), 5.0))).size)
    // Under 100 m a pace would be noise.
    assertEquals("—", panel(walked.copy(distanceM = 50.0), null, null, null).first { it.label == R.string.cell_pace }.value)
  }
}
