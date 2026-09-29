package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePagesTest {
  private val stats = TrackStats(5620.0, 420.4, (1 * 60 + 42) * 60_000L, listOf(0.0 to 1200.0, 5620.0 to 1650.0))

  @Test fun firstPageIsWalkedTimeAscentAltitude() {
    val page = activePages(stats, altitudeM = 1648.3, battery = 85)[0]
    assertEquals(listOf("5.62" to "已走 km", "1:42" to "用时", "420" to "爬升 m", "1648" to "海拔 m"), page.cells)
    assertNull(page.row)
  }

  @Test fun secondPageIsPaceSpeedHighestBattery() {
    val stats = TrackStats(4000.0, 0.0, 60 * 60_000L, listOf(0.0 to 1200.0, 4000.0 to 1650.0))
    assertEquals(
      listOf("15:00" to "km 用时", "4.0" to "均速 km/h", "1650" to "最高海拔 m", "85%" to "电量"),
      activePages(stats, altitudeM = null, battery = 85)[1].cells,
    )
  }

  @Test fun nothingYetShowsDashes() {
    val pages = activePages(null, altitudeM = null, battery = null)
    assertEquals(listOf("0.00", "0:00", "0", "—"), pages[0].cells.map { it.first })
    assertEquals(listOf("—", "—", "—", "—"), pages[1].cells.map { it.first })
  }

  @Test fun withAReferenceTheRowTopsThreeCellsAndPageTwoHasRemaining() {
    val pages = activePages(stats, altitudeM = 1648.3, battery = 85, along = AlongNow(listOf(7_300.0), 16_900.0, offM = 5.0, accuracyM = 8.0, arrival = "15:40"))
    assertEquals("沿轨 7.3 km", pages[0].row)
    assertEquals(listOf("已走 km", "用时", "爬升 m"), pages[0].cells.map { it.second })
    assertEquals(listOf("18:09" to "km 用时", "9.6" to "剩余 km", "15:40" to "预计到达", "85%" to "电量"), pages[1].cells)
  }

  @Test fun severalValuesAllShowButNoRemaining() {
    val pages = activePages(stats, null, 85, AlongNow(listOf(3_100.0, 13_800.0), 16_900.0, offM = 5.0, accuracyM = 8.0, arrival = "15:40"))
    assertEquals("沿轨 3.1 / 13.8 km", pages[0].row)
    assertEquals(listOf("—", "—"), pages[1].cells.subList(1, 3).map { it.first })
  }

  @Test fun offTrackIsAnAlertAndNoRemaining() {
    val pages = activePages(stats, null, 85, AlongNow(emptyList(), 16_900.0, offM = 152.0, alert = true, accuracyM = 8.0))
    assertEquals("偏离 152 m", pages[0].row)
    assertTrue(pages[0].alert)
    assertEquals(listOf("—", "—"), pages[1].cells.subList(1, 3).map { it.first })
    // The 偏离提醒 is on, but the fix has gone stale.
    assertEquals("偏离", activePages(stats, null, 85, AlongNow(emptyList(), 16_900.0, alert = true))[0].row)
  }

  @Test fun offTheTrackBeforeAnyAlert() {
    assertEquals("不在轨迹上", activePages(stats, null, 85, AlongNow(emptyList(), 16_900.0, offM = 70.0, accuracyM = 8.0))[0].row)
  }

  @Test fun poorFixGreysAndSays() {
    val page = activePages(stats, null, 85, AlongNow(listOf(7_300.0), 16_900.0, offM = 5.0, accuracyM = 80.0))[0]
    assertTrue(page.grey)
    assertEquals("精度差 ±80 m", page.note)
  }

  @Test fun noFixYet() {
    val page = activePages(stats, null, 85, AlongNow(emptyList(), 16_900.0))[0]
    assertEquals("沿轨 —", page.row)
    assertNull(page.note)
  }
}
