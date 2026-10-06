package com.starsdom.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ux-v3 §8.3 第 1–5 条.
class PreTripTest {
  private val allGood = PhoneState(precise = true, locationOn = true, notifications = true, batteryUnrestricted = true, offlineCovered = true)

  @Test fun allGoodFailsNothing() = assertEquals(emptySet<Check>(), failing(allGood))

  @Test fun eachFailsItsOwn() {
    assertEquals(
      setOf(Check.Precise, Check.LocationOn, Check.Notifications, Check.Battery, Check.Offline),
      failing(PhoneState(precise = false, locationOn = false, notifications = false, batteryUnrestricted = false, offlineCovered = false)),
    )
  }

  // 第 3、4 条: battery, then offline, then notifications; offline only online; skipped 3 times in a row, no more.
  @Test fun remindersInOrder() {
    val all = setOf(Check.Notifications, Check.Offline, Check.Battery, Check.Precise)
    assertEquals(listOf(Check.Battery, Check.Offline, Check.Notifications), reminders(all, { 0 }, online = true))
    assertEquals(listOf(Check.Battery, Check.Notifications), reminders(all, { 0 }, online = false))
    assertEquals(listOf(Check.Offline, Check.Notifications), reminders(all, { if (it == Check.Battery) 3 else 2 }, online = true))
  }

  @Test fun coveredByABoxOrATracksCorridor() {
    val box = bboxRequest(108.0, 34.0, 108.2, 34.2)
    val track = trackRequest(listOf(listOf(TrackPoint(0, 33.0, 107.0, null), TrackPoint(0, 33.1, 107.0, null))))
    assertTrue(covered(34.1, 108.1, listOf(box)))
    assertFalse(covered(34.3, 108.1, listOf(box)))
    assertTrue(covered(33.05, 107.01, listOf(box, track)))
    assertFalse(covered(33.05, 107.05, listOf(track)))
  }

  @Test fun brandsThatKillBackgroundApps() {
    assertTrue(brandNote("Xiaomi"))
    assertTrue(brandNote("HUAWEI"))
    assertFalse(brandNote("Google"))
  }
}
