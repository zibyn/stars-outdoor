package dev.stars.outdoor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 轨迹详情 (ux-v2 §4.2), a 半屏抽屉 that pulls up to full screen; the track drawn on the map above previews the 纠偏
 * live. Top to bottom: name, numbers, elevation profile (§2.5), 设为参考 and 叠加, 沿线离线地图 (§2.3), the 出发前
 * battery row, 沿途天气 (§2.9), then 坐标纠偏, export (§2.6) and 公开 (§2.8).
 */
@Composable
fun TrackDetailScreen(
  name: String,
  planned: Boolean,
  stats: TrackStats,
  /** The first point's time; null when the track has none. */
  dateMs: Long?,
  datum: Datum,
  reference: Boolean,
  overlaid: Boolean,
  public: Boolean,
  weather: TrackWeather?,
  weatherLoading: Boolean,
  pace: Pace,
  /** [corridorText]; [onDownload] is null when there's nothing to download (已下载, or 下载中). */
  corridor: String,
  onDownload: (() -> Unit)?,
  /** The 出发前 battery row shows. */
  batteryRow: Boolean,
  onBattery: () -> Unit,
  onReference: () -> Unit,
  onOverlay: () -> Unit,
  onPublic: () -> Unit,
  onDatum: (Datum) -> Unit,
  onRename: (String) -> Unit,
  onPace: (Pace) -> Unit,
  onDepart: () -> Unit,
  onExport: (kml: Boolean) -> Unit,
  onClose: () -> Unit,
) {
  var full by rememberSaveable { mutableStateOf(false) }
  HalfDrawer(full, { full = it }, onClose) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
      var renaming by remember(name) { mutableStateOf<String?>(null) }
      val draft = renaming
      if (draft == null) {
        Row(Modifier.clickable { renaming = name.removeSuffix("（计划）") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText(name, style = TextStyle(fontSize = 22.sp))
          Icon(R.drawable.edit_wght500_24px, "改名")
        }
      } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
          draft, { renaming = it }, Modifier.weight(1f).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).padding(8.dp),
          textStyle = TextStyle(fontSize = 18.sp), singleLine = true,
        )
        Button("保存", primary = true, { onRename(draft); renaming = null }, Modifier)
      }
      Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("距离", String.format(Locale.ROOT, "%.2f km", stats.distanceM / 1000))
        Stat("爬升", "${Math.round(stats.ascentM)} m")
        // A plan has no time of its own.
        if (!planned) {
          val min = stats.durationMs / 60_000
          Stat("用时", String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60))
        }
        dateMs?.let { Stat("日期", SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(it)) }
      }
      BasicText("海拔剖面", style = TextStyle(color = Color.Gray))
      ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp))
      Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(if (reference) "不再用作参考轨迹" else "设为参考", enabled = true, onReference, Modifier.weight(1f))
        PrimaryButton(if (overlaid) "取消叠加" else "叠加到地图", enabled = true, onOverlay, Modifier.weight(1f))
      }
      Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("沿线离线地图：$corridor", Modifier.weight(1f))
        onDownload?.let { BasicText("下载", Modifier.heightIn(min = 56.dp).clickable(onClick = it).padding(horizontal = 12.dp).wrapContentHeight(), style = TextStyle(color = Green)) }
      }
      if (batteryRow) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onBattery), verticalAlignment = Alignment.CenterVertically) {
        BasicText("出发前：防止手机在后台停掉记录", Modifier.weight(1f))
        BasicText("去设置", Modifier.padding(horizontal = 12.dp), style = TextStyle(color = Green))
      }
      WeatherBlock(weather, weatherLoading, pace, onPace, onDepart)
      BasicText("坐标纠偏（只对中国境内生效）", style = TextStyle(color = Color.Gray))
      Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in Datum.entries) Chip(d.label, d == datum) { onDatum(d) }
      }
      Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("导出 GPX", enabled = true, { onExport(false) }, Modifier.weight(1f))
        PrimaryButton("导出 KML", enabled = true, { onExport(true) }, Modifier.weight(1f))
      }
      PrimaryButton(if (public) "撤回公开" else "公开到周边路网", enabled = true, onPublic, Modifier.fillMaxWidth())
      BasicText(
        if (public) "他人可在周边路网看到这条轨迹（起点和终点各 200 m 不显示）" else "公开后他人可在周边路网看到，起点和终点各 200 m 自动隐藏，可随时撤回",
        Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp),
      )
    }
  }
}

