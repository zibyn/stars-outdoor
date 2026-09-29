package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivePagesTest {
  @Test fun firstPageIsWalkedTimeAscentAltitude() {
    val stats = TrackStats(5620.0, 420.4, (1 * 60 + 42) * 60_000L, listOf(0.0 to 1200.0, 5620.0 to 1650.0))
    assertEquals(
      listOf("5.62" to "已走 km", "1:42" to "用时", "420" to "爬升 m", "1648" to "海拔 m"),
      activePages(stats, altitudeM = 1648.3, battery = 85)[0],
    )
  }

  @Test fun secondPageIsPaceSpeedHighestBattery() {
    val stats = TrackStats(4000.0, 0.0, 60 * 60_000L, listOf(0.0 to 1200.0, 4000.0 to 1650.0))
    assertEquals(
      listOf("15:00" to "km 用时", "4.0" to "均速 km/h", "1650" to "最高海拔 m", "85%" to "电量"),
      activePages(stats, altitudeM = null, battery = 85)[1],
    )
  }

  @Test fun nothingYetShowsDashes() {
    val pages = activePages(null, altitudeM = null, battery = null)
    assertEquals(listOf("0.00", "0:00", "0", "—"), pages[0].map { it.first })
    assertEquals(listOf("—", "—", "—", "—"), pages[1].map { it.first })
  }
}
