package com.starsdom.outdoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ponytail: data sources only; the §1.4 承诺 and the rest of 关于 come with M5.
/** 设置 → 关于: where the map data comes from and under which licence; [onOsmExtract] opens the OSM extraction script (ODbL). */
@Composable
fun AboutScreen(onOsmExtract: () -> Unit) {
  Page(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
    Text("关于 · 数据来源", style = MaterialTheme.typography.titleLarge)
    // 强制升级 (#118) lands here until the in-app update (#51): at least say which build this is.
    Text("当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）；要新版本请联系发布者", Modifier.padding(top = 12.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    for (line in listOf(
      "地图与徒步线路：© OpenStreetMap contributors，以开放数据库许可（ODbL 1.0）授权；底图由 Protomaps 生成。",
      "徒步线路按标签 route=hiking / route=foot 从 OpenStreetMap 抽取，原样展示，未与其他数据合并。",
      "公开轨迹：用户主动公开的轨迹，单独存放和显示，不与 OpenStreetMap 合并。",
      "地形：Mapterhorn；等高线：Copernicus GLO-30。",
      "搜索：先查离线地名索引，在线结果来自 OpenStreetMap（Photon）与天地图。",
    )) Text(line, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
    Text(
      "查看 OSM 抽取脚本与标签规则",
      Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOsmExtract).padding(top = 24.dp),
      color = MaterialTheme.colorScheme.primary,
    )
  }
}
