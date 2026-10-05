package com.starsdom.outdoor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Edit a 标注: name, description, photo (§2.4), held by the caller and saved when it closes; and its 标注组 (#121),
 * moved at once. A track's 标注 has no [groups] (null) and no 标注组 row.
 */
@Composable
fun WaypointScreen(
  waypoint: Waypoint,
  name: String,
  description: String,
  onName: (String) -> Unit,
  onDescription: (String) -> Unit,
  groups: List<WaypointGroup>?,
  onGroup: (Long?) -> Unit,
  /** 新建标注组… and move there; false if the name is taken. */
  onNewGroup: (String) -> Boolean,
  onPickPhoto: () -> Unit,
  onDelete: () -> Unit,
  /** 下载这附近 (§2.3), around this 标注. */
  onDownload: () -> Unit,
  onDone: () -> Unit,
) {
  Page(Modifier.padding(16.dp)) {
    Text("标注", style = MaterialTheme.typography.titleLarge)
    Text(
      // Imported 标注 may have no time.
      (if (waypoint.timeMs != 0L) SimpleDateFormat("yyyy-MM-dd HH:mm  ", Locale.ROOT).format(Date(waypoint.timeMs)) else "") +
        String.format(Locale.ROOT, "%.5f, %.5f", waypoint.lat, waypoint.lon) + (waypoint.ele?.let { "  ${Math.round(it)} m" } ?: ""),
      color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
    )
    Field("名称", name, onName, singleLine = true)
    Field("描述", description, onDescription, singleLine = false)
    if (groups != null) GroupPicker(groups, waypoint.groupId, onGroup, onNewGroup)
    val photo = remember(waypoint.photo) { waypoint.photo?.let(::loadThumbnail) }
    if (photo != null) {
      Image(photo.asImageBitmap(), "照片", Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 16.dp), contentScale = ContentScale.Fit)
    }
    Spacer(Modifier.weight(1f))
    Button("下载这附近", primary = false, onDownload, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      // ux-v2 §6.3: only a second tap within 3 s deletes.
      TapAgain("删除", "再点一次删除", Modifier.weight(1f), onDelete)
      Button(if (photo == null) "添加照片" else "更换照片", primary = false, onPickPhoto, Modifier.weight(1f))
      Button("完成", primary = true, onDone, Modifier.weight(1f))
    }
  }
}

@Composable
private fun GroupPicker(groups: List<WaypointGroup>, current: Long?, onGroup: (Long?) -> Unit, onNewGroup: (String) -> Boolean) {
  var open by rememberSaveable { mutableStateOf(false) }
  var naming by rememberSaveable { mutableStateOf(false) }
  Text("标注组", Modifier.padding(top = 16.dp, bottom = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant)
  if (!open) return PickRow(groups.firstOrNull { it.id == current }?.name ?: "不分组") { open = true }
  PickRow("不分组") { onGroup(null); open = false }
  for (g in groups) PickRow(g.name) { onGroup(g.id); open = false }
  if (naming) NameEntry("", "建立") { if (onNewGroup(it)) { naming = false; open = false } }
  else PickRow("新建标注组…") { naming = true }
}

@Composable
private fun PickRow(label: String, onClick: () -> Unit) =
  Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).wrapContentHeight())

/** A name typed in place, handed to [onSave] by the [action] button unless blank. */
@Composable
internal fun NameEntry(initial: String, action: String, onSave: (String) -> Unit) {
  var draft by rememberSaveable { mutableStateOf(initial) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicTextField(
      draft, { draft = it }, Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(8.dp),
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
    Button(action, primary = true, { draft.trim().takeIf { it.isNotEmpty() }?.let(onSave) }, Modifier)
  }
}

/** A 标注组's page (#121): its 标注, 改名, and 删除 with them all (再点一次, ux-v2 §6.3; an empty one at once). */
@Composable
fun WaypointGroupScreen(
  group: WaypointGroup,
  waypoints: List<Waypoint>,
  onWaypoint: (Waypoint) -> Unit,
  /** False if the name is taken. */
  onRename: (String) -> Boolean,
  onDelete: () -> Unit,
) {
  Page(Modifier.padding(16.dp)) {
    var renaming by rememberSaveable(group.id) { mutableStateOf(false) }
    if (renaming) NameEntry(group.name, "保存") { if (onRename(it)) renaming = false }
    else Text(group.name, style = MaterialTheme.typography.titleLarge)
    Text("${group.count} 个标注", Modifier.padding(bottom = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    LazyColumn(Modifier.weight(1f)) {
      items(waypoints, key = { it.id }) { w -> WaypointRow(w, Modifier.fillMaxWidth()) { onWaypoint(w) } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      if (group.count == 0) Text("删除", Modifier.heightIn(min = 56.dp).clickable(onClick = onDelete).padding(horizontal = 12.dp).wrapContentHeight(), MaterialTheme.colorScheme.error)
      else TapAgain("删除", "再点一次删除 ${group.count} 个标注", Modifier, onDelete)
      Spacer(Modifier.weight(1f))
      Button("改名", primary = false, { renaming = true }, Modifier)
    }
  }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean) {
  Text(label, Modifier.padding(top = 16.dp, bottom = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant)
  BasicTextField(
    value, onChange, Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp),
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = singleLine, minLines = if (singleLine) 1 else 3,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
  )
}

// ponytail: ignores EXIF rotation; honour it when portrait photos show sideways.
private fun loadThumbnail(path: String) = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
  BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
