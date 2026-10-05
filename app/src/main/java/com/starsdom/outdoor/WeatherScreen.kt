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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Where 天气 is for (§2.9, ADR 0011): me (地图右上), a long-pressed point, or spots along a track (轨迹详情). */
sealed interface WeatherPlace {
  data object Here : WeatherPlace
  data class Point(val lat: Double, val lon: Double) : WeatherPlace
  data class Track(val id: Long) : WeatherPlace
}

// The weather's own colors, as weather apps paint their icons; 雷阵雨 is the alert red.
private val Sun = Color(0xFFF2A516)
private val CloudGrey = Color(0xFF8A96A3)
private val RainBlue = Color(0xFF2F7FD8)
private val SnowBlue = Color(0xFF4FA3D1)
private val NightBlue = Color(0xFF5C6BC0)
private val Ink = Color(0xFF37474F)
/** Hours between sunset and sunrise, shaded in the 气象图. */
private val NightShade = Color(0xFFE8ECF2)

private val Tabular = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum")

/** The hour's icon and its color; [night] swaps the sun for the moon. */
private fun glyph(h: WeatherHour?, night: Boolean = false): Pair<Int, Color> = when {
  h?.thunder == true -> R.drawable.thunderstorm_wght500_24px to AlertRed
  h?.sky == Sky.Snow -> R.drawable.weather_snowy_wght500_24px to SnowBlue
  h?.sky == Sky.Rain || h?.sky == null && h != null && h.precip >= 0.1 -> R.drawable.rainy_wght500_24px to RainBlue
  h?.sky == Sky.Fog -> R.drawable.foggy_wght500_24px to CloudGrey
  h?.sky == Sky.Cloudy -> R.drawable.cloud_wght500_24px to CloudGrey
  h?.sky == Sky.Clear -> if (night) R.drawable.bedtime_wght500_24px to NightBlue else R.drawable.sunny_wght500_24px to Sun
  else -> if (night) R.drawable.partly_cloudy_night_wght500_24px to NightBlue else R.drawable.partly_cloudy_day_wght500_24px to Sun
}

/** A day's icon: by its [WeatherDay.sky] (rain already in it), 雷阵雨 on top. */
private fun glyph(d: WeatherDay) = glyph(d.hours.first().second.copy(thunder = d.thunder, sky = d.sky, precip = 0.0))

private fun skyText(h: WeatherHour, night: Boolean) = when {
  h.thunder -> "雷阵雨"
  h.sky == Sky.Snow -> "雪"
  h.sky == Sky.Rain || h.sky == null && h.precip >= 0.1 -> "雨"
  h.sky == Sky.Fog -> "雾"
  h.sky == Sky.Cloudy -> "阴"
  h.sky == Sky.Clear -> if (night) "晴夜" else "晴"
  else -> "多云"
}

/** Whether hour [t] (its middle) is between sunset and sunrise at (lat, lon); never during polar day or night. */
private fun night(t: Long, lat: Double, lon: Double, zone: TimeZone): Boolean {
  val mid = t + 1_800_000
  val rise = sunriseMs(lat, lon, t, zone) ?: return false
  val set = sunsetMs(lat, lon, t, zone) ?: return false
  return mid < rise || mid > set
}

/** 地图右上的天气 (§2.9): the icon for where I am this hour, with a red dot when [warn] (出行提醒 in the next 12 h). */
@Composable
fun WeatherChip(w: PlaceWeather?, warn: Boolean, nowMs: Long, onClick: () -> Unit) = MapIconButton(onClick) {
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    val (icon, color) = glyph(w?.at(nowMs), w != null && night(nowMs, w.lat, w.lon, TimeZone.getDefault()))
    Icon(icon, "天气", tint = if (w == null) Color.Black else color)
    if (warn) Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(8.dp).background(AlertRed, CircleShape))
  }
}

/**
 * 天气 (整页, §2.9, ADR 0011): [title] (and [subtitle]: which spot) with 关闭, then [w] as data, no advice: now, the
 * week's days (today from now) to pick one, and that day's 气象图 hour by hour: weather, 气温 curve, 体感, 降水 and
 * 风向 with 阵风, values past the 出行提醒 thresholds in red. [above] goes under the title (沿途天气's profile) and is told
 * the day picked. Greyed once a cached forecast is over 12 h old.
 */
