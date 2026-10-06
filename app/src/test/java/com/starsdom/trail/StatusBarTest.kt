package com.starsdom.trail

import com.starsdom.trail.track.POOR_FIX_M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ux-v3 §6 状态条: one line, by priority; R8 两种没定位, R15 下载, C7-07 同步失败.
class StatusBarTest {
  private fun text(s: StatusInput) = status(s)?.text

  @Test fun nothingWrongShowsNothing() {
    assertNull(status(StatusInput()))
    assertNull(status(StatusInput(recording = true)))
  }

  @Test fun noLocationIsOneOfTwoAndNeverAboutPermission() {
    assertEquals(R.string.status_location_off, text(StatusInput(locationOn = false, fixAccuracyM = null)))
    assertEquals(StatusAction.OpenLocation, status(StatusInput(locationOn = false))?.action)
    assertEquals(R.string.status_weak_fix, text(StatusInput(fixAccuracyM = null)))
    assertEquals(R.string.status_weak_fix, text(StatusInput(fixAccuracyM = POOR_FIX_M + 1)))
    assertNull(status(StatusInput(fixAccuracyM = POOR_FIX_M)))
    assertNull(status(StatusInput(permitted = false, fixAccuracyM = null)))
    assertNull(status(StatusInput(permitted = false, locationOn = false)))
  }

  @Test fun locationWhileRecordingOutranksOfflineWhichOutranksLocationOtherwise() {
    val s = StatusInput(fixAccuracyM = null, online = false)
    assertEquals(R.string.status_offline, text(s))
    assertEquals(R.string.status_weak_fix, text(s.copy(recording = true)))
    assertEquals(R.string.status_location_off, text(s.copy(recording = true, locationOn = false)))
  }

  @Test fun offlineAndDownloadingShowsOnlyOffline() {
    assertEquals(R.string.status_offline, text(StatusInput(online = false, downloadPercent = 45)))
    val down = status(StatusInput(downloadPercent = 45))!!
    assertEquals(R.string.status_downloading, down.text)
    assertEquals(45, down.percent)
    assertEquals(R.string.status_weak_fix, text(StatusInput(fixAccuracyM = null, downloadPercent = 45)))
  }

  @Test fun offlineOnSatelliteOrStandardOffersTerrain() {
    assertEquals(StatusAction.Terrain, status(StatusInput(online = false, basemap = Basemap.Satellite))?.action)
    assertEquals(StatusAction.Terrain, status(StatusInput(online = false, basemap = Basemap.Standard))?.action)
    assertNull(status(StatusInput(online = false))?.action)
  }

  @Test fun syncFailedLastWithRetry() {
    val s = StatusInput(syncFailed = true)
    assertEquals(R.string.status_sync_failed, text(s))
    assertEquals(StatusAction.RetrySync, status(s)?.action)
    assertEquals(R.string.status_downloading, text(s.copy(downloadPercent = 3)))
    assertEquals(R.string.status_weak_fix, text(s.copy(fixAccuracyM = null)))
  }

  // §8.1 第 3 条: only 大致位置 given, the map draws its circle and says nothing; the switch still counts.
  @Test fun approximateOnlyIsNotAWeakFix() {
    assertNull(status(StatusInput(precise = false, fixAccuracyM = 2_000.0)))
    assertNull(status(StatusInput(precise = false, fixAccuracyM = null)))
    assertEquals(R.string.status_location_off, text(StatusInput(precise = false, locationOn = false)))
  }

  // #143: paused, the GPS is off on purpose.
  @Test fun noLocationLineWhilePaused() {
    assertNull(status(StatusInput(recording = true, paused = true, fixAccuracyM = null)))
    assertNull(status(StatusInput(recording = true, paused = true, locationOn = false)))
    assertEquals(R.string.status_offline, text(StatusInput(recording = true, paused = true, fixAccuracyM = null, online = false)))
  }
}
