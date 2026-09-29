package dev.stars.outdoor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 规划状态首屏 (ux-v2 §3.1): the top bar and the 底栏. The 底栏 is an entry bar, not tabs: the map stays the screen.

private val Red = Color(0xFFE4572E)
private val Green = Color(0xFF2F9E6E)

/** 顶部栏: the search box (opens 搜索) and 图层, which stays on the right whichever hand (§2.2). */
@Composable
fun TopBar(onSearch: () -> Unit, onLayers: () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(
      Modifier.weight(1f).heightIn(min = 56.dp).border(1.5.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(28.dp))
        .background(Color.White, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).clickable(onClick = onSearch).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(R.drawable.search_wght500_24px, null, Modifier.padding(end = 8.dp), tint = Color.Gray)
      BasicText("搜索地点、山峰、坐标", style = TextStyle(color = Color.Gray, fontSize = 16.sp))
    }
    MapIconButton(R.drawable.layers_wght500_24px, "图层", onLayers)
  }
}

/** 标注 (§2.3: icon + label), under 定位 on the 惯用手 side. */
@Composable
fun MarkButton(onClick: () -> Unit) {
  Row(
    Modifier.heightIn(min = 56.dp).border(1.5.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(28.dp))
      .background(Color.White, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(R.drawable.add_location_alt_wght500_24px, null, Modifier.padding(end = 6.dp))
    BasicText("标注", style = TextStyle(fontSize = 16.sp))
  }
}

/**
 * 底栏: 我的轨迹 / 队伍 / 开始 / 离线地图 / 设置. [team] is [teamButton]'s label and red; [unread] adds a dot, no count.
 * Not shown in 活动状态.
 */
@Composable
fun BottomBar(
  team: Pair<String, Boolean>,
  unread: Boolean,
  onTracks: () -> Unit,
  onTeam: () -> Unit,
  onStart: () -> Unit,
  onOffline: () -> Unit,
  onSettings: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    BarItem(R.drawable.route_wght500_24px, "我的轨迹", onClick = onTracks)
    BarItem(R.drawable.group_wght500_24px, team.first, if (team.second) Red else Color.Black, dot = unread, onClick = onTeam)
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      Box(
        Modifier.size(64.dp).background(Green, CircleShape).clip(CircleShape).clickable(onClick = onStart),
        contentAlignment = Alignment.Center,
      ) {
        Icon(R.drawable.play_arrow_wght600fill1_24px, null, tint = Color.White, size = 28.dp)
        BasicText("开始", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), style = TextStyle(color = Color.White, fontSize = 11.sp))
      }
    }
    BarItem(R.drawable.download_for_offline_wght500_24px, "离线地图", onClick = onOffline)
    BarItem(R.drawable.settings_wght500_24px, "设置", onClick = onSettings)
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(
  @DrawableRes icon: Int, label: String, color: Color = Color.Black, dot: Boolean = false, onClick: () -> Unit,
) {
  Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Box {
      Icon(icon, null, tint = color)
      if (dot) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp).size(8.dp).background(Red, CircleShape))
    }
    BasicText(label, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1)
  }
}
