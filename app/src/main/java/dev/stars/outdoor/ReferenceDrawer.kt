package dev.stars.outdoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 参考轨迹抽屉 (ux-v2 §4.3), from the 参考轨迹条: the elevation profile along the track as walked from its 起算点
 * ([stats] of the oriented track), a line at each of [atM]; 正向 / 反向; a loop's 起点; 不再用作参考轨迹.
 */
@Composable
fun ReferenceDrawer(
  name: String,
  stats: TrackStats,
  atM: List<Double>,
  start: TrackStart,
  loop: Boolean,
  onStart: (TrackStart) -> Unit,
  /** 在轨迹上选: the next tap on the track is the new 起点. */
  onPickStart: () -> Unit,
  onStop: () -> Unit,
  onClose: () -> Unit,
) {
  var full by rememberSaveable { mutableStateOf(false) }
  HalfDrawer(full, { full = it }, onClose) {
    Column(Modifier.padding(horizontal = 16.dp)) {
      BasicText(name, style = TextStyle(fontSize = 18.sp))
      BasicText("海拔剖面 · 全长 ${kmText(stats.distanceM)} km", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray))
      ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp), stats.distanceM, ReferenceColor, atM = atM)
      DirectionChips(start.reversed) { onStart(start.copy(reversed = it)) }
      if (loop) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("起点", Modifier.weight(1f))
        BasicText("在轨迹上选", Modifier.heightIn(min = 56.dp).clickable(onClick = onPickStart).padding(horizontal = 12.dp).wrapContentHeight(), style = TextStyle(color = Green))
        if (start.startM > 0) BasicText(
          "恢复",
          Modifier.heightIn(min = 56.dp).clickable { onStart(start.copy(startM = 0.0)) }.padding(horizontal = 12.dp).wrapContentHeight(),
          style = TextStyle(color = Green),
        )
      }
      PrimaryButton("不再用作参考轨迹", enabled = true, onStop, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
  }
}
