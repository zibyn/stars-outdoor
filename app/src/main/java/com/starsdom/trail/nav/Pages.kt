package com.starsdom.trail.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.starsdom.trail.WeatherPlace
import kotlinx.serialization.Serializable

// The back stack's keys (ADR 0015): the map at the root, the 整页 over it. Saved with the stack, so they're @Serializable.

/** The back stack's root: the map, with its drawers. */
@Serializable data object MapRoot : NavKey

/** A 整页. */
@Serializable sealed interface Page : NavKey {
  @Serializable data object Settings : Page
  @Serializable data object PreTrip : Page
  @Serializable data object Offline : Page
  @Serializable data object About : Page
  @Serializable data object Sources : Page
  @Serializable data object Search : Page
  /** 天气 for a place (ADR 0011). */
  @Serializable data class Weather(val place: WeatherPlace) : Page

  /** The 队伍页 (ux-v2 §4.4): its 对话, 队伍信息 over it, 建队 / 加入 (out of a team, or 新建队伍 from the 对话). */
  @Serializable sealed interface Team : Page {
    @Serializable data object Chat : Team
    @Serializable data object Info : Team
    @Serializable data object Join : Team
  }
}

/** How the stack is saved, the activity going and coming back. */
val PagesSerializer = NavBackStackSerializer(NavKeySerializer<NavKey>())

/** [page] on top, one of each kind: one already open comes up from under the others, 天气 for another place replaces it. */
fun MutableList<NavKey>.open(page: Page) {
  removeAll { it::class == page::class }
  add(page)
}

/** 队伍 tapped (or a join through): in a team its 对话, else 建队 / 加入, in place of any 队伍页 open. */
fun MutableList<NavKey>.openTeam(inTeam: Boolean) {
  closeTeam()
  add(if (inTeam) Page.Team.Chat else Page.Team.Join)
}

/** Every 队伍页 closes (退出队伍, a location in the 对话 tapped). */
fun MutableList<NavKey>.closeTeam() {
  removeAll { it is Page.Team }
}

/** Back to the map: every 整页 closes. */
fun MutableList<NavKey>.closePages() {
  while (size > 1) removeAt(lastIndex)
}

/** Each 整页 over everything under it, which stays composed: the map isn't rebuilt, a drawer stays as it was. */
class PageStrategy : SceneStrategy<NavKey> {
  override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? =
    if (entries.size < 2) null else PageScene(entries.last(), entries.dropLast(1), onBack)
}

private class PageScene(
  private val entry: NavEntry<NavKey>,
  override val overlaidEntries: List<NavEntry<NavKey>>,
  private val onBack: () -> Unit,
) : OverlayScene<NavKey> {
  override val key: Any = entry.contentKey
  override val entries = listOf(entry)
  override val previousEntries = overlaidEntries
  override val content: @Composable () -> Unit = {
    // NavDisplay (1.2.0) draws a page opened over another page under it until it's all composed afresh (turning the
    // phone): stacked by depth instead, in a box around NavDisplay of the caller's.
    Box(Modifier.zIndex(overlaidEntries.size.toFloat())) {
      // Added after the map's drawers' handlers, so Back closes the page before the drawer under it; NavDisplay's own
      // handler, added before them, would lose.
      BackHandler(onBack = onBack)
      entry.Content()
    }
  }

  // Not by onBack, a new lambda each time: NavDisplay tells the top page by equality.
  override fun equals(other: Any?) = other is PageScene && entry == other.entry && overlaidEntries == other.overlaidEntries
  override fun hashCode() = entry.hashCode() * 31 + overlaidEntries.hashCode()
}