@Composable
fun WeatherScreen(
  title: String,
  w: PlaceWeather?,
  loading: Boolean,
  nowMs: Long,
  onClose: () -> Unit,
  subtitle: String? = null,
  above: (@Composable (day: Int) -> Unit)? = null,
) {
  var day by rememberSaveable { mutableIntStateOf(0) }
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(R.drawable.close_wght500_24px, "关闭天气") }
      Column(Modifier.weight(1f).padding(start = 4.dp)) {
        BasicText(title, style = TextStyle(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { BasicText(it, style = TextStyle(color = Color.Gray, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
      }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      above?.invoke(day)
      if (w == null) {
        BasicText(if (loading) "正在获取天气…" else "暂无天气，联网后再打开", Modifier.padding(16.dp), style = TextStyle(fontSize = 16.sp))
        return@Column
      }
      val zone = remember { TimeZone.getDefault() }
      val old = w.offline && stale(w.fetchedMs, nowMs)
      val days = remember(w, nowMs / 3_600_000) { weatherDays(w, nowMs, zone) }
      if (days.isEmpty()) {
        BasicText("预报已过期，联网后再打开", Modifier.padding(16.dp), style = TextStyle(fontSize = 16.sp))
        return@Column
      }
      val picked = days[day.coerceIn(days.indices)]
      Column(Modifier.alpha(if (old) 0.4f else 1f)) {
        Now(w, days.first().hours.first().second, nowMs, zone)
        Column(Modifier.padding(horizontal = 16.dp)) {
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
        }
        DayStrip(days, days.indexOf(picked), nowMs, zone) { day = it }
        Meteogram(w, picked, nowMs, zone)
        val names = w.forecast.sources.map { if (it == "qweather") "和风天气" else "Open-Meteo" }
        if (names.isNotEmpty()) BasicText("数据：" + names.joinToString(" / "), Modifier.padding(16.dp), style = TextStyle(color = Color.Gray, fontSize = 10.sp))
      }
    }
  }
}

/** Now: the hour's icon and temperature large, then 体感, 阵风 with its direction, and 降水 by their icons. */
@Composable
private fun Now(w: PlaceWeather, h: WeatherHour, nowMs: Long, zone: TimeZone) {
  val isNight = night(nowMs, w.lat, w.lon, zone)
  val (icon, color) = glyph(h, isNight)
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, skyText(h, isNight), tint = color, size = 56.dp)
    BasicText("${Math.round(h.tempAt(w.ele))}°", Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"))
    Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      BasicText(skyText(h, isNight), style = TextStyle(fontSize = 15.sp))
      Metric(R.drawable.accessibility_new_wght500_24px, "体感", "${Math.round(h.feelsLikeAt(w.ele))}°", isFreezing(h, w.ele))
      Metric(R.drawable.air_wght500_24px, "阵风", String.format(Locale.ROOT, "%.1f m/s", h.gust), isGale(h), h.windDir)
      Metric(R.drawable.water_drop_wght500_24px, "降水", String.format(Locale.ROOT, "%.1f mm", h.precip), isHeavyRain(h))
    }
  }
}

/** One of now's figures after its icon, red past its 出行提醒 threshold; [windDir] adds an arrow the way the wind blows. */
@Composable
private fun Metric(@DrawableRes icon: Int, label: String, value: String, alert: Boolean, windDir: Double? = null) = Row(
  Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
) {
  Icon(icon, label, tint = if (alert) AlertRed else Color.Gray, size = 16.dp)
  BasicText(value, Modifier.padding(start = 4.dp), style = Tabular.copy(color = if (alert) AlertRed else Color.Black, fontSize = 14.sp))
  windDir?.let { WindArrow(it, if (alert) AlertRed else Color.Gray, 14.dp, Modifier.padding(start = 4.dp)) }
}

/** An arrow pointing where wind from [from] degrees blows. */
@Composable
private fun WindArrow(from: Double, tint: Color, size: Dp, modifier: Modifier = Modifier) =
  Icon(R.drawable.navigation_wght500_24px, "风向", modifier.rotate((from + 180).toFloat()), tint = tint, size = size)

