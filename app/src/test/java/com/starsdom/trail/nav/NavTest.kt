package com.starsdom.trail.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.starsdom.trail.WeatherPlace
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavTest {
  // 设置 → 关于 → 数据来源: each over the last, Back closing them in turn.
  @Test fun pagesStackAndBackClosesTheTopOne() {
    val pages = NavBackStack<NavKey>(MapRoot)
    pages.open(Page.About)
    pages.open(Page.Sources)
    assertEquals(listOf(MapRoot, Page.About, Page.Sources), pages)
    pages.removeLastOrNull()
    assertEquals(listOf(MapRoot, Page.About), pages)
  }

  // 关于 again from the upgrade prompt, under 数据来源: it comes up, never twice on the stack.
  @Test fun openingAnOpenPageBringsItUp() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.About, Page.Sources)
    pages.open(Page.About)
    assertEquals(listOf(MapRoot, Page.Sources, Page.About), pages)
  }

  // 出发前检查 from 设置: over it, Back back to 设置.
  @Test fun preTripOverSettings() {
    val pages = NavBackStack<NavKey>(MapRoot)
    pages.open(Page.Settings)
    pages.open(Page.PreTrip)
    assertEquals(listOf(MapRoot, Page.Settings, Page.PreTrip), pages)
    pages.removeLastOrNull()
    assertEquals(listOf(MapRoot, Page.Settings), pages)
  }

  // 天气 for another place (the 风险 hint's 看天气 over a point's): it takes the place of the one open.
  @Test fun oneWeatherPageAtATime() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Weather(WeatherPlace.Point(34.0, 108.0, "太白山")))
    pages.open(Page.Weather(WeatherPlace.Here))
    assertEquals(listOf(MapRoot, Page.Weather(WeatherPlace.Here)), pages)
  }

  // A drawer opening by itself (#196): back to the map.
  @Test fun closePagesLeavesTheMap() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.About, Page.Sources)
    pages.closePages()
    assertEquals(listOf(MapRoot), pages)
  }

  // Turning the phone round: the pages come back as they were.
  @Test fun savedAndRestored() {
    val serializer = NavBackStackSerializer(NavKeySerializer())
    val all = listOf(MapRoot, Page.Settings, Page.PreTrip, Page.Offline, Page.About, Page.Sources, Page.Search, Page.Weather(WeatherPlace.Here))
    for (place in listOf(WeatherPlace.Point(34.0, 108.0, "太白山"), WeatherPlace.Track(7))) {
      val saved = encodeToSavedState(serializer, NavBackStack(MapRoot, Page.Weather(place)))
      assertEquals(listOf(MapRoot, Page.Weather(place)), decodeFromSavedState(serializer, saved).toList())
    }
    val saved = encodeToSavedState(serializer, NavBackStack(*all.toTypedArray()))
    assertEquals(all, decodeFromSavedState(serializer, saved).toList())
  }
}
