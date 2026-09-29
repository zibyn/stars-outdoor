package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 底栏 → 我的轨迹 (整页, ux-v2 §4.1): 轨迹 (finished tracks, and import, §2.6) and 标注 tabs. */
@Composable
fun TrackListScreen(
  tracks: List<TrackSummary>,
  waypoints: List<Waypoint>,
  importing: Boolean,
  onOpen: (Long) -> Unit,
  onWaypoint: (Waypoint) -> Unit,
  onImport: () -> Unit,
) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("我的轨迹", Modifier.padding(bottom = 8.dp), style = TextStyle(fontSize = 22.sp))
    Row(Modifier.fillMaxWidth()) {
      for ((i, label) in listOf("轨迹", "标注").withIndex()) BasicText(
        label,
        Modifier.weight(1f).heightIn(min = 56.dp).selectable(tab == i, role = Role.Tab) { tab = i }.wrapContentHeight(),
        style = TextStyle(color = if (tab == i) Color(0xFF2F9E6E) else Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center),
      )
    }
    if (tab == 0) {
      LazyColumn(Modifier.weight(1f)) {
        items(tracks, key = { it.id }) { t ->
          BasicText(t.name + if (t.planned) "（计划）" else "", Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 14.dp))
        }
      }
      PrimaryButton(if (importing) "正在导入…" else "导入 GPX / KML / FIT / GeoJSON / PLT", enabled = !importing, onImport)
    } else LazyColumn(Modifier.weight(1f)) {
      items(waypoints, key = { it.id }) { w ->
        Column(Modifier.fillMaxWidth().clickable { onWaypoint(w) }.padding(vertical = 10.dp)) {
          BasicText(w.name.ifBlank { "未命名标注" })
          // Imported 标注 may have no time.
          if (w.timeMs != 0L) BasicText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(w.timeMs)), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        }
      }
    }
  }
}

/** A file with several tracks: the user ticks which to import (§2.6). */
@Composable
fun ImportPickScreen(fileName: String, tracks: List<ParsedTrack>, checked: Set<Int>, onToggle: (Int) -> Unit, onImport: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("导入 $fileName", style = TextStyle(fontSize = 22.sp))
    BasicText("文件中有 ${tracks.size} 条轨迹，选择要导入的", Modifier.padding(vertical = 8.dp), style = TextStyle(color = Color.Gray))
    LazyColumn(Modifier.weight(1f)) {
      itemsIndexed(tracks) { i, t ->
        Row(Modifier.fillMaxWidth().toggleable(i in checked, role = Role.Checkbox) { onToggle(i) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(if (i in checked) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, Modifier.padding(end = 12.dp))
          Column {
            BasicText(importName(t, fileName, i, tracks.size) + if (t.planned) "（计划）" else "")
            BasicText("${t.segments.sumOf { it.size }} 个点", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
          }
        }
      }
    }
    PrimaryButton("导入 ${checked.size} 条", enabled = checked.isNotEmpty(), onImport)
  }
}

/** The file's own name for the track, else the file name (numbered when the file holds several). */
fun importName(t: ParsedTrack, fileName: String, index: Int, count: Int) =
  t.name.ifBlank { fileName.substringBeforeLast('.') + if (count > 1) " ${index + 1}" else "" }

@Composable
fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
  BasicText(
    text,
    modifier.background(if (enabled) Color(0xFF2F9E6E) else Color.LightGray, RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(14.dp),
    style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
  )
}
