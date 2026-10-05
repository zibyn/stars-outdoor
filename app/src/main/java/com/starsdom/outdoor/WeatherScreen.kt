package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Numbers line up down the hours. */
private val Figures = TextStyle(fontSize = 15.sp, fontFeatureSettings = "tnum", textAlign = TextAlign.End)

/**
 * 地图右上的天气 (§2.9): the temperature where I am this hour, a red dot when [warn] (出行提醒 in the next 12 h);
 * "天气" before there's any forecast.
 */
@Composable
fun WeatherChip(w: PlaceWeather?, warn: Boolean, nowMs: Long, onClick: () -> Unit) = MapIconButton(onClick) {
  val h = w?.at(nowMs)
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    BasicText(h?.let { "${Math.round(it.tempAt(w.ele))}°" } ?: "天气", style = TextStyle(fontSize = if (h != null) 16.sp else 12.sp))
    if (warn) Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(8.dp).background(AlertRed, CircleShape))
  }
}

/**
 * 天气 (整页, §2.9): one place's forecast as data, no advice: now, sunrise and sunset, the official warnings, then
 * each hour's 气温 / 体感 / 降水 / 阵风, values past the 出行提醒 thresholds in red. [place] says where: 我的位置 or a
 * long-pressed point. Greyed once a cached forecast is over 12 h old.
 */
@Composable
fun WeatherScreen(place: String, w: PlaceWeather?, loading: Boolean, nowMs: Long) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(horizontal = 16.dp)) {
    BasicText(place, Modifier.padding(top = 16.dp), style = TextStyle(color = Color.Gray, fontSize = 14.sp))
    if (w == null) {
      BasicText(if (loading) "正在获取天气…" else "暂无天气，联网后再打开", Modifier.padding(top = 16.dp), style = TextStyle(fontSize = 16.sp))
      return@Column
    }
    val zone = remember { TimeZone.getDefault() }
    val old = w.offline && stale(w.fetchedMs, nowMs)
    val hours = remember(w, nowMs / 3_600_000) { w.hours().filter { (t, _) -> t + 3_600_000 > nowMs } }
    LazyColumn(Modifier.fillMaxSize().alpha(if (old) 0.4f else 1f)) {
      item {
        hours.firstOrNull()?.second?.let { h ->
          Row(verticalAlignment = Alignment.Bottom) {
            BasicText("${Math.round(h.tempAt(w.ele))}°", style = TextStyle(fontSize = 64.sp, fontFeatureSettings = "tnum"))
            BasicText("体感 ${Math.round(h.feelsLikeAt(w.ele))}°", Modifier.padding(start = 12.dp, bottom = 14.dp), style = TextStyle(color = Color.Gray, fontSize = 16.sp))
          }
        }
        val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
        val sun = listOfNotNull(
          sunriseMs(w.lat, w.lon, nowMs, zone)?.let { "日出 ${clock.format(it)}" },
          sunsetMs(w.lat, w.lon, nowMs, zone)?.let { "日落 ${clock.format(it)}" },
        )
        if (sun.isNotEmpty()) BasicText(sun.joinToString("    "), style = TextStyle(fontSize = 15.sp))
        if (w.offline) BasicText(updatedText(w.fetchedMs, nowMs) + if (old) "，预报可能已过时" else "", Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        // Open-Meteo stood in for 和风: its forecast comes without warnings.
        if ("open-meteo" in w.forecast.sources) BasicText("暂时无法获取官方预警", Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        for (a in w.forecast.alerts) Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Color(0xFFFFEBEE)).padding(12.dp)) {
          BasicText(a.title, style = TextStyle(color = AlertRed, fontSize = 15.sp))
          if (a.text.isNotEmpty()) BasicText(a.text, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 13.sp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
          for ((label, weight) in listOf("时间" to 1f, "气温" to 1f, "体感" to 1f, "降水 mm" to 1.2f, "阵风 m/s" to 1.2f)) {
            BasicText(label, Modifier.weight(weight), style = TextStyle(color = Color.Gray, fontSize = 12.sp, textAlign = if (label == "时间") TextAlign.Start else TextAlign.End))
          }
        }
      }
      items(hours, key = { it.first }) { (t, h) -> HourRow(t, h, w.ele, zone, nowMs) }
      item {
        val names = w.forecast.sources.map { if (it == "qweather") "和风天气" else "Open-Meteo" }
        if (names.isNotEmpty()) BasicText("数据：" + names.joinToString(" / "), Modifier.padding(vertical = 16.dp), style = TextStyle(color = Color.Gray, fontSize = 10.sp))
      }
    }
  }
}

/** One hour; a new day starts with its date. 雷 marks thunder. */
@Composable
private fun HourRow(t: Long, h: WeatherHour, ele: Double?, zone: TimeZone, nowMs: Long) {
  val hour = SimpleDateFormat("HH", Locale.ROOT).apply { timeZone = zone }.format(t)
  if (hour == "00") BasicText(
    SimpleDateFormat("M月d日 E", Locale.CHINA).apply { timeZone = zone }.format(t),
    Modifier.padding(top = 12.dp, bottom = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 13.sp),
  )
  fun red(on: Boolean) = if (on) Figures.copy(color = AlertRed) else Figures
  Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(
      if (t <= nowMs) "现在" else "$hour:00" + if (h.thunder) "  雷" else "",
      Modifier.weight(1f), style = TextStyle(color = if (h.thunder) AlertRed else Color.Black, fontSize = 15.sp, fontFeatureSettings = "tnum"),
    )
    BasicText("${Math.round(h.tempAt(ele))}°", Modifier.weight(1f), style = Figures)
    BasicText("${Math.round(h.feelsLikeAt(ele))}°", Modifier.weight(1f), style = red(isFreezing(h, ele)))
    BasicText(String.format(Locale.ROOT, "%.1f", h.precip), Modifier.weight(1.2f), style = red(isHeavyRain(h)))
    BasicText(String.format(Locale.ROOT, "%.1f", h.gust), Modifier.weight(1.2f), style = red(isGale(h)))
  }
}
