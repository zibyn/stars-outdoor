package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ponytail: data sources only; the §1.4 承诺 and the rest of 关于 come with M5.
/** 菜单 → 关于: where the map data comes from and under which licence; [onOsmExtract] opens the OSM extraction script (ODbL). */
@Composable
fun AboutScreen(onOsmExtract: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
    BasicText("关于 · 数据来源", style = TextStyle(fontSize = 20.sp))
    for (line in listOf(
      "地图与徒步线路：© OpenStreetMap contributors，以开放数据库许可（ODbL 1.0）授权；底图由 Protomaps 生成。",
      "徒步线路按标签 route=hiking / route=foot 从 OpenStreetMap 抽取，原样展示，未与其他数据合并。",
      "公开轨迹：用户主动公开的轨迹，单独存放和显示，不与 OpenStreetMap 合并。",
      "平台轨迹：香港渔农自然护理署郊野公园远足径（DATA.GOV.HK）；台湾林业及自然保育署自然步道轨迹图（政府資料開放授權條款第1版）。",
      "地形：Mapterhorn；等高线：Copernicus GLO-30。",
    )) BasicText(line, Modifier.padding(top = 12.dp), style = TextStyle(fontSize = 14.sp))
    BasicText(
      "查看 OSM 抽取脚本与标签规则",
      Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOsmExtract).padding(top = 20.dp),
      style = TextStyle(color = Color(0xFF2F9E6E), fontSize = 16.sp),
    )
  }
}
