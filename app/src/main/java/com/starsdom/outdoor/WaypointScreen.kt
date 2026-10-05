package com.starsdom.outdoor

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// 标注 in the 我的轨迹 drawer (ux-v3 §8.5 第 10–13 条): the 标注 页签, a 标注组's list, and editing one.

/** A row with a background while it's [highlighted]: just made, or back from 撤销 (§8.5 第 12、15 条). */
@Composable
internal fun Modifier.highlight(highlighted: Boolean) =
  if (highlighted) background(MaterialTheme.colorScheme.secondaryContainer) else this

/** ← [title] ⋮ at the top of a drawer page (C5-16, C5-28); the ⋮ only with [menu] items. */
@Composable
internal fun DrawerHeader(title: String, onBack: () -> Unit, menu: List<Pair<String, () -> Unit>> = emptyList()) =
  Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS), verticalAlignment = Alignment.CenterVertically) {
    DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onBack)
    Text(title, Modifier.weight(1f).padding(horizontal = Space.XXS), style = MaterialTheme.typography.titleMedium)
    MoreMenu(menu)
  }

/** A ⋮ opening [menu]; none without items. */
@Composable
internal fun MoreMenu(menu: List<Pair<String, () -> Unit>>) {
  if (menu.isNotEmpty()) Box {
    var open by remember { mutableStateOf(false) }
    DrawerIconButton(R.drawable.more_vert_wght500_24px, stringResource(R.string.more)) { open = true }
    DropdownMenu(open, { open = false }) {
      for ((label, action) in menu) DropdownMenuItem({ Text(label) }, { open = false; action() }, Modifier.heightIn(min = 48.dp))
    }
  }
}

/**
 * A two-line row: [icon] (read as nothing: the name says it), [title] and [line]; tapped [onClick], long-pressed the same.
 * With [onShown], its 叠加 switch at the end ([shown] in the 标注 dot's colour).
 */
@Composable
private fun TwoLineRow(
  @DrawableRes icon: Int, title: String, line: String, highlighted: Boolean, onClick: () -> Unit, shown: Boolean = false, onShown: (() -> Unit)? = null,
) = Row(Modifier.fillMaxWidth().highlight(highlighted).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
  Icon(icon, null, Modifier.padding(start = Space.L), MaterialTheme.colorScheme.onSurfaceVariant)
  Column(Modifier.weight(1f).heightIn(min = 56.dp).padding(horizontal = Space.M, vertical = Space.XS), Arrangement.Center) {
    Text(title)
    if (line.isNotEmpty()) Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
  }
  if (onShown != null) OverlayToggle(semantic.warn.takeIf { shown }, onShown)
}

/** A 标注's name, or 「未命名标注」 (C5-14). */
@Composable
internal fun waypointName(w: Waypoint) = w.name.ifBlank { stringResource(R.string.unnamed_waypoint) }

/**
 * The 标注 页签 (C5-12…15): 标注组 first (📁 name, 「12 个」, 叠加), then under 「不在组里」 the 标注 in none
 * ([loose]: 📍 name, 「8月10日 · 海拔 2600 m」, 叠加). Empty: 「长按地图就能标注」 and 回地图.
 */
@Composable
fun ColumnScope.WaypointTab(
  groups: List<WaypointGroup>,
  loose: List<Waypoint>,
  now: Long,
  highlighted: String?,
  onGroup: (Long) -> Unit,
  onGroupShown: (WaypointGroup) -> Unit,
  onWaypoint: (Waypoint) -> Unit,
  onWaypointShown: (Waypoint) -> Unit,
  onBackToMap: () -> Unit,
) {
  if (groups.isEmpty() && loose.isEmpty()) return EmptyState(
    R.drawable.location_on_wght500_24px, stringResource(R.string.waypoints_empty), stringResource(R.string.back_to_map), onBackToMap,
  )
  LazyColumn(Modifier.weight(1f)) {
    items(groups, key = { "g${it.id}" }) { g ->
      TwoLineRow(R.drawable.folder_wght500_24px, g.name, stringResource(R.string.waypoint_count_short, g.count), highlighted == "g${g.id}", { onGroup(g.id) }, g.shown) { onGroupShown(g) }
    }
    if (loose.isNotEmpty()) item {
      Text(
        stringResource(R.string.not_in_group), Modifier.padding(start = Space.L, top = Space.M, bottom = Space.XXS),
        MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
      )
    }
    items(loose, key = { "w${it.id}" }) { w ->
      TwoLineRow(R.drawable.location_on_wght500_24px, waypointName(w), waypointLine(w, now), highlighted == "w${w.id}", { onWaypoint(w) }, w.shown) { onWaypointShown(w) }
    }
  }
}

