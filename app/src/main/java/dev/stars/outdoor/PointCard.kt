package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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

/** §2.1 long-press card: 添加标注 / 测距 / 复制坐标 / 分享坐标 for the pressed point. */
@Composable
fun PointCard(
  lat: Double,
  lon: Double,
  onWaypoint: () -> Unit,
  onMeasure: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    BasicText(coordinateText(lat, lon), style = TextStyle(fontSize = 18.sp))
    BasicText("WGS-84", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton("添加标注", enabled = true, onWaypoint, Modifier.weight(1f))
      PrimaryButton("测距", enabled = true, onMeasure, Modifier.weight(1f))
      PrimaryButton("复制坐标", enabled = true, onCopy, Modifier.weight(1f))
      PrimaryButton("分享坐标", enabled = true, onShare, Modifier.weight(1f))
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