@Composable
private fun RowScope.Chip(label: String, selected: Boolean, weight: Float = 1f, onClick: () -> Unit) = BasicText(
  label,
  Modifier.weight(weight).border(1.dp, if (selected) Green else Color.LightGray, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp),
  style = TextStyle(color = if (selected) Green else Color.Black, fontSize = 12.sp, textAlign = TextAlign.Center),
)

private val alertRed = Color(0xFFC62828)

/** 沿途天气: departure and 配速档, the 出行提醒, then each sample's forecast; greyed once an offline forecast is over 12 h old. */
@Composable
private fun WeatherBlock(w: TrackWeather?, loading: Boolean, pace: Pace, onPace: (Pace) -> Unit, onDepart: () -> Unit) {
  val now = System.currentTimeMillis()
  val clock = remember { SimpleDateFormat("HH:mm", Locale.ROOT) }
  BasicText("沿途天气", style = TextStyle(color = Color.Gray))
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Chip("出发 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(w?.departMs ?: now), false, 3f, onDepart)
    for (p in Pace.entries) Chip(p.label, p == pace) { onPace(p) }
  }
  if (w == null) return BasicText(if (loading) "正在获取天气…" else "暂无天气，联网后再打开", Modifier.padding(bottom = 16.dp), style = TextStyle(color = Color.Gray))
  val old = w.offline && stale(w.fetchedMs, now)
  Column(Modifier.padding(bottom = 16.dp).alpha(if (old) 0.4f else 1f)) {
    if (w.offline) BasicText(updatedText(w.fetchedMs, now) + if (old) "，预报可能已过时" else "", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    // Open-Meteo stood in for 和风 somewhere: its forecast comes without warnings.
    if ("open-meteo" in w.forecast.sources) BasicText("部分路段暂时无法获取官方预警", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    val alerts = remember(w) { w.alerts() }
    if (alerts.isEmpty()) BasicText("沿途没有出行提醒", Modifier.padding(vertical = 4.dp))
    for (a in alerts) {
      BasicText(a.text, Modifier.padding(top = 4.dp), style = TextStyle(color = alertRed))
      if (a.detail.isNotEmpty()) BasicText(a.detail, style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    }
    for ((i, s) in w.samples.withIndex()) {
      val h = w.forecast.hours[i]
      val where = clock.format(s.etaMs) + String.format(Locale.ROOT, "  %.1f km", s.distM / 1000) + (s.point.ele?.let { "  ${Math.round(it)} m" } ?: "")
      val what = h?.let {
        "  ${Math.round(it.tempAt(s.point.ele))}°C（体感 ${Math.round(it.feelsLikeAt(s.point.ele))}°C）  雨 ${it.precip} mm  阵风 ${Math.round(it.gust)} m/s" + if (it.thunder) "  雷阵雨" else ""
      } ?: "  暂无预报"
      BasicText(where + what, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 12.sp))
    }
    val names = w.forecast.sources.map { if (it == "qweather") "和风天气" else "Open-Meteo" }
    if (names.isNotEmpty()) BasicText("数据：" + names.joinToString(" / "), Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 10.sp))
  }
}

/** Top banner (§2.9) when a track's 沿途天气 has 出行提醒: which risks, tap for the details. */
@Composable
fun TripAlertBanner(name: String, alerts: List<TripAlert>, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
  Row(modifier.fillMaxWidth().background(Color(0xFFFFEBEE), RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      BasicText("出行提醒 · $name", style = TextStyle(color = alertRed, fontSize = 14.sp))
      BasicText(alerts.map { it.risk.label }.distinct().joinToString(" · "), style = TextStyle(fontSize = 12.sp))
    }
    Box(Modifier.size(48.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(R.drawable.close_wght500_24px, "关闭") }
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
      drawPath(path, Green, style = Stroke(width = 2.dp.toPx()))
    }
    BasicText("${Math.round(minEle)} m", style = TextStyle(color = Color.Gray, fontSize = 10.sp))
  }
}