/** §7.1 空: an icon, one line and a button for what's next. */
@Composable
internal fun ColumnScope.EmptyState(@DrawableRes icon: Int, text: String, action: String, onAction: () -> Unit, small: String? = null) = Column(
  Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.L),
  Arrangement.Center, Alignment.CenterHorizontally,
) {
  Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 48.dp)
  Text(text, Modifier.padding(top = Space.M), style = MaterialTheme.typography.titleMedium)
  PrimaryButton(action, enabled = true, onAction, Modifier.padding(top = Space.L).widthIn(min = 160.dp))
  small?.let { Text(it, Modifier.padding(top = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
}

/** A 标注组's list (C5-28, C5-29): ← name ⋮ (改名, 导出, 删除), how many, its 标注. */
@Composable
fun ColumnScope.GroupPage(
  group: WaypointGroup,
  waypoints: List<Waypoint>,
  now: Long,
  highlighted: String?,
  onBack: () -> Unit,
  onWaypoint: (Waypoint) -> Unit,
  onRename: () -> Unit,
  onExport: () -> Unit,
  onDelete: () -> Unit,
) {
  DrawerHeader(group.name, onBack, listOf(stringResource(R.string.rename) to onRename, stringResource(R.string.export) to onExport, stringResource(R.string.delete) to onDelete))
  Text(stringResource(R.string.waypoint_count, group.count), Modifier.padding(horizontal = Space.L), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
  LazyColumn(Modifier.weight(1f)) {
    items(waypoints, key = { it.id }) { w -> TwoLineRow(R.drawable.location_on_wght500_24px, waypointName(w), waypointLine(w, now), highlighted == "w${w.id}", { onWaypoint(w) }) }
  }
}

/**
 * Editing a 标注 (C5-16…26): ← name ⋮ (删除); 「海拔 2600 m · 8月10日」; 名称 and 描述, saved as they change (no 完成);
 * its 所属组 if it's not a track's ([groups] null), moved at once, or into a 新建标注组; the photo; 下载附近.
 */
@Composable
fun ColumnScope.WaypointEditor(
  waypoint: Waypoint,
  name: String,
  description: String,
  now: Long,
  onName: (String) -> Unit,
  onDescription: (String) -> Unit,
  groups: List<WaypointGroup>?,
  onGroup: (Long?) -> Unit,
  onNewGroup: () -> Unit,
  onPickPhoto: () -> Unit,
  /** 下载附近 (§2.3), around this 标注. */
  onDownload: () -> Unit,
  onBack: () -> Unit,
  onDelete: () -> Unit,
) {
  DrawerHeader(name.ifBlank { stringResource(R.string.unnamed_waypoint) }, onBack, listOf(stringResource(R.string.delete) to onDelete))
  Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.L, end = Space.L, bottom = Space.L)) {
    waypointLine(waypoint, now, eleFirst = true).takeIf { it.isNotEmpty() }?.let {
      Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(name, onName, Modifier.fillMaxWidth().padding(top = Space.XS), label = { Text(stringResource(R.string.name)) }, singleLine = true)
    OutlinedTextField(description, onDescription, Modifier.fillMaxWidth().padding(top = Space.XS), label = { Text(stringResource(R.string.description)) }, minLines = 3)
    if (groups != null) GroupPicker(groups, waypoint.groupId, onGroup, onNewGroup)
    val photo = remember(waypoint.photo) { waypoint.photo?.let(::loadThumbnail) }
    if (photo != null) Image(
      photo.asImageBitmap(), stringResource(R.string.photo), Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = Space.M), contentScale = ContentScale.Fit,
    )
    Row(Modifier.fillMaxWidth().padding(top = Space.M), Arrangement.spacedBy(Space.XS)) {
      OutlinedButton(onPickPhoto, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(if (photo == null) R.string.add_photo else R.string.change_photo)) }
      OutlinedButton(onDownload, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.download_nearby)) }
    }
  }
}

/** 所属组 (C5-24…26): where it is, a tap opening the choices. */
@Composable
private fun GroupPicker(groups: List<WaypointGroup>, current: Long?, onGroup: (Long?) -> Unit, onNewGroup: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box(Modifier.padding(top = Space.M)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.in_group), Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
      Text(groups.firstOrNull { it.id == current }?.name ?: stringResource(R.string.not_in_group))
    }
    DropdownMenu(open, { open = false }) {
      DropdownMenuItem({ Text(stringResource(R.string.not_in_group)) }, { open = false; onGroup(null) }, Modifier.heightIn(min = 48.dp))
      for (g in groups) DropdownMenuItem({ Text(g.name) }, { open = false; onGroup(g.id) }, Modifier.heightIn(min = 48.dp))
      DropdownMenuItem({ Text(stringResource(R.string.new_group)) }, { open = false; onNewGroup() }, Modifier.heightIn(min = 48.dp))
    }
  }
}

/** 叠加 switch at a row's end (R12): a hollow box, or one filled in [on]'s colour with a check. */
@Composable
internal fun OverlayToggle(on: Color?, onToggle: () -> Unit) {
  val label = stringResource(R.string.overlay)
  Box(
    Modifier.size(56.dp).toggleable(on != null, role = Role.Checkbox) { onToggle() }.semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) { Icon(if (on != null) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, tint = on ?: MaterialTheme.colorScheme.onSurfaceVariant) }
}

// ponytail: ignores EXIF rotation; honour it when portrait photos show sideways.
private fun loadThumbnail(path: String) = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
  BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
