package dev.stars.outdoor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 轨迹详情: distance, ascent, time and elevation profile (§2.5). */
@Composable
fun TrackDetailScreen(name: String, stats: TrackStats, onExport: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText(name, style = TextStyle(fontSize = 22.sp))
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Stat("距离", String.format(Locale.ROOT, "%.2f km", stats.distanceM / 1000))
      Stat("爬升", "${Math.round(stats.ascentM)} m")
      val min = stats.durationMs / 60_000
      Stat("用时", String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60))
    }
    BasicText("海拔剖面", style = TextStyle(color = Color.Gray))
    ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp))
    Spacer(Modifier.weight(1f))
    BasicText(
      "导出 GPX",
      Modifier.fillMaxWidth().background(Color(0xFF2F9E6E), RoundedCornerShape(8.dp)).clickable(onClick = onExport).padding(14.dp),
      style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
    )
  }
}

@Composable
private fun Stat(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    BasicText(value, style = TextStyle(fontSize = 20.sp))
    BasicText(label, style = TextStyle(color = Color.Gray, fontSize = 12.sp))
  }
}

@Composable
private fun ElevationProfile(profile: List<Pair<Double, Double>>, modifier: Modifier) {
  if (profile.size < 2) return BasicText("无海拔数据", modifier, style = TextStyle(color = Color.Gray))
  val maxDist = profile.last().first.coerceAtLeast(1.0)
  val minEle = profile.minOf { it.second }
  val span = (profile.maxOf { it.second } - minEle).coerceAtLeast(1.0)
  Column(modifier) {
    BasicText("${Math.round(minEle + span)} m", style = TextStyle(color = Color.Gray, fontSize = 10.sp))
    Canvas(Modifier.fillMaxWidth().weight(1f)) {
      val path = Path()
      profile.forEachIndexed { i, (d, e) ->
        val o = Offset((d / maxDist * size.width).toFloat(), ((1 - (e - minEle) / span) * size.height).toFloat())
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
      }
      drawPath(path, Color(0xFF2F9E6E), style = Stroke(width = 2.dp.toPx()))
    }
    BasicText("${Math.round(minEle)} m", style = TextStyle(color = Color.Gray, fontSize = 10.sp))
  }
}
