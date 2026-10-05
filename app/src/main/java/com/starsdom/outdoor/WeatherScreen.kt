package com.starsdom.outdoor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Numbers line up down the hours. */
private val Figures = TextStyle(fontSize = 15.sp, fontFeatureSettings = "tnum", textAlign = TextAlign.End)

/** Where 天气 is for (§2.9, ADR 0010): me, a long-pressed point, or spots along a track. */
sealed interface WeatherPlace {
  data object Here : WeatherPlace
  data class Point(val lat: Double, val lon: Double) : WeatherPlace
  data class Track(val id: Long) : WeatherPlace
}

/** The hour's glyph: 雷阵雨, rain, or the plain weather sign (there's no cloud cover to say sunny by). */
@DrawableRes
private fun glyph(h: WeatherHour?): Int = when {
  h?.thunder == true -> R.drawable.thunderstorm_wght500_24px
  h != null && h.precip >= 0.1 -> R.drawable.rainy_wght500_24px
  else -> R.drawable.partly_cloudy_day_wght500_24px
}

/** 地图右上的天气 (§2.9): an icon for where I am this hour, with a red dot when [warn] (出行提醒 in the next 12 h). */
@Composable
fun WeatherChip(w: PlaceWeather?, warn: Boolean, nowMs: Long, onClick: () -> Unit) = MapIconButton(onClick) {
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    Icon(glyph(w?.at(nowMs)), "天气")
    if (warn) Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(8.dp).background(AlertRed, CircleShape))
  }
}

/**
 * 天气 (整页, §2.9): [places] to switch between along the top, then one place's forecast as data, no advice: now,
 * sunrise and sunset, the official warnings, then each hour's 气温 / 体感 / 降水 / 阵风, values past the 出行提醒
 * thresholds in red. [above] goes between the switch and the forecast (沿途天气's profile); [where] says which spot.
 * Greyed once a cached forecast is over 12 h old.
 */
