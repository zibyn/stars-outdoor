package dev.stars.outdoor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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

/**
 * 轨迹详情: distance, ascent, time, elevation profile (§2.5), 坐标纠偏 and export (§2.6), offline download (§2.3).
 * A bottom panel, so the track drawn on the map above previews the 纠偏 live.
 */
@Composable
fun TrackDetailScreen(
  name: String,
  stats: TrackStats,
  datum: Datum,
  reference: Boolean,
  onReference: () -> Unit,
  onDatum: (Datum) -> Unit,
  onExport: (kml: Boolean) -> Unit,
  onDownload: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    BasicText(name, style = TextStyle(fontSize = 22.sp))
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Stat("距离", String.format(Locale.ROOT, "%.2f km", stats.distanceM / 1000))
      Stat("爬升", "${Math.round(stats.ascentM)} m")
      val min = stats.durationMs / 60_000
      Stat("用时", String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60))
    }
    BasicText("海拔剖面", style = TextStyle(color = Color.Gray))
    ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp))
    BasicText("坐标纠偏（只对中国境内生效）", style = TextStyle(color = Color.Gray))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (d in Datum.entries) {
        val selected = d == datum
        BasicText(
          d.label,
          Modifier.weight(1f).border(1.dp, if (selected) Color(0xFF2F9E6E) else Color.LightGray, RoundedCornerShape(8.dp)).clickable { onDatum(d) }.padding(8.dp),
          style = TextStyle(color = if (selected) Color(0xFF2F9E6E) else Color.Black, fontSize = 12.sp, textAlign = TextAlign.Center),
        )
      }
    }
    PrimaryButton(if (reference) "取消参考轨迹" else "设为参考轨迹", enabled = true, onReference, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    PrimaryButton("沿此轨迹下载离线地图", enabled = true, onDownload, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton("导出 GPX", enabled = true, { onExport(false) }, Modifier.weight(1f))
      PrimaryButton("导出 KML", enabled = true, { onExport(true) }, Modifier.weight(1f))
    }
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
