package com.starsdom.outdoor

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 底栏 → 离线地图 (ux-v2 §4.1), management only: downloaded packages (可更新 when the server's [dataVersion] moved on,
 * §2.3) and imported files with their size, delete (再点一次, ux-v2 §6.3), and import. New packages come from
 * 轨迹详情 and 下载这附近.
 */
@Composable
fun OfflineMapScreen(
  packages: List<OfflinePackage>,
  dataVersion: String?,
  /** The download in flight, as a percentage; null when none. */
  downloading: Int?,
  onUpdate: (OfflinePackage) -> Unit,
  onDeletePackage: (OfflinePackage) -> Unit,
  files: List<File>,
  importing: Boolean,
  onImport: () -> Unit,
  onDelete: (File) -> Unit,
) {
  val context = LocalContext.current
  Page(Modifier.padding(16.dp)) {
    Text("离线地图", style = MaterialTheme.typography.titleLarge)
    Text(
      "共 " + Formatter.formatShortFileSize(context, files.sumOf { it.length() } + packages.sumOf { it.bytes }) +
        (downloading?.let { " · 下载中 $it%" } ?: ""),
      Modifier.padding(vertical = 8.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (packages.isEmpty() && files.isEmpty()) Text("还没有离线地图。在轨迹详情里沿线下载，或长按地图「下载这附近」", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    LazyColumn(Modifier.weight(1f)) {
      items(packages, key = { it.dir.path }) { pkg ->
        val stale = dataVersion != null && pkg.version != dataVersion
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(pkg.name)
            Text(
              Formatter.formatShortFileSize(context, pkg.bytes) + if (stale) " · 可更新" else "",
              color = if (stale) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
            )
          }
          if (stale) Text("更新", Modifier.heightIn(min = 56.dp).clickable(enabled = downloading == null) { onUpdate(pkg) }.padding(horizontal = 12.dp).wrapContentHeight(), color = MaterialTheme.colorScheme.primary)
          TapAgain("删除", "再点一次删除") { onDeletePackage(pkg) }
        }
      }
      items(files, key = { it.path }) { file ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text(file.name)
            Text(Formatter.formatShortFileSize(context, file.length()), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          }
          TapAgain("删除", "再点一次删除") { onDelete(file) }
        }
      }
    }
    Text(
      if (importing) "正在导入…" else "导入离线地图文件",
      Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).clickable(enabled = !importing, onClick = onImport).padding(12.dp),
      color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center,
    )
  }
}
