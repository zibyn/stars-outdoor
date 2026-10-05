package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 底栏 → 我的轨迹 (整页, ux-v2 §4.1): 轨迹 (finished tracks, and import, §2.6) and 标注 tabs. 标注 lists the
 * 标注组 (#121), then the 标注 in none ([waypoints]), each with its 叠加.
 */
@Composable
fun TrackListScreen(
  tracks: List<TrackSummary>,
  groups: List<WaypointGroup>,
  waypoints: List<Waypoint>,
  importing: Boolean,
  onOpen: (Long) -> Unit,
  onWaypoint: (Waypoint) -> Unit,
  onImport: () -> Unit,
  /** 叠加 (ux-v2 §9.2): track id → its place in the order overlaid ([Semantic.overlay]). */
  overlays: Map<Long, Int>,
  onOverlay: (Long) -> Unit,
  onClearOverlays: () -> Unit,
  onGroup: (Long) -> Unit,
  onGroupShown: (WaypointGroup) -> Unit,
  onWaypointShown: (Waypoint) -> Unit,
  /** 新建标注组; false if the name is taken. */
  onNewGroup: (String) -> Boolean,
) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  Page(Modifier.padding(16.dp)) {
    Text("我的轨迹", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth()) {
      for ((i, label) in listOf("轨迹", "标注").withIndex()) Text(
        label,
        Modifier.weight(1f).heightIn(min = 56.dp).selectable(tab == i, role = Role.Tab) { tab = i }.wrapContentHeight(),
        if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
      )
    }
    if (tab == 0) {
      if (overlays.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已叠加 ${overlays.size} 条", Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
        // Clears at once, no undo (ux-v2 §9.2).
        Text("全部取消", Modifier.heightIn(min = 56.dp).clickable(onClick = onClearOverlays).padding(horizontal = 12.dp).wrapContentHeight(), MaterialTheme.colorScheme.primary)
      }
      if (tracks.isEmpty()) Text("还没有轨迹。开始记录，或导入轨迹文件", Modifier.padding(vertical = 16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
      LazyColumn(Modifier.weight(1f)) {
        items(tracks, key = { it.id }) { t ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.name + if (t.planned) "（计划）" else "", Modifier.weight(1f).heightIn(min = 56.dp).clickable { onOpen(t.id) }.wrapContentHeight())
            val color = overlays[t.id]?.let { semantic.overlay(it) }
            OverlayToggle(color != null, color ?: MaterialTheme.colorScheme.onSurfaceVariant) { onOverlay(t.id) }
          }
        }
      }
      PrimaryButton(if (importing) "正在导入…" else "导入轨迹文件", enabled = !importing, onImport)
      Text("支持 GPX、KML、FIT、GeoJSON、PLT", Modifier.fillMaxWidth().padding(top = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    } else LazyColumn(Modifier.weight(1f)) {
      item {
        var naming by rememberSaveable { mutableStateOf(false) }
        if (naming) NameEntry("", "建立") { if (onNewGroup(it)) naming = false }
        else Text("新建标注组", Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { naming = true }.wrapContentHeight(), MaterialTheme.colorScheme.primary)
      }
      items(groups, key = { "g${it.id}" }) { g ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("${g.name}（${g.count} 个）", Modifier.weight(1f).heightIn(min = 56.dp).clickable { onGroup(g.id) }.wrapContentHeight())
          OverlayToggle(g.shown) { onGroupShown(g) }
        }
      }
      items(waypoints, key = { "w${it.id}" }) { w ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          WaypointRow(w, Modifier.weight(1f)) { onWaypoint(w) }
          OverlayToggle(w.shown) { onWaypointShown(w) }
        }
      }
    }
  }
}

@Composable
internal fun WaypointRow(w: Waypoint, modifier: Modifier, onClick: () -> Unit) {
  Column(modifier.heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
    Text(w.name.ifBlank { "未命名标注" })
    // Imported 标注 may have no time.
    if (w.timeMs != 0L) Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(w.timeMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  }
}

/** 叠加 switch at a row's end; [onColor] when on (a track's palette colour). */
@Composable
private fun OverlayToggle(on: Boolean, onColor: Color = MaterialTheme.colorScheme.primary, onToggle: () -> Unit) = Text(
  if (on) "已叠加" else "叠加",
  Modifier.heightIn(min = 56.dp).widthIn(min = 72.dp).toggleable(on, role = Role.Switch) { onToggle() }.wrapContentHeight(),
  if (on) onColor else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
)

/** A file with several tracks: the user ticks which to import (§2.6). */
@Composable
fun ImportPickScreen(fileName: String, tracks: List<ParsedTrack>, checked: Set<Int>, onToggle: (Int) -> Unit, onImport: () -> Unit) {
  Page(Modifier.padding(16.dp)) {
    Text("导入 $fileName", style = MaterialTheme.typography.titleLarge)
    Text("文件中有 ${tracks.size} 条轨迹，选择要导入的", Modifier.padding(vertical = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.weight(1f)) {
      itemsIndexed(tracks) { i, t ->
        Row(Modifier.fillMaxWidth().toggleable(i in checked, role = Role.Checkbox) { onToggle(i) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(if (i in checked) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, Modifier.padding(end = 12.dp))
          Column {
            Text(importName(t, fileName, i, tracks.size) + if (t.planned) "（计划）" else "")
            Text("${t.segments.sumOf { it.size }} 个点", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
    PrimaryButton("导入 ${checked.size} 条", enabled = checked.isNotEmpty(), onImport)
  }
}

/** The file name; with several tracks, plus the track's own name or its number. Names inside files are mostly auto-generated (#123). */
fun importName(t: ParsedTrack, fileName: String, index: Int, count: Int): String {
  val base = fileName.substringBeforeLast('.')
  return when {
    count == 1 -> base
    t.name.isBlank() -> "$base ${index + 1}"
    else -> "$base · ${t.name}"
  }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
  val c = MaterialTheme.colorScheme
  // Disabled as M3: onSurface at 12 % behind, 38 % on top.
  Text(
    text,
    modifier.background(if (enabled) c.primary else c.onSurface.copy(alpha = 0.12f), CircleShape).clip(CircleShape)
      .clickable(enabled = enabled, onClick = onClick).padding(16.dp),
    if (enabled) c.onPrimary else c.onSurface.copy(alpha = 0.38f), textAlign = TextAlign.Center,
  )
}
