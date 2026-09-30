package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarTest {
  private fun texts(active: Boolean, s: StatusInput) = statusLines(active, s).map { it.text }

  @Test fun nothingWrongShowsNothing() {
    assertEquals(emptyList<String>(), texts(false, StatusInput()))
    assertEquals(emptyList<String>(), texts(true, StatusInput()))
  }

  @Test fun satelliteOfflineComesFirstThenOffline() {
    val s = StatusInput(online = false, basemap = Basemap.Satellite)
    assertEquals(listOf("卫星图离线用不了 · 切到地形", "离线中：已下载的地图和记录照常可用"), texts(false, s))
    assertEquals(StatusAction.Terrain, statusLines(false, s).first().action)
    assertEquals(listOf("标准图离线用不了 · 切到地形", "离线 · 地图照常用"), texts(true, s.copy(basemap = Basemap.Standard)))
  }

  @Test fun locationOffOutranksEverythingAndHidesNoFix() {
    val s = StatusInput(locationOn = false, fixAccuracyM = null, online = false)
    assertEquals(listOf("定位已关 · 点这里打开", "离线 · 地图照常用"), texts(true, s))
    assertEquals("定位已关，记录和共享都用不了 · 点这里打开", texts(false, s).first())
    assertEquals(StatusAction.OpenLocation, statusLines(false, s).first().action)
  }

  @Test fun noPermission() {
    val s = StatusInput(permitted = false, fixAccuracyM = null)
    assertEquals(listOf("没有定位权限 · 点这里开启"), texts(true, s))
    assertEquals(listOf("没有定位权限，记录不到轨迹 · 点这里开启"), texts(false, s))
    assertEquals(StatusAction.Permission, statusLines(false, s).first().action)
  }

  @Test fun noFixAndPoorFix() {
    assertEquals(listOf("正在定位"), texts(true, StatusInput(fixAccuracyM = null)))
    assertEquals(listOf("正在定位，到开阔处更快"), texts(false, StatusInput(fixAccuracyM = null)))
    assertEquals(emptyList<String>(), texts(true, StatusInput(fixAccuracyM = 50.0)))
    assertEquals(listOf("定位不准 · 偏离提醒暂停"), texts(true, StatusInput(fixAccuracyM = 80.4, reference = true)))
    assertEquals(listOf("定位不准（约 80 m）"), texts(false, StatusInput(fixAccuracyM = 80.4)))
    // The 参考轨迹条 greys out instead (§3.2).
    assertEquals(emptyList<String>(), texts(false, StatusInput(fixAccuracyM = 80.4, reference = true)))
  }

  @Test fun unsentPositionsWhileInATeam() {
    val s = StatusInput(online = false, unsent = true)
    assertEquals(listOf("离线 · 位置联网后补发", "离线 · 地图照常用"), texts(true, s))
    assertEquals("没有信号，你的位置联网后补发给队友", texts(false, s).first())
  }

  @Test fun lowBattery() {
    assertEquals(emptyList<String>(), texts(true, StatusInput(battery = 20)))
    assertEquals(listOf("电量 18%"), texts(true, StatusInput(battery = 18, sharing = true)))
    assertEquals(listOf("电量 18%"), texts(false, StatusInput(battery = 18)))
    assertEquals(listOf("电量 18%，共享已降到每 2 分钟一次"), texts(false, StatusInput(battery = 18, sharing = true)))
    assertEquals(listOf("电量 8%，共享已降到每 5 分钟一次"), texts(false, StatusInput(battery = 8, sharing = true)))
  }

  @Test fun syncFailedOnlyWhilePlanning() {
    val s = StatusInput(syncFailed = true, lastSync = "14:05")
    assertEquals(emptyList<String>(), texts(true, s))
    assertEquals(listOf("同步没成功，联网后会自动再试 · 上次同步 14:05"), texts(false, s))
    assertEquals(listOf("同步没成功，联网后会自动再试"), texts(false, s.copy(lastSync = null)))
  }

  @Test fun orderedByGroup() {
    val s = StatusInput(fixAccuracyM = null, online = false, battery = 10, syncFailed = true)
    assertEquals(listOf("正在定位，到开阔处更快", "离线中：已下载的地图和记录照常可用", "电量 10%", "同步没成功，联网后会自动再试"), texts(false, s))
  }
}