/** The week, a column a day: name, date, icon, high and low, rain if any; a red dot on days with 雷阵雨, 强降水 or 大风. */
@Composable
private fun DayStrip(days: List<WeatherDay>, picked: Int, nowMs: Long, zone: TimeZone, onPick: (Int) -> Unit) {
  val name = SimpleDateFormat("E", Locale.CHINA).apply { timeZone = zone }
  val date = SimpleDateFormat("M/d", Locale.ROOT).apply { timeZone = zone }
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
    days.forEachIndexed { i, d ->
      val selected = i == picked
      Box(
        Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp))
          .then(if (selected) Modifier.background(Green.copy(alpha = 0.08f)).border(1.dp, Green, RoundedCornerShape(12.dp)) else Modifier)
          .clickable { onPick(i) }.padding(vertical = 8.dp),
      ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          BasicText(if (i == 0) "今天" else name.format(d.startMs), style = TextStyle(fontSize = 13.sp, color = if (selected) Green else Color.Black))
          BasicText(date.format(d.startMs), style = Tabular.copy(fontSize = 11.sp, color = Color.Gray))
          val (icon, color) = glyph(d)
          Icon(icon, null, Modifier.padding(vertical = 6.dp), tint = color, size = 28.dp)
          BasicText("${d.high}°", style = Tabular.copy(fontSize = 15.sp))
          BasicText("${d.low}°", style = Tabular.copy(color = Color.Gray))
          BasicText(if (d.precip >= 0.1) String.format(Locale.ROOT, "%.1f", d.precip) else " ", style = Tabular.copy(fontSize = 11.sp, color = RainBlue))
        }
        if (d.stormy) Box(Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(6.dp).background(AlertRed, CircleShape))
      }
    }
  }
}

// The 气象图's rows, so the fixed icons on the left line up with what scrolls.
private val HourCol = 52.dp
private val TimeRow = 24.dp
private val IconRow = 36.dp
private val CurveRow = 88.dp
private val FeelsRow = 28.dp
private val RainRow = 52.dp
private val WindRow = 52.dp

/**
 * [d]'s hours as a 气象图 that scrolls sideways, each row headed by its icon on the left: time, weather, the 气温 curve,
 * 体感, 降水 bars (square-root scale to 10 mm/h) and 风向 with 阵风. Night hours are shaded; its date and sunrise, sunset above.
 */
