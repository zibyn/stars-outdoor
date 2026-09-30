package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** §2.2 layer drawer: the three basemap cards, then the overlay switches. */
@Composable
fun LayerSheet(
  basemap: Basemap,
  overseas: Boolean,
  contours: Boolean,
  hillshade: Boolean,
  tilted: Boolean,
  nearby: Boolean,
  /** 尾迹 on or off; null hides the switch (not in a team). */
  trails: Boolean?,
  /** Tracks 叠加 (ux-v2 §9.2); the row opens 我的轨迹, where they're chosen. */
  overlaid: Int,
  onTracks: () -> Unit,
  onBasemap: (Basemap) -> Unit,
  onContours: () -> Unit,
  onHillshade: () -> Unit,
  onTilt: () -> Unit,
  onNearby: () -> Unit,
  onTrails: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (b in Basemap.entries) Column(
        Modifier.weight(1f).heightIn(min = 72.dp)
          .border(if (b == basemap) 2.dp else 1.dp, if (b == basemap) Green else Color.LightGray, RoundedCornerShape(8.dp))
          .clickable { onBasemap(b) }.padding(12.dp),
      ) {
        BasicText(b.label, style = TextStyle(fontSize = 16.sp))
        BasicText(b.offlineNote, style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        if (b == Basemap.Satellite && overseas) BasicText("海外影像精度有限", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      }
    }
    Switch("等高线", contours, onContours)
    Switch("山体阴影", hillshade, onHillshade)
    Switch("3D 地形", tilted, onTilt)
    Switch("周边路网", nearby, onNearby)
    trails?.let { Switch("队友尾迹", it, onTrails) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onTracks), verticalAlignment = Alignment.CenterVertically) {
      BasicText("我的轨迹", Modifier.weight(1f), style = TextStyle(fontSize = 16.sp))
      BasicText("已叠加 $overlaid 条", style = TextStyle(color = Color.Gray, fontSize = 16.sp))
    }
  }
}

internal val Green = Color(0xFF2F9E6E)
/** Warnings that must stand out: 出行提醒, 失联, 求助, low battery. */
internal val AlertRed = Color(0xFFC62828)

@Composable
internal fun Switch(label: String, on: Boolean, onClick: () -> Unit) {
  // Whole row is the target: ≥ 56 dp for gloves (§1.3).
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
    BasicText(label, Modifier.weight(1f), style = TextStyle(fontSize = 16.sp))
    BasicText(if (on) "开" else "关", style = TextStyle(color = if (on) Green else Color.Gray, fontSize = 16.sp))
  }
}
