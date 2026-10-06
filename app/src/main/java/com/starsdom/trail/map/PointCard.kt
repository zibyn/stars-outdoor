package com.starsdom.trail.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.search.Place
import com.starsdom.trail.track.DrawerIconButton
import com.starsdom.trail.track.distanceValue
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Floating
import com.starsdom.trail.ui.Icon
import com.starsdom.trail.ui.Sheet
import com.starsdom.trail.ui.Space
import java.util.Locale

fun coordinateText(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

/** C2-11: a place's title, its 地名, else the coordinate. */
fun placeTitle(place: Place?, lat: Double, lon: Double): String = place?.name ?: coordinateText(lat, lon)

/**
 * 地点小抽屉 (§8.2 第 3 条, C2-11…13) and 我的位置's (C2-14): [title], a small [line] under it, [actions] in a row (the
 * first filled), and [menu] behind ⋮.
 */
@Composable
fun PlaceSheet(
  title: String,
  line: String?,
  actions: List<Pair<String, () -> Unit>>,
  modifier: Modifier,
  menu: List<Pair<String, () -> Unit>> = emptyList(),
) = Sheet(modifier) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
      line?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
    if (menu.isNotEmpty()) Box {
      var open by remember { mutableStateOf(false) }
      DrawerIconButton(R.drawable.more_vert_wght500_24px, stringResource(R.string.more)) { open = true }
      DropdownMenu(open, { open = false }) {
        for ((label, action) in menu) DropdownMenuItem({ Text(label) }, { open = false; action() }, Modifier.heightIn(min = 48.dp))
      }
    }
  }
  Row(Modifier.fillMaxWidth().padding(top = Space.M), Arrangement.spacedBy(Space.XS)) {
    actions.forEachIndexed { i, (label, onClick) ->
      val m = Modifier.weight(1f).heightIn(min = 48.dp)
      if (i == 0) Button(onClick, m) { Text(label, textAlign = TextAlign.Center) }
      else OutlinedButton(onClick, m) { Text(label, textAlign = TextAlign.Center) }
    }
  }
}

/** 测距 (C2-17…19): 「↔ 点地图选终点」, then 「↔ 1.3 km」 (read 「直线 1.3 公里」); ✕ ends it. */
@Composable
fun MeasureBanner(distanceM: Double?, onClose: () -> Unit, modifier: Modifier) {
  Floating(modifier, MaterialTheme.shapes.medium) {
    Row(Modifier.padding(start = Space.L), verticalAlignment = Alignment.CenterVertically) {
      val text = distanceM?.let { "↔ " + distanceValue(it) } ?: stringResource(R.string.measure_pick)
      val spoken = distanceM?.let { m ->
        val v = distanceValue(m)
        val said = if (v.endsWith(" km")) stringResource(R.string.speech_km, v.removeSuffix(" km")) else stringResource(R.string.speech_m, v.removeSuffix(" m"))
        stringResource(R.string.measure_spoken, said)
      }
      Text(text, Modifier.weight(1f).semantics { spoken?.let { contentDescription = it } })
      Box(Modifier.size(48.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(R.drawable.close_wght500_24px, stringResource(R.string.measure_close))
      }
    }
  }
}