@Composable
private fun Meteogram(w: PlaceWeather, d: WeatherDay, nowMs: Long, zone: TimeZone) {
  val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(SimpleDateFormat("M月d日 EEEE", Locale.CHINA).apply { timeZone = zone }.format(d.startMs), Modifier.weight(1f), style = TextStyle(fontSize = 15.sp))
    val rise = sunriseMs(w.lat, w.lon, d.startMs, zone)
    val set = sunsetMs(w.lat, w.lon, d.startMs, zone)
    if (rise != null || set != null) Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
      Icon(R.drawable.wb_twilight_wght500_24px, "日出日落", tint = Color.Gray, size = 16.dp)
      BasicText("${rise?.let(clock::format) ?: "—"} – ${set?.let(clock::format) ?: "—"}", Modifier.padding(start = 4.dp), style = Tabular.copy(color = Color.Gray))
    }
  }
  val hours = d.hours
  val nights = remember(d) { hours.map { (t, _) -> night(t, w.lat, w.lon, zone) } }
  val measurer = rememberTextMeasurer()
  Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
    Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Box(Modifier.height(TimeRow + IconRow))
      RowIcon(R.drawable.device_thermostat_wght500_24px, "气温", null, CurveRow)
      RowIcon(R.drawable.accessibility_new_wght500_24px, "体感", null, FeelsRow)
      RowIcon(R.drawable.water_drop_wght500_24px, "降水", "mm", RainRow)
      RowIcon(R.drawable.air_wght500_24px, "阵风", "m/s", WindRow)
    }
    Column(
      Modifier.weight(1f).horizontalScroll(rememberScrollState()).width(HourCol * hours.size).drawBehind {
        val col = HourCol.toPx()
        nights.forEachIndexed { i, n -> if (n) drawRect(NightShade, Offset(i * col, 0f), Size(col, size.height)) }
      },
    ) {
      Row {
        hours.forEach { (t, _) ->
          BasicText(
            if (t <= nowMs) "现在" else clock.format(t), Modifier.width(HourCol).height(TimeRow).padding(top = 4.dp),
            style = Tabular.copy(color = if (t <= nowMs) Green else Color.Gray, textAlign = TextAlign.Center),
          )
        }
      }
      Row {
        hours.forEachIndexed { i, (_, h) ->
          val (icon, color) = glyph(h, nights[i])
          Box(Modifier.width(HourCol).height(IconRow), contentAlignment = Alignment.Center) { Icon(icon, skyText(h, nights[i]), tint = color, size = 26.dp) }
        }
      }
      val temps = hours.map { it.second.tempAt(w.ele) }
      Canvas(Modifier.width(HourCol * hours.size).height(CurveRow)) {
        val col = HourCol.toPx()
        val top = 24.dp.toPx()
        val bottom = 10.dp.toPx()
        val lo = temps.min()
        val span = (temps.max() - lo).coerceAtLeast(4.0)
        fun at(i: Int) = Offset(col * (i + 0.5f), (top + (1 - (temps[i] - lo) / span) * (size.height - top - bottom)).toFloat())
        val line = Path().apply { temps.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        drawPath(line, Ink, style = Stroke(width = 2.dp.toPx()))
        temps.indices.forEach { i ->
          val p = at(i)
          drawCircle(Color.White, 4.dp.toPx(), p)
          drawCircle(Ink, 2.5.dp.toPx(), p)
          val label = measurer.measure("${Math.round(temps[i])}°", Tabular.copy(color = Ink, fontSize = 14.sp))
          drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - 6.dp.toPx() - label.size.height))
        }
      }
      Row {
        hours.forEach { (_, h) ->
          val cold = isFreezing(h, w.ele)
          Box(Modifier.width(HourCol).height(FeelsRow), contentAlignment = Alignment.Center) {
            BasicText("${Math.round(h.feelsLikeAt(w.ele))}°", style = Tabular.copy(color = if (cold) AlertRed else Color.Gray))
          }
        }
      }
      Canvas(Modifier.width(HourCol * hours.size).height(RainRow)) {
        val col = HourCol.toPx()
        val labelRoom = 16.dp.toPx()
        hours.forEachIndexed { i, (_, h) ->
          if (h.precip < 0.1) return@forEachIndexed
          val color = if (isHeavyRain(h)) AlertRed else RainBlue
          // Square-root scale to 10 mm/h, so the 0.3–2 mm/h of most mountain rain still shows.
          val barH = (kotlin.math.sqrt(h.precip.coerceAtMost(10.0) / 10) * (size.height - labelRoom)).toFloat().coerceAtLeast(2.dp.toPx())
          drawRoundRect(
            color.copy(alpha = 0.75f), Offset(col * i + col * 0.25f, size.height - barH), Size(col * 0.5f, barH),
            CornerRadius(2.dp.toPx()),
          )
          val label = measurer.measure(String.format(Locale.ROOT, "%.1f", h.precip), Tabular.copy(color = color, fontSize = 11.sp))
          drawText(label, topLeft = Offset(col * (i + 0.5f) - label.size.width / 2f, size.height - barH - label.size.height))
        }
      }
      Row {
        hours.forEach { (_, h) ->
          val gale = isGale(h)
          Column(Modifier.width(HourCol).height(WindRow), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            h.windDir?.let { WindArrow(it, if (gale) AlertRed else Ink, 18.dp) }
            BasicText(String.format(Locale.ROOT, "%.0f", h.gust), style = Tabular.copy(color = if (gale) AlertRed else Color.Black))
          }
        }
      }
    }
  }
}

/** A 气象图 row's heading on the left: its icon, and [unit] under it. */
@Composable
private fun RowIcon(@DrawableRes icon: Int, label: String, unit: String?, height: Dp) = Column(
  Modifier.height(height), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
) {
  Icon(icon, label, tint = Color.Gray, size = 18.dp)
  unit?.let { BasicText(it, style = TextStyle(color = Color.Gray, fontSize = 9.sp)) }
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
 * 沿途天气 (ADR 0010): the track's elevation [profile] over [lengthM] with a pin at each of [spots] — red where the
 * day picked has 雷阵雨, 强降水 or 大风 there ([risky]) — and the same spots as choices with that day's high / low ([temps], null
 * while loading). Tapping a pin or a choice picks the spot [chosen].
 */
@Composable
fun TrackSpots(
  profile: List<Pair<Double, Double>>,
  lengthM: Double,
  spots: List<TrackSpot>,
  temps: List<String?>,
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
      Pill(s.label + (temps.getOrNull(i)?.let { "  $it" } ?: ""), i == chosen, dot = risky.getOrElse(i) { false }) { onChoose(i) }
    }
  }
}
