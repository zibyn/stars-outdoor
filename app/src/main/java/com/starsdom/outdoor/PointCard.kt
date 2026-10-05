package com.starsdom.outdoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

fun coordinateText(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

fun distanceText(m: Double): String = if (m < 1000) "${Math.round(m)} m" else String.format(Locale.ROOT, "%.2f km", m / 1000)

/** Long-press 小抽屉 (ux-v2 §4.1): the pressed point, then 添加标注 / 测距 / 这里的天气 / 复制坐标 / 分享坐标 / 下载这附近. */
@Composable
fun PointCard(
  lat: Double,
  lon: Double,
  onWaypoint: () -> Unit,
  onMeasure: () -> Unit,
  onWeather: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  onDownload: () -> Unit,
  modifier: Modifier,
) {
  Sheet(modifier) {
    // Shared text still says WGS-84 (§6.5).
    Text(coordinateText(lat, lon), Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
    for ((label, onClick) in listOf("添加标注" to onWaypoint, "测距" to onMeasure, "这里的天气" to onWeather, "复制坐标" to onCopy, "分享坐标" to onShare, "下载这附近" to onDownload)) {
      Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).wrapContentHeight())
    }
  }
}

/** 测距 banner: the straight-line distance once an end point is tapped. */
@Composable
fun MeasureBanner(distanceM: Double?, onClose: () -> Unit, modifier: Modifier) {
  Floating(modifier, MaterialTheme.shapes.medium) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(distanceM?.let { "直线距离 " + distanceText(it) } ?: "点地图选择终点")
      Text("结束", Modifier.padding(start = 16.dp).clickable(onClick = onClose), color = MaterialTheme.colorScheme.primary)
    }
  }
}
