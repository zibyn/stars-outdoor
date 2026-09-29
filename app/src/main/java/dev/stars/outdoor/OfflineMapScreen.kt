package dev.stars.outdoor

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 底栏 → 离线地图: downloaded packages (可更新 when the server's [dataVersion] moved on, §2.3) and offline
 * files with their size, delete, and import.
 */
@Composable
fun OfflineMapScreen(
  packages: List<OfflinePackage>,
  dataVersion: String?,
  downloading: Boolean,
  onUpdate: (OfflinePackage) -> Unit,
  onDeletePackage: (OfflinePackage) -> Unit,
  files: List<File>,
  importing: Boolean,
  onImport: () -> Unit,
  onDelete: (File) -> Unit,
) {
  val context = LocalContext.current
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("离线地图", style = TextStyle(fontSize = 22.sp))
    BasicText(
      "共 " + Formatter.formatShortFileSize(context, files.sumOf { it.length() } + packages.sumOf { it.bytes }) +
        if (downloading) " · 正在下载…" else "",
      Modifier.padding(vertical = 8.dp),
      style = TextStyle(color = Color.Gray),
    )
    if (packages.isEmpty()) BasicText("还没有离线地图。在轨迹详情里沿线下载，或长按地图「下载这附近」", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    LazyColumn(Modifier.weight(1f)) {
      items(packages, key = { it.dir.path }) { pkg ->
        val stale = dataVersion != null && pkg.version != dataVersion
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            BasicText(pkg.name)
            BasicText(
              Formatter.formatShortFileSize(context, pkg.bytes) + if (stale) " · 可更新" else "",
              style = TextStyle(color = if (stale) Color(0xFF2F9E6E) else Color.Gray, fontSize = 12.sp),
            )
          }
          if (stale) BasicText("更新", Modifier.clickable(enabled = !downloading) { onUpdate(pkg) }.padding(8.dp), style = TextStyle(color = Color(0xFF2F9E6E)))
          BasicText("删除", Modifier.clickable { onDeletePackage(pkg) }.padding(8.dp), style = TextStyle(color = Color(0xFFE4572E)))
        }
      }
      items(files, key = { it.path }) { file ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            BasicText(file.name)
            BasicText(Formatter.formatShortFileSize(context, file.length()), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
          }
          BasicText("删除", Modifier.clickable { onDelete(file) }.padding(8.dp), style = TextStyle(color = Color(0xFFE4572E)))
        }
      }
    }
    BasicText(
      if (importing) "正在导入…" else "导入 MBTiles / PMTiles",
      Modifier.fillMaxWidth().background(Color(0xFF2F9E6E), RoundedCornerShape(8.dp)).clickable(enabled = !importing, onClick = onImport).padding(14.dp),
      style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
    )
  }
}
