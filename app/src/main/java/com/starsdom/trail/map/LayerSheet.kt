package com.starsdom.trail.map

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Sheet
import com.starsdom.trail.ui.Space

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
  /** Tracks 叠加 (ux-v3 §5.5): with any, a row to take them all off ([onClearOverlays]). */
  overlaid: Int,
  onClearOverlays: () -> Unit,
  onBasemap: (Basemap) -> Unit,
  onContours: () -> Unit,
  onHillshade: () -> Unit,
  onTilt: () -> Unit,
  onNearby: () -> Unit,
  onTrails: () -> Unit,
  modifier: Modifier,
) {
  Sheet(modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (b in Basemap.entries) Column(
        Modifier.weight(1f).heightIn(min = 72.dp)
          .border(if (b == basemap) 2.dp else 1.dp, if (b == basemap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
          .clickable { onBasemap(b) }.padding(12.dp),
      ) {
        Text(b.label)
        Text(b.offlineNote, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        if (b == Basemap.Satellite && overseas) Text("海外影像精度有限", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      }
    }
    Switch("等高线", contours, onClick = onContours)
    Switch("山体阴影", hillshade, onClick = onHillshade)
    Switch("3D 地形", tilted, onClick = onTilt)
    Switch("周边路网", nearby, onClick = onNearby)
    trails?.let { Switch("队友尾迹", it, onClick = onTrails) }
    // C2-125: none, no row.
    if (overlaid > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.overlaid_count, overlaid), Modifier.weight(1f))
      Text(
        stringResource(R.string.clear_overlays),
        Modifier.heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClearOverlays).padding(horizontal = Space.M).wrapContentHeight(),
        MaterialTheme.colorScheme.primary,
      )
    }
  }
}

/** A switch row; the M3 switch says on or off itself (C2-124). */
@Composable
internal fun Switch(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  // Whole row is the target: ≥ 56 dp for gloves (§1.3).
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, enabled, Role.Switch) { onClick() }, verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    M3Switch(on, null, enabled = enabled)
  }
}
