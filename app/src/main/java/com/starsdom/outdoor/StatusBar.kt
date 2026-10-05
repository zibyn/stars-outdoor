package com.starsdom.outdoor

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// 状态条 (ux-v3 §6): something that goes on, one at a time, the most pressing.

enum class StatusAction(@StringRes val label: Int) {
  OpenLocation(R.string.action_open_location),
  Terrain(R.string.action_terrain),
  RetrySync(R.string.action_retry),
}

/** One 状态条: its [text] (with [percent] for the 下载 one) and what its button does. */
data class Status(@StringRes val text: Int, val action: StatusAction? = null, val percent: Int? = null)

/**
 * What the 状态条 looks at. [fixAccuracyM] null: no fix; [downloadPercent]: a 离线地图 downloading. Without the
 * permission there's no 状态条 about location (R8): it's asked for when needed.
 */
data class StatusInput(
  val recording: Boolean = false,
  /** Recording paused: the GPS is off on purpose, so nothing about location (#143). */
  val paused: Boolean = false,
  val locationOn: Boolean = true,
  val permitted: Boolean = true,
  /** Only 大致位置 given: its circle is all there is, not a weak fix (§8.1 第 3 条). */
  val precise: Boolean = true,
  val fixAccuracyM: Double? = 5.0,
  val basemap: Basemap = Basemap.Terrain,
  val online: Boolean = true,
  val downloadPercent: Int? = null,
  val syncFailed: Boolean = false,
)

/** The one 状态条 to show, if any: 记录中的定位问题 > 没有网络 > 不记录时的定位问题 > 下载中 > 同步失败. */
fun status(s: StatusInput): Status? {
  val location = when {
    !s.permitted || s.paused -> null
    !s.locationOn -> Status(R.string.status_location_off, StatusAction.OpenLocation)
    s.precise && s.fixAccuracyM.let { it == null || it > POOR_FIX_M } -> Status(R.string.status_weak_fix)
    else -> null
  }
  // C2-126: 卫星 and 标准 need the network; 地形 doesn't.
  val offline = Status(R.string.status_offline, StatusAction.Terrain.takeIf { s.basemap != Basemap.Terrain }).takeIf { !s.online }
  return (location.takeIf { s.recording } ?: offline ?: location)
    ?: s.downloadPercent?.let { Status(R.string.status_downloading, percent = it) }
    ?: Status(R.string.status_sync_failed, StatusAction.RetrySync).takeIf { s.syncFailed }
}

/**
 * The 状态条: grows in and out; a new matter crossfades in (§3.4); read out politely (§4.5). Not shaken (§6).
 * [onAction] runs its button.
 */
@Composable
fun StatusBar(status: Status?, onAction: (StatusAction) -> Unit, modifier: Modifier = Modifier) {
  // Kept while shrinking away, so it doesn't go blank first.
  var last by remember { mutableStateOf(status) }
  if (status != null) last = status
  val motion = MaterialTheme.motionScheme
  AnimatedVisibility(status != null, modifier, expandVertically(motion.defaultSpatialSpec()), shrinkVertically(motion.defaultSpatialSpec())) {
    Floating(Modifier.fillMaxWidth(), MaterialTheme.shapes.small) {
      // Keyed by matter: the 下载 percent just changes.
      AnimatedContent(last, transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) }, contentKey = { it?.text }) { s ->
        if (s != null) StatusLine(s, onAction)
      }
    }
  }
}

@Composable
private fun StatusLine(s: Status, onAction: (StatusAction) -> Unit) = Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Space.M), verticalAlignment = Alignment.CenterVertically) {
  // Read out when the matter changes, not at every percent.
  Text(stringResource(s.text), Modifier.padding(vertical = Space.XS).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
  Text(s.percent?.let { " $it%" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
  s.action?.let { a ->
    Text(
      stringResource(a.label),
      Modifier.heightIn(min = 56.dp).clickable { onAction(a) }.padding(horizontal = Space.M).wrapContentHeight(),
      MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

/** Under a page's top bar (队伍, 对话, 账号, 搜索, 天气): only 没有网络 (§6). */
@Composable
fun OfflineStatus(online: Boolean, modifier: Modifier = Modifier) =
  StatusBar(Status(R.string.status_offline).takeIf { !online }, onAction = {}, modifier.padding(vertical = Space.XS))

/** Under a team page's top bar (对话, 队伍信息): 没有网络, else 重新连接中 once the socket has been down 10 s (§8.4 第 15 条). */
@Composable
fun TeamStatus(online: Boolean, reconnecting: Boolean, modifier: Modifier = Modifier) = StatusBar(
  Status(R.string.status_offline).takeIf { !online } ?: Status(R.string.status_reconnecting).takeIf { reconnecting },
  onAction = {}, modifier.padding(vertical = Space.XS),
)
