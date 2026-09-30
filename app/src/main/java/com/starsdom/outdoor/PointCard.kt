package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun coordinateText(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

fun distanceText(m: Double): String = if (m < 1000) "${Math.round(m)} m" else String.format(Locale.ROOT, "%.2f km", m / 1000)

/** Long-press 小抽屉 (ux-v2 §4.1): the pressed point, then 添加标注 / 测距 / 复制坐标 / 分享坐标 / 下载这附近. */
@Composable
fun PointCard(
  lat: Double,
  lon: Double,
  onWaypoint: () -> Unit,
  onMeasure: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  onDownload: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    // Shared text still says WGS-84 (§6.5).
    BasicText(coordinateText(lat, lon), Modifier.padding(bottom = 4.dp), style = TextStyle(fontSize = 18.sp))
    for ((label, onClick) in listOf("添加标注" to onWaypoint, "测距" to onMeasure, "复制坐标" to onCopy, "分享坐标" to onShare, "下载这附近" to onDownload)) {
      BasicText(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).wrapContentHeight(), style = TextStyle(fontSize = 16.sp))
    }
  }
}

/** 测距 banner: the straight-line distance once an end point is tapped. */
@Composable
fun MeasureBanner(distanceM: Double?, onClose: () -> Unit, modifier: Modifier) {
  Row(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(distanceM?.let { "直线距离 " + distanceText(it) } ?: "点地图选择终点", style = TextStyle(fontSize = 16.sp))
    BasicText("结束", Modifier.padding(start = 16.dp).clickable(onClick = onClose), style = TextStyle(color = Color(0xFF2F9E6E), fontSize = 16.sp))
  }
}
