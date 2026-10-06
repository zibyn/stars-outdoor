package com.starsdom.trail.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.map.MapIconButton
import com.starsdom.trail.map.MarkIcon
import com.starsdom.trail.recording.HoldKey
import com.starsdom.trail.team.unread

// 主界面 (ux-v3 §5.1): one layout, recording or not. The 底栏 is an entry bar, not tabs: the map stays the screen.

/** 顶部: the 搜索 capsule (C1-13), [weather] (§2.9) and 图层; the same whichever hand (§5.4). */
@Composable
fun TopBar(onSearch: () -> Unit, onLayers: () -> Unit, weather: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
    Floating(Modifier.weight(1f).heightIn(min = 56.dp), CircleShape) {
      Row(Modifier.clickable(onClick = onSearch).padding(horizontal = Space.L), verticalAlignment = Alignment.CenterVertically) {
        Icon(R.drawable.search_wght500_24px, null)
        Text(stringResource(R.string.search_places), Modifier.padding(start = Space.M), MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
    }
    weather()
    MapIconButton(R.drawable.layers_wght500_24px, stringResource(R.string.layers), onLayers)
  }
}

/** 「⊕」 标注 (§5.4, C3-30): an icon key like its neighbours; while [waiting] for a fix it turns (§8.3 第 12 条). */
@Composable
fun MarkKey(waiting: Boolean, onClick: () -> Unit) = MapIconButton(onClick) { MarkIcon(waiting) }

/**
 * 底栏 (§5.2): 我的轨迹 / 队伍 / 开始 / 离线地图 / 设置, recording or not. 队伍 has a dot when [unread], no count
 * (C1-09); 设置 one when there's an [update] (§2.13). [recording], 开始 is 暂停 ([onStart] does either).
 */
@Composable
fun BottomBar(
  unread: Boolean,
  update: Boolean,
  recording: Boolean,
  onTracks: () -> Unit,
  onTeam: () -> Unit,
  onStart: () -> Unit,
  onOffline: () -> Unit,
  onSettings: () -> Unit,
) {
  Surface(Modifier.fillMaxWidth().hintAnchor(), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = Space.XXS), verticalAlignment = Alignment.CenterVertically) {
      BarItem(R.drawable.route_wght500_24px, stringResource(R.string.bar_tracks), onClick = onTracks)
      BarItem(R.drawable.group_wght500_24px, stringResource(R.string.bar_team), dotLabel = stringResource(R.string.bar_team_unread).takeIf { unread }, onClick = onTeam)
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StartKey(recording, onStart) }
      BarItem(R.drawable.download_for_offline_wght500_24px, stringResource(R.string.bar_offline), onClick = onOffline)
      BarItem(R.drawable.settings_wght500_24px, stringResource(R.string.bar_settings), dotLabel = stringResource(R.string.bar_settings_update).takeIf { update }, onClick = onSettings)
    }
  }
}

/**
 * ▶, or ⏸ while [recording]: the change is one of the two 表现力时刻 (§3.3), the circle squaring off on the
 * expressive spring as the icon crosses over. With 移除动画 on it just swaps.
 */
@Composable
private fun StartKey(recording: Boolean, onClick: () -> Unit) {
  val motion = MotionScheme.expressive()
  val corner by animateDpAsState(if (recording) 20.dp else 32.dp, motion.defaultSpatialSpec())
  val shape = RoundedCornerShape(corner)
  val label = stringResource(if (recording) R.string.pause_recording else R.string.start_recording)
  Box(
    Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, shape).clip(shape).clickable(onClickLabel = label, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Crossfade(recording, animationSpec = motion.defaultEffectsSpec()) { r ->
      Icon(if (r) R.drawable.pause_wght600fill1_24px else R.drawable.play_arrow_wght600fill1_24px, null, tint = MaterialTheme.colorScheme.onPrimary, size = 36.dp)
    }
  }
}

/** [dotLabel]: a red dot, TalkBack reading it after [label]. */
@Composable
private fun RowScope.BarItem(@DrawableRes icon: Int, label: String, dotLabel: String? = null, onClick: () -> Unit) {
  Column(
    Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onClick).clearAndSetSemantics { contentDescription = if (dotLabel != null) "$label，$dotLabel" else label },
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
  ) {
    Box {
      Icon(icon, null)
      if (dotLabel != null) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
    }
    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
  }
}

/**
 * 暂停小栏 (§5.2): 「▶ 继续」 and 「■ 按住结束」 (held 1 s), 16 dp apart for gloves (§4.2); [leftHanded] swaps them.
 * Up until answered. TalkBack's double-tap ends it ([HoldKey]).
 */
@Composable
fun PauseBar(leftHanded: Boolean, onResume: () -> Unit, onEnd: () -> Unit, onEndTooShort: () -> Unit, modifier: Modifier = Modifier) {
  val keys: List<@Composable RowScope.() -> Unit> = listOf(
    {
      Surface(Modifier.weight(1f).heightIn(min = 56.dp), CircleShape, MaterialTheme.colorScheme.primary) {
        Row(Modifier.clickable(onClick = onResume), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
          Icon(R.drawable.play_arrow_wght600fill1_24px, null)
          Text(stringResource(R.string.resume), Modifier.padding(start = Space.XS), style = MaterialTheme.typography.labelLarge)
        }
      }
    },
    {
      HoldKey(
        R.drawable.stop_wght600fill1_24px, stringResource(R.string.hold_to_end), stringResource(R.string.end_recording), 1000,
        MaterialTheme.colorScheme.inverseSurface, Modifier.weight(1f), onEndTooShort, onEnd,
      )
    },
  )
  Row(modifier.fillMaxWidth().padding(horizontal = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.L)) {
    for (key in if (leftHanded) keys.reversed() else keys) key()
  }
}
