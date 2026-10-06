package com.starsdom.trail.offline

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.track.DrawerIconButton
import com.starsdom.trail.track.EmptyState
import com.starsdom.trail.track.alongTrack
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Icon
import com.starsdom.trail.ui.Page
import com.starsdom.trail.ui.Space
import com.starsdom.trail.ui.Spinner
import com.starsdom.trail.weather.stale
import java.io.File

/**
 * 底栏 → 离线地图 (ux-v3 §8.6 第 13–18 条), a 一级页 (no ←): ＋ imports a file; the total and the phone's free space;
 * the packages (沿线 / 附近 by icon) and imported files. A tap on a package shows its outline on the map ([onOpen]);
 * ⋮ 更新 (when the server's [dataVersion] moved on, also at the row's end) / 删除. The one downloading carries its
 * progress: an update on its row, a new one on a row of its own at the top.
 */
@Composable
fun OfflineMapScreen(
  packages: List<OfflinePackage>,
  dataVersion: String?,
  /** The download in flight (its request, name and percentage); null when none. */
  download: Triple<String, String, Int>?,
  freeBytes: Long,
  now: Long,
  onOpen: (OfflinePackage) -> Unit,
  onUpdate: (OfflinePackage) -> Unit,
  onDeletePackage: (OfflinePackage) -> Unit,
  files: List<File>,
  importing: Boolean,
  onImport: () -> Unit,
  onDelete: (File) -> Unit,
  onBackToMap: () -> Unit,
) {
  val context = LocalContext.current
  val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
  Page {
    Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.XS), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.bar_offline), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
      // C6-57: importing, the ＋ turns into a spinner.
      Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !importing, role = Role.Button, onClick = onImport), contentAlignment = Alignment.Center) {
        if (importing) Spinner(Modifier.size(24.dp)) else Icon(R.drawable.add_wght500_24px, stringResource(R.string.import_label))
      }
    }
    val newOne = download?.takeIf { (request) -> packages.none { it.request == request } }
    if (packages.isEmpty() && files.isEmpty() && newOne == null) return@Page EmptyState(
      R.drawable.download_for_offline_wght500_24px, stringResource(R.string.offline_empty), stringResource(R.string.back_to_map), onBackToMap,
    )
    Text(
      stringResource(R.string.offline_summary, size(files.sumOf { it.length() } + packages.sumOf { it.bytes }), size(freeBytes)),
      Modifier.padding(horizontal = Space.L, vertical = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.weight(1f)) {
      if (newOne != null) item(key = "downloading") {
        val (request, name, percent) = newOne
        KindRow(alongTrack(request), name, "$percent%", percent, null)
      }
      items(packages, key = { it.dir.path }) { pkg ->
        val stale = dataVersion != null && pkg.version != dataVersion
        val percent = download?.takeIf { it.first == pkg.request }?.third
        val update = { onUpdate(pkg) }.takeIf { stale && percent == null }
        KindRow(
          pkg.alongTrack, pkg.name, packageLine(size(pkg.bytes), pkg.savedMs, now, stale, percent), percent, { onOpen(pkg) },
          update, listOfNotNull(update?.let { stringResource(R.string.update) to it }, stringResource(R.string.delete) to { onDeletePackage(pkg) }),
        )
      }
      items(files, key = { it.path }) { file ->
        PackageRow(
          R.drawable.folder_wght500_24px, stringResource(R.string.import_label), file.nameWithoutExtension,
          stringResource(R.string.offline_imported, size(file.length())), null, null, menu = listOf(stringResource(R.string.delete) to { onDelete(file) }),
        )
      }
    }
  }
}

/** A package's row, its icon by [along] (沿线 / 附近). */
@Composable
private fun KindRow(
  along: Boolean, name: String, line: String, percent: Int?, onClick: (() -> Unit)?,
  onUpdate: (() -> Unit)? = null, menu: List<Pair<String, () -> Unit>> = emptyList(),
) = PackageRow(
  if (along) R.drawable.route_wght500_24px else R.drawable.location_on_wght500_24px, stringResource(if (along) R.string.kind_along else R.string.kind_nearby),
  name, line, percent, onClick, onUpdate, menu,
)

/**
 * One row (C6-60…62): the kind's [icon] (read as [kind]), [name] over [line], a progress bar under them while [percent];
 * ［更新］ at the end with [onUpdate], then ⋮ with [menu]. Tapped, [onClick].
 */
@Composable
private fun PackageRow(
  @DrawableRes icon: Int, kind: String, name: String, line: String, percent: Int?, onClick: (() -> Unit)?,
  onUpdate: (() -> Unit)? = null, menu: List<Pair<String, () -> Unit>> = emptyList(),
) = Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
  Icon(icon, kind, Modifier.padding(start = Space.L), MaterialTheme.colorScheme.onSurfaceVariant)
  // No ellipsis: at 200 % it wraps (§4.3).
  Column(Modifier.weight(1f).heightIn(min = 56.dp).padding(start = Space.L, top = Space.XS, bottom = Space.XS), Arrangement.Center) {
    Text(name)
    Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    if (percent != null) LinearProgressIndicator({ percent / 100f }, Modifier.fillMaxWidth().padding(top = Space.XXS))
  }
  if (onUpdate != null) Text(
    stringResource(R.string.update), Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onUpdate).padding(horizontal = Space.M).wrapContentHeight(),
    MaterialTheme.colorScheme.primary,
  )
  if (menu.isEmpty()) Spacer(Modifier.width(Space.L))
  else Box {
    var open by remember { mutableStateOf(false) }
    DrawerIconButton(R.drawable.more_vert_wght500_24px, stringResource(R.string.more)) { open = true }
    DropdownMenu(open, { open = false }) {
      for ((label, action) in menu) DropdownMenuItem({ Text(label) }, { open = false; action() }, Modifier.heightIn(min = 48.dp))
    }
  }
}
