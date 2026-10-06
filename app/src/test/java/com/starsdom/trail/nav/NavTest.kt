package com.starsdom.trail.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
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

  // 队伍: in a team, its 对话; not, 建队 / 加入. Opened again, whatever 队伍页 was open goes.
  @Test fun teamOpensOnChatOrJoin() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings)
    pages.openTeam(inTeam = false)
    assertEquals(listOf(MapRoot, Page.Settings, Page.Team.Join), pages)
    pages.openTeam(inTeam = true)
    assertEquals(listOf(MapRoot, Page.Settings, Page.Team.Chat), pages)
  }

  // 对话 → 队伍信息 / 新建队伍: over it, Back back to the 对话. Joined, straight into the new team's 对话.
  @Test fun teamPagesStackOverTheChat() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Team.Chat)
    pages.open(Page.Team.Info)
    assertEquals(listOf(MapRoot, Page.Team.Chat, Page.Team.Info), pages)
    pages.removeLastOrNull()
    pages.open(Page.Team.Join)
    pages.openTeam(inTeam = true)
    assertEquals(listOf(MapRoot, Page.Team.Chat), pages)
  }

  // 退出队伍 (or a location tapped in the 对话): every 队伍页 goes, the rest stays.
  @Test fun closeTeamClosesOnlyTeamPages() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings, Page.Team.Chat, Page.Team.Info)
    pages.closeTeam()
    assertEquals(listOf(MapRoot, Page.Settings), pages)
  }

  // A drawer opening by itself under 登录 (#196): 登录 (and its 头像 crop) stays, the rest goes.
  @Test fun closePagesKeepsLogin() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings, Page.Login, Page.Crop("content://photo"))
    pages.closePages()
    assertEquals(listOf(MapRoot, Page.Login, Page.Crop("content://photo")), pages)
  }

  // A drawer opening by itself (#196): back to the map.
  @Test fun closePagesLeavesTheMap() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.About, Page.Sources)
    pages.closePages()
    assertEquals(listOf(MapRoot), pages)
  }

  // Turning the phone round: the pages come back as they were.
  @Test fun savedAndRestored() {
    val serializer = PagesSerializer
    val all = listOf(MapRoot, Page.Settings, Page.PreTrip, Page.Offline, Page.About, Page.Sources, Page.Search, Page.Weather(WeatherPlace.Here), Page.Team.Chat, Page.Team.Info, Page.Team.Join, Page.Login, Page.Crop("content://photo"), Page.ImportPick)
    for (place in listOf(WeatherPlace.Point(34.0, 108.0, "太白山"), WeatherPlace.Track(7))) {
      val saved = encodeToSavedState(serializer, NavBackStack(MapRoot, Page.Weather(place)))
      assertEquals(listOf(MapRoot, Page.Weather(place)), decodeFromSavedState(serializer, saved).toList())
    }
    val saved = encodeToSavedState(serializer, NavBackStack(*all.toTypedArray()))
    assertEquals(all, decodeFromSavedState(serializer, saved).toList())
  }

  // 抽屉: one at a time, the next one opened replacing it.
  @Test fun oneDrawerAtATime() {
    val d = Drawers().open(Drawer.Layers).open(Drawer.Mate(3))
    assertEquals(Drawers(Drawer.Mate(3)), d)
    assertEquals(Drawers(Drawer.Tracks(listOf(TrackLayer.Detail(5)))), d.openDetail(5))
  }

  // 轨迹详情 from the list: back to the list. Opened by itself: back closes the drawer.
  @Test fun detailBackGoesToTheListItCameFrom() {
    val fromList = Drawers().push(TrackLayer.List).push(TrackLayer.Detail(5))
    assertEquals(Drawers(Drawer.Tracks(listOf(TrackLayer.List))), fromList.back())
    assertEquals(Drawers(), Drawers().openDetail(5).back())
  }

  // 标注组 → a 标注 edited: back a layer at a time.
  @Test fun tracksLayersStack() {
    val d = Drawers().push(TrackLayer.List).push(TrackLayer.Group(2)).push(TrackLayer.Waypoint(9))
    assertEquals(9L, d.editing)
    assertEquals(listOf(TrackLayer.List, TrackLayer.Group(2)), d.back().tracks)
  }

  // The 地点小抽屉 over a 轨迹详情 leaves it, and the list under it (#202); over anything else it replaces it.
  @Test fun placeOverDetailKeepsIt() {
    val at = Pin(34.0, 108.0)
    val d = Drawers().push(TrackLayer.List).push(TrackLayer.Detail(5)).push(TrackLayer.Waypoint(9)).openPlace(at)
    assertEquals(Drawers(Drawer.Tracks(listOf(TrackLayer.List, TrackLayer.Detail(5))), at), d)
    assertEquals(listOf(TrackLayer.List), d.back().back().tracks)
    assertEquals(Drawers(null, at), Drawers().push(TrackLayer.List).openPlace(at))
    assertEquals(Drawers(null, at), Drawers().open(Drawer.Layers).openPlace(at))
  }

  // A tap on the map closes 我的位置, a 队友's and 周边路网's drawers and the 地点小抽屉; the rest stay.
  @Test fun tapClosesSome() {
    assertEquals(Drawers(), Drawers(Drawer.Me, Pin(1.0, 2.0)).tapped())
    assertEquals(Drawers(Drawer.Layers), Drawers(Drawer.Layers).tapped())
  }

  // A track cut or merged: 轨迹详情 goes on with the new one, in its place.
  @Test fun detailNowOnNewTrack() {
    val d = Drawers().push(TrackLayer.List).push(TrackLayer.Detail(5)).detailNowOn(6)
    assertEquals(listOf(TrackLayer.List, TrackLayer.Detail(6)), d.tracks)
  }

  // A track deleted under its 轨迹详情: back to the list, or the drawer closes.
  @Test fun withoutDetail() {
    assertEquals(listOf(TrackLayer.List), Drawers().push(TrackLayer.List).push(TrackLayer.Detail(5)).without(TrackLayer.Detail(5)).tracks)
    assertEquals(Drawers(), Drawers().openDetail(5).without(TrackLayer.Detail(5)))
  }

  // Turning the phone round: the drawer and 我的轨迹's layers come back.
  @Test fun drawersSavedAndRestored() {
    val d = Drawers(Drawer.Tracks(listOf(TrackLayer.List, TrackLayer.Detail(5), TrackLayer.Waypoint(9))), Pin(34.0, 108.0))
    assertEquals(d, decodeFromSavedState(Drawers.serializer(), encodeToSavedState(Drawers.serializer(), d)))
  }

  // An import or a recording ended with 设置、搜索、队伍 open: back to the map, the track in its 轨迹详情 (#196).
  @Test fun trackCameInGoesToTheMap() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings, Page.Search, Page.Team.Chat)
    assertEquals(Drawers().openDetail(5), Drawers(Drawer.Layers).cameIn(listOf(5), pages))
    assertEquals(listOf(MapRoot), pages)
  }

  // Under 登录: the 轨迹详情 waits under it, Back closing 登录 first.
  @Test fun trackCameInUnderLogin() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings, Page.Login)
    assertEquals(Drawers().openDetail(5), Drawers().cameIn(listOf(5), pages))
    assertEquals(listOf(MapRoot, Page.Login), pages)
  }

  // Several imported (or only 标注): the list, new on top.
  @Test fun manyCameInStayInTheList() {
    val list = Drawers(Drawer.Tracks(listOf(TrackLayer.List)))
    assertEquals(list, Drawers().openDetail(3).cameIn(listOf(5, 6), NavBackStack(MapRoot)))
    assertEquals(list, Drawers().cameIn(emptyList(), NavBackStack(MapRoot)))
  }

  // A 群聊 notification or a file opened under 登录: the page goes under it, 登录 staying on top (#134).
  @Test fun pagesOpenUnderLogin() {
    val pages = NavBackStack<NavKey>(MapRoot, Page.Settings, Page.Login)
    pages.openTeam(inTeam = true)
    pages.open(Page.ImportPick)
    assertEquals(listOf(MapRoot, Page.Settings, Page.Team.Chat, Page.ImportPick, Page.Login), pages)
  }

  // 我的轨迹 dragged down over a 轨迹详情: back to it; with none, the drawer closes.
  @Test fun dragDownToDetail() {
    val d = Drawers().push(TrackLayer.List).push(TrackLayer.Detail(5)).push(TrackLayer.Waypoint(9))
    assertEquals(listOf(TrackLayer.List, TrackLayer.Detail(5)), d.downToDetail().tracks)
    assertEquals(Drawers(), Drawers().push(TrackLayer.List).push(TrackLayer.Group(2)).downToDetail())
  }
}
