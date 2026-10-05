package com.starsdom.outdoor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// 规划状态首屏 (ux-v2 §3.1): the top bar and the 底栏. The 底栏 is an entry bar, not tabs: the map stays the screen.

/** 顶部栏: 搜索 (opens 搜索), [weather] (§2.9) and 图层, icons only, on the right whichever hand (§2.2). */
@Composable
fun TopBar(onSearch: () -> Unit, onLayers: () -> Unit, weather: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
    MapIconButton(R.drawable.search_wght500_24px, "搜索", onSearch)
    weather()
    MapIconButton(R.drawable.layers_wght500_24px, "图层", onLayers)
  }
}

/** 标注 (§2.3, #122: icon only), under 定位 on the 惯用手 side; the icon turns while [waiting] for a fix. */
@Composable
fun MarkButton(waiting: Boolean, onClick: () -> Unit) = MapIconButton(onClick) { MarkIcon(waiting) }

/**
 * 底栏: 我的轨迹 / 队伍 / 开始 / 离线地图 / 设置. [team] is [teamButton]'s label; [unread] adds a dot, no count.
 * Not shown in 活动状态.
 */
@Composable
fun BottomBar(
  team: String,
  unread: Boolean,
  onTracks: () -> Unit,
  onTeam: () -> Unit,
  onStart: () -> Unit,
  onOffline: () -> Unit,
  onSettings: () -> Unit,
) {
  Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      BarItem(R.drawable.route_wght500_24px, "我的轨迹", onClick = onTracks)
      BarItem(R.drawable.group_wght500_24px, team, dot = unread, onClick = onTeam)
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Box(
          Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clip(CircleShape).clickable(onClick = onStart),
          contentAlignment = Alignment.Center,
        ) {
          Icon(R.drawable.play_arrow_wght600fill1_24px, "开始", tint = MaterialTheme.colorScheme.onPrimary, size = 36.dp)
        }
      }
      BarItem(R.drawable.download_for_offline_wght500_24px, "离线地图", onClick = onOffline)
      BarItem(R.drawable.settings_wght500_24px, "设置", onClick = onSettings)
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(
  @DrawableRes icon: Int, label: String, dot: Boolean = false, onClick: () -> Unit,
) {
  Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Box {
      Icon(icon, null)
      if (dot) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
    }
    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
  }
}
