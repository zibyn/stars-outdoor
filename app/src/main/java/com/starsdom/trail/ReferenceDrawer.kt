package com.starsdom.trail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.starsdom.trail.track.TrackStart
import com.starsdom.trail.track.TrackStats

/**
 * 参考轨迹抽屉 (ux-v2 §4.3), from the 参考 窄条 (ux-v3 §5.3): the elevation profile along the track as walked from its 起算点
 * ([stats] of the oriented track), a line at each of [atM]; 正向 / 反向; a loop's 起点; 取消参考 (C2-82, C2-83).
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
      Text(name, style = MaterialTheme.typography.titleLarge)
      ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp), stats.distanceM, semantic.reference, atM = atM)
      DirectionRow(start.reversed) { onStart(start.copy(reversed = it)) }
      if (loop) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("起点", Modifier.weight(1f))
        Text(stringResource(R.string.change_start), Modifier.heightIn(min = 56.dp).clickable(onClick = onPickStart).padding(horizontal = 12.dp).wrapContentHeight(), MaterialTheme.colorScheme.primary)
        if (start.startM > 0) Text(
          stringResource(R.string.restore),
          Modifier.heightIn(min = 56.dp).clickable { onStart(start.copy(startM = 0.0)) }.padding(horizontal = 12.dp).wrapContentHeight(),
          MaterialTheme.colorScheme.primary,
        )
      }
      PrimaryButton(stringResource(R.string.stop_reference), enabled = true, onStop, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
  }
}