@Composable
fun WeatherScreen(
  places: List<Pair<WeatherPlace, String>>,
  place: WeatherPlace,
  onPlace: (WeatherPlace) -> Unit,
  w: PlaceWeather?,
  loading: Boolean,
  nowMs: Long,
  where: String? = null,
  above: (@Composable () -> Unit)? = null,
) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for ((p, label) in places) Pill(label, p == place) { onPlace(p) }
    }
    above?.invoke()
    where?.let { BasicText(it, Modifier.padding(horizontal = 16.dp), style = TextStyle(color = Color.Gray, fontSize = 14.sp)) }
    if (w == null) {
      BasicText(if (loading) "正在获取天气…" else "暂无天气，联网后再打开", Modifier.padding(16.dp), style = TextStyle(fontSize = 16.sp))
      return@Column
    }
    val zone = remember { TimeZone.getDefault() }
    val old = w.offline && stale(w.fetchedMs, nowMs)
    val hours = remember(w, nowMs / 3_600_000) { w.hours().filter { (t, _) -> t + 3_600_000 > nowMs } }
    LazyColumn(Modifier.fillMaxSize().alpha(if (old) 0.4f else 1f).padding(horizontal = 16.dp)) {
      item {
        hours.firstOrNull()?.second?.let { h ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("${Math.round(h.tempAt(w.ele))}°", style = TextStyle(fontSize = 64.sp, fontFeatureSettings = "tnum"))
            Column(Modifier.padding(start = 16.dp)) {
              BasicText("体感 ${Math.round(h.feelsLikeAt(w.ele))}°", style = TextStyle(color = if (isFreezing(h, w.ele)) AlertRed else Color.Black, fontSize = 16.sp))
              BasicText(
                String.format(Locale.ROOT, "降水 %.1f mm   阵风 %.1f m/s", h.precip, h.gust), Modifier.padding(top = 2.dp),
                style = TextStyle(color = if (isHeavyRain(h) || isGale(h)) AlertRed else Color.Gray, fontSize = 13.sp, fontFeatureSettings = "tnum"),
              )
            }
          }
        }
        val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
        val sun = listOfNotNull(
          sunriseMs(w.lat, w.lon, nowMs, zone)?.let { "日出 ${clock.format(it)}" },
          sunsetMs(w.lat, w.lon, nowMs, zone)?.let { "日落 ${clock.format(it)}" },
        )
        if (sun.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(R.drawable.wb_twilight_wght500_24px, null, tint = Color.Gray, size = 18.dp)
          BasicText(sun.joinToString("    "), Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 15.sp))
        }
        if (w.offline) BasicText(updatedText(w.fetchedMs, nowMs) + if (old) "，预报可能已过时" else "", Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        // Open-Meteo stood in for 和风: its forecast comes without warnings.
        if ("open-meteo" in w.forecast.sources) BasicText("暂时无法获取官方预警", Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
        for (a in w.forecast.alerts) Row(Modifier.fillMaxWidth().padding(top = 12.dp).background(Color(0xFFFFEBEE), RoundedCornerShape(8.dp)).padding(12.dp)) {
          Icon(R.drawable.warning_fill1_24px, null, tint = AlertRed, size = 20.dp)
          Column(Modifier.padding(start = 8.dp)) {
            BasicText(a.title, style = TextStyle(color = AlertRed, fontSize = 15.sp))
            if (a.text.isNotEmpty()) BasicText(a.text, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 13.sp))
          }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
          BasicText("时间", Modifier.weight(1f), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
          Box(Modifier.width(24.dp))
          for ((label, weight) in listOf("气温" to 1f, "体感" to 1f, "降水 mm" to 1.2f, "阵风 m/s" to 1.2f)) {
            BasicText(label, Modifier.weight(weight), style = TextStyle(color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.End))
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

/** A choice in a row that scrolls sideways: long track names cut short. */
@Composable
private fun Pill(label: String, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) = Row(
  Modifier.heightIn(min = 40.dp).border(1.dp, if (selected) Green else Color.LightGray, RoundedCornerShape(20.dp))
    .background(if (selected) Green.copy(alpha = 0.08f) else Color.White, RoundedCornerShape(20.dp))
    .clickable(onClick = onClick).padding(horizontal = 14.dp),
  verticalAlignment = Alignment.CenterVertically,
) {
  if (dot) Box(Modifier.padding(end = 6.dp).size(6.dp).background(AlertRed, CircleShape))
  BasicText(
    label, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = TextStyle(color = if (selected) Green else Color.Black, fontSize = 14.sp, fontFeatureSettings = "tnum"),
  )
}

/**
 * 沿途天气 (ADR 0010): the track's elevation [profile] over [lengthM] with a pin at each of [spots] — red where its
 * forecast has 出行提醒 in the next 48 h ([risky]) — and the same spots as choices with their temperature now ([temps],
 * null while loading). Tapping a pin or a choice picks the spot [chosen].
 */
@Composable
fun TrackSpots(
  profile: List<Pair<Double, Double>>,
  lengthM: Double,
  spots: List<TrackSpot>,
  temps: List<Int?>,
  risky: List<Boolean>,
  chosen: Int,
  onChoose: (Int) -> Unit,
) {
  val maxDist = lengthM.coerceAtLeast(1.0)
  val minEle = profile.minOfOrNull { it.second } ?: 0.0
  val span = ((profile.maxOfOrNull { it.second } ?: 0.0) - minEle).coerceAtLeast(1.0)
  Canvas(
    Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 16.dp).pointerInput(spots, maxDist) {
      detectTapGestures { at ->
        spots.indices.minByOrNull { kotlin.math.abs(spots[it].distM / maxDist * size.width - at.x) }?.let(onChoose)
      }
    },
  ) {
    val inset = 8.dp.toPx()
    fun x(d: Double) = (d / maxDist * size.width).toFloat()
    fun y(e: Double) = (inset + (1 - (e - minEle) / span) * (size.height - 2 * inset)).toFloat()
    if (profile.size >= 2) {
      val line = Path().apply { profile.forEachIndexed { i, (d, e) -> if (i == 0) moveTo(x(d), y(e)) else lineTo(x(d), y(e)) } }
      val fill = Path().apply { addPath(line); lineTo(x(profile.last().first), size.height); lineTo(x(profile.first().first), size.height); close() }
      drawPath(fill, Green.copy(alpha = 0.08f))
      drawPath(line, Green, style = Stroke(width = 2.dp.toPx()))
    } else drawLine(Color.LightGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
    spots.forEachIndexed { i, s ->
      val cx = x(s.distM)
      val cy = s.point.ele?.takeIf { profile.size >= 2 }?.let(::y) ?: (size.height / 2)
      val color = if (risky.getOrElse(i) { false }) AlertRed else if (i == chosen) Green else Color.Gray
      drawLine(color.copy(alpha = if (i == chosen) 1f else 0.4f), Offset(cx, cy), Offset(cx, size.height), (if (i == chosen) 2 else 1).dp.toPx())
      drawCircle(Color.White, (if (i == chosen) 7 else 5).dp.toPx(), Offset(cx, cy))
      drawCircle(color, (if (i == chosen) 5f else 3.5f).dp.toPx(), Offset(cx, cy))
    }
  }
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    spots.forEachIndexed { i, s ->
      Pill(s.label + (temps.getOrNull(i)?.let { "  $it°" } ?: ""), i == chosen, dot = risky.getOrElse(i) { false }) { onChoose(i) }
    }
  }
}

/** One hour; a new day starts with its date. A glyph marks 雷阵雨 (red) or rain. */
@Composable
private fun HourRow(t: Long, h: WeatherHour, ele: Double?, zone: TimeZone, nowMs: Long) {
  val hour = SimpleDateFormat("HH", Locale.ROOT).apply { timeZone = zone }.format(t)
  if (hour == "00") BasicText(
    SimpleDateFormat("M月d日 E", Locale.CHINA).apply { timeZone = zone }.format(t),
    Modifier.padding(top = 12.dp, bottom = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 13.sp),
  )
  fun red(on: Boolean) = if (on) Figures.copy(color = AlertRed) else Figures
  Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(if (t <= nowMs) "现在" else "$hour:00", Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, fontFeatureSettings = "tnum"))
    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
      if (h.thunder) Icon(R.drawable.thunderstorm_wght500_24px, "雷阵雨", tint = AlertRed, size = 18.dp)
      else if (h.precip >= 0.1) Icon(R.drawable.rainy_wght500_24px, "有降水", tint = Color.Gray, size = 18.dp)
    }
    BasicText("${Math.round(h.tempAt(ele))}°", Modifier.weight(1f), style = Figures)
    BasicText("${Math.round(h.feelsLikeAt(ele))}°", Modifier.weight(1f), style = red(isFreezing(h, ele)))
    BasicText(String.format(Locale.ROOT, "%.1f", h.precip), Modifier.weight(1.2f), style = red(isHeavyRain(h)))
    BasicText(String.format(Locale.ROOT, "%.1f", h.gust), Modifier.weight(1.2f), style = red(isGale(h)))
  }
}
