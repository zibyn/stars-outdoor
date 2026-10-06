package com.starsdom.trail.weather

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import com.starsdom.trail.R
import com.starsdom.trail.map.MapIconButton
import com.starsdom.trail.recording.clock
import com.starsdom.trail.team.updatedText
import com.starsdom.trail.ui.Icon
import com.starsdom.trail.ui.OfflineStatus
import com.starsdom.trail.ui.Page
import com.starsdom.trail.ui.PageError
import com.starsdom.trail.ui.Space
import com.starsdom.trail.ui.reasonOf
import com.starsdom.trail.ui.semantic
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.datetime.toKotlinTimeZone
import kotlinx.serialization.Serializable

/** Where 天气 is for (§2.9, ADR 0011): me (地图右上), a long-pressed point, or spots along a track (轨迹详情). */
@Serializable sealed interface WeatherPlace {
  @Serializable data object Here : WeatherPlace
  @Serializable data class Point(val lat: Double, val lon: Double, val name: String? = null) : WeatherPlace
  @Serializable data class Track(val id: Long) : WeatherPlace
}

/** The hour's icon and its color (the weather's own, as weather apps paint them; 雷阵雨 paints like 雨, #245); [night] swaps the sun for the moon. */
@Composable
@ReadOnlyComposable
private fun glyph(h: WeatherHour?, night: Boolean = false): Pair<Int, Color> = when {
  h?.thunder == true -> R.drawable.thunderstorm_wght500_24px to semantic.rain
  h?.sky == Sky.Snow -> R.drawable.weather_snowy_wght500_24px to semantic.snow
  h?.sky == Sky.Rain || h?.sky == null && h != null && h.precip >= 0.1 -> R.drawable.rainy_wght500_24px to semantic.rain
  h?.sky == Sky.Fog -> R.drawable.foggy_wght500_24px to semantic.cloud
  h?.sky == Sky.Cloudy -> R.drawable.cloud_wght500_24px to semantic.cloud
  h?.sky == Sky.Clear -> if (night) R.drawable.bedtime_wght500_24px to semantic.night else R.drawable.sunny_wght500_24px to semantic.sun
  else -> if (night) R.drawable.partly_cloudy_night_wght500_24px to semantic.night else R.drawable.partly_cloudy_day_wght500_24px to semantic.sun
}

/** A day's icon: by its [WeatherDay.sky] (rain already in it), 雷阵雨 on top. */
@Composable
@ReadOnlyComposable
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

/** The shared weather logic takes kotlinx-datetime's. */
private val TimeZone.kotlin get() = toZoneId().toKotlinTimeZone()

/** Whether hour [t] (its middle) is between sunset and sunrise at (lat, lon); never during polar day or night. */
private fun night(t: Long, lat: Double, lon: Double, zone: TimeZone): Boolean {
  val mid = t + 1_800_000
  val rise = sunriseMs(lat, lon, t, zone.kotlin) ?: return false
  val set = sunsetMs(lat, lon, t, zone.kotlin) ?: return false
  return mid < rise || mid > set
}

/** 地图右上的天气 (§2.9): the icon for where I am this hour, with a red dot when [warn] (出行提醒 in the next 12 h). */
@Composable
fun WeatherChip(w: PlaceWeather?, warn: Boolean, nowMs: Long, onClick: () -> Unit) = MapIconButton(onClick) {
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    val (icon, color) = glyph(w?.at(nowMs), w != null && night(nowMs, w.lat, w.lon, TimeZone.getDefault()))
    Icon(icon, "天气", tint = if (w == null) LocalContentColor.current else color)
    if (warn) Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
  }
}

/**
 * 天气 (整页, §2.9, ADR 0011, §8.2 第 13 条): [title] with ←, then [w] as data, no advice: now, the week's days (today
 * from now) to pick one, and that day's 气象图 hour by hour: weather, 气温 curve, 体感, 降水 and 风向 with 风力, values past the
 * 出行提醒 thresholds in red. [above] goes under the title (沿途天气's profile) and is told the day picked. A cached
 * forecast says how old it is; over 12 h, it's faded and 「已过期」. Without one: 「定位后显示天气」 when [noFix], a
 * skeleton while [loading], 「联网后自动刷新」 offline, else what went wrong ([error], a server code) with [onRetry].
 */
@Composable
fun WeatherScreen(
  title: String,
  w: PlaceWeather?,
  loading: Boolean,
  nowMs: Long,
  onBack: () -> Unit,
  online: Boolean,
  error: String? = null,
  onRetry: () -> Unit = {},
  noFix: Boolean = false,
  above: (@Composable (day: Int) -> Unit)? = null,
) {
  var day by rememberSaveable { mutableIntStateOf(0) }
  Page {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Icon(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back)) }
      Text(title, Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
    }
    OfflineStatus(online, Modifier.padding(horizontal = Space.L))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      above?.invoke(day)
      val zone = remember { TimeZone.getDefault() }
      val days = w?.let { remember(it, nowMs / 3_600_000) { weatherDays(it, nowMs, zone.kotlin) } }.orEmpty()
      if (w == null || days.isEmpty()) {
        when {
          noFix -> NoWeather(null, stringResource(R.string.weather_no_fix), null) { Text("—", style = MaterialTheme.typography.displaySmall) }
          loading -> Skeleton()
          !online || error == "offline" -> NoWeather(R.drawable.cloud_off_wght500_24px, stringResource(R.string.weather_offline), onRetry)
          error != null -> PageError(
            stringResource(R.string.error_with_reason, stringResource(R.string.result_weather_failed), stringResource(reasonOf(error))), onRetry, Modifier.padding(Space.L),
          )
          // Not asked yet (the moment before loading), or nothing to ask for.
          else -> Unit
        }
        return@Column
      }
      val old = w.offline && stale(w.fetchedMs, nowMs)
      val picked = days[day.coerceIn(days.indices)]
      Column(Modifier.alpha(if (old) 0.4f else 1f)) {
        Now(w, days.first().hours.first().second, nowMs, zone)
        Column(Modifier.padding(horizontal = 16.dp)) {
          if (w.offline) Text(
            listOfNotNull(updatedText(w.fetchedMs, nowMs), stringResource(R.string.weather_expired).takeIf { old }).joinToString(" · "),
            Modifier.padding(top = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
          )
          // Open-Meteo stood in for 和风: its forecast comes without warnings.
          if ("open-meteo" in w.forecast.sources) Text(stringResource(R.string.weather_no_alerts), Modifier.padding(top = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          for (a in w.forecast.alerts) Row(Modifier.fillMaxWidth().padding(top = 12.dp).background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(12.dp)) {
            Icon(R.drawable.warning_fill1_24px, null, tint = MaterialTheme.colorScheme.error, size = 20.dp)
            Column(Modifier.padding(start = 8.dp)) {
              Text(a.title, color = MaterialTheme.colorScheme.onErrorContainer)
              if (a.text.isNotEmpty()) Text(a.text, Modifier.padding(top = 4.dp), MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        DayStrip(days, days.indexOf(picked), zone) { day = it }
        Meteogram(w, picked, nowMs, zone)
      }
    }
  }
}

/** 天气 without a forecast (C2-97, C2-98): [icon] or [top], [text], and 重试 when there's [onRetry]. */
@Composable
private fun NoWeather(@DrawableRes icon: Int?, text: String, onRetry: (() -> Unit)?, top: (@Composable () -> Unit)? = null) = Column(
  Modifier.fillMaxWidth().padding(Space.XL), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.M),
) {
  icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 48.dp) }
  top?.invoke()
  Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
  onRetry?.let { TextButton(it, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) } }
}

/** C2-96: the shape of what's coming, after 300 ms, no words. */
@Composable
private fun Skeleton() {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { delay(300); shown = true }
  if (shown) Column(Modifier.fillMaxWidth().padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.M)) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(160.dp, 56.dp).background(color, MaterialTheme.shapes.small))
    Box(Modifier.fillMaxWidth().height(96.dp).background(color, MaterialTheme.shapes.small))
    Box(Modifier.fillMaxWidth().height(200.dp).background(color, MaterialTheme.shapes.small))
  }
}

/** Now (C2-104): the hour's icon, 「8°」 and its weather, then 「体感 3° · 东北风 3 级」, red past an 出行提醒 threshold. */
@Composable
private fun Now(w: PlaceWeather, h: WeatherHour, nowMs: Long, zone: TimeZone) {
  val isNight = night(nowMs, w.lat, w.lon, zone)
  val (icon, color) = glyph(h, isNight)
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = color, size = 56.dp)
    Text("${Math.round(h.tempAt(w.ele))}°", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.displaySmall)
    Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(skyText(h, isNight))
      Text(
        "体感 ${Math.round(h.feelsLikeAt(w.ele))}° · ${windText(h.windDir, h.gust)}",
        color = if (isFreezing(h, w.ele) || isGale(h)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

/** An arrow pointing where wind from [from] degrees blows. */
@Composable
private fun WindArrow(from: Double, tint: Color, size: Dp, modifier: Modifier = Modifier) =
  Icon(R.drawable.navigation_wght500_24px, "风向", modifier.rotate((from + 180).toFloat()), tint = tint, size = size)

/** The week, a column a day: name, date, icon, high and low, rain if any; a red dot on days with 强降水 or 大风 (#245: 雷阵雨 doesn't mark). */
@Composable
private fun DayStrip(days: List<WeatherDay>, picked: Int, zone: TimeZone, onPick: (Int) -> Unit) {
  // C2-106: 「今天」 / 「周六」 over 「10月5日」.
  val name = SimpleDateFormat("E", Locale.CHINA).apply { timeZone = zone }
  val date = SimpleDateFormat("M月d日", Locale.CHINA).apply { timeZone = zone }
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
    days.forEachIndexed { i, d ->
      val selected = i == picked
      Box(
        Modifier.weight(1f).padding(horizontal = 2.dp).clip(MaterialTheme.shapes.small)
          .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
          .clickable { onPick(i) }.padding(vertical = 8.dp),
      ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(if (i == 0) "今天" else name.format(d.startMs), color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, style = MaterialTheme.typography.bodyMedium)
          Text(date.format(d.startMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          val (icon, color) = glyph(d)
          Icon(icon, null, Modifier.padding(vertical = 6.dp), tint = color, size = 28.dp)
          Text("${d.high}°")
          Text("${d.low}°", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
          Text(if (d.precip >= 0.1) String.format(Locale.ROOT, "%.1f", d.precip) else " ", color = semantic.rain, style = MaterialTheme.typography.labelMedium)
        }
        if (d.stormy) Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
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
  // C2-108: 「10时」.
  val hour = SimpleDateFormat("H时", Locale.CHINA).apply { timeZone = zone }
  // Read in composition for the Canvases below.
  val c = MaterialTheme.colorScheme
  val s = semantic
  val curveLabel = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface)
  val rainLabel = MaterialTheme.typography.labelMedium
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(SimpleDateFormat("M月d日 E", Locale.CHINA).apply { timeZone = zone }.format(d.startMs), Modifier.weight(1f))
    val rise = sunriseMs(w.lat, w.lon, d.startMs, zone.kotlin)
    val set = sunsetMs(w.lat, w.lon, d.startMs, zone.kotlin)
    if (rise != null || set != null) Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
      Icon(R.drawable.wb_twilight_wght500_24px, "日出日落", tint = c.onSurfaceVariant, size = 16.dp)
      Text("${rise?.let(clock::format) ?: "—"} – ${set?.let(clock::format) ?: "—"}", Modifier.padding(start = 4.dp), c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
      RowIcon(R.drawable.air_wght500_24px, "阵风", "级", WindRow)
    }
    Column(
      Modifier.weight(1f).horizontalScroll(rememberScrollState()).width(HourCol * hours.size).drawBehind {
        val col = HourCol.toPx()
        nights.forEachIndexed { i, n -> if (n) drawRect(s.nightShade, Offset(i * col, 0f), Size(col, size.height)) }
      },
    ) {
      Row {
        hours.forEach { (t, _) ->
          Text(
            if (t <= nowMs) "现在" else hour.format(t), Modifier.width(HourCol).height(TimeRow).padding(top = 4.dp),
            color = if (t <= nowMs) c.primary else c.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
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
        drawPath(line, c.onSurface, style = Stroke(width = 2.dp.toPx()))
        temps.indices.forEach { i ->
          val p = at(i)
          drawCircle(c.surface, 4.dp.toPx(), p)
          drawCircle(c.onSurface, 2.5.dp.toPx(), p)
          val label = measurer.measure("${Math.round(temps[i])}°", curveLabel)
          drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - 6.dp.toPx() - label.size.height))
        }
      }
      Row {
        hours.forEach { (_, h) ->
          val cold = isFreezing(h, w.ele)
          Box(Modifier.width(HourCol).height(FeelsRow), contentAlignment = Alignment.Center) {
            Text("${Math.round(h.feelsLikeAt(w.ele))}°", color = if (cold) c.error else c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      Canvas(Modifier.width(HourCol * hours.size).height(RainRow)) {
        val col = HourCol.toPx()
        val labelRoom = 16.dp.toPx()
        hours.forEachIndexed { i, (_, h) ->
          if (h.precip < 0.1) return@forEachIndexed
          val color = if (isHeavyRain(h)) c.error else s.rain
          // Square-root scale to 10 mm/h, so the 0.3–2 mm/h of most mountain rain still shows.
          val barH = (kotlin.math.sqrt(h.precip.coerceAtMost(10.0) / 10) * (size.height - labelRoom)).toFloat().coerceAtLeast(2.dp.toPx())
          drawRoundRect(
            color.copy(alpha = 0.75f), Offset(col * i + col * 0.25f, size.height - barH), Size(col * 0.5f, barH),
            CornerRadius(2.dp.toPx()),
          )
          val label = measurer.measure(String.format(Locale.ROOT, "%.1f", h.precip), rainLabel.copy(color = color))
          drawText(label, topLeft = Offset(col * (i + 0.5f) - label.size.width / 2f, size.height - barH - label.size.height))
        }
      }
      Row {
        hours.forEach { (_, h) ->
          val gale = isGale(h)
          Column(Modifier.width(HourCol).height(WindRow), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            h.windDir?.let { WindArrow(it, if (gale) c.error else c.onSurfaceVariant, 18.dp) }
            Text("${beaufort(h.gust)}", color = if (gale) c.error else Color.Unspecified, style = MaterialTheme.typography.bodyMedium)
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
  Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
  unit?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
}

/** A choice in a row that scrolls sideways: long track names cut short. */
@Composable
private fun Pill(label: String, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) = Row(
  Modifier.heightIn(min = 40.dp).border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent, CircleShape)
    .clickable(onClick = onClick).padding(horizontal = 16.dp),
  verticalAlignment = Alignment.CenterVertically,
) {
  if (dot) Box(Modifier.padding(end = 8.dp).size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
  Text(
    label, Modifier.widthIn(max = 180.dp), if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MaterialTheme.typography.bodyMedium,
  )
}

/**
 * 沿途天气 (ADR 0010): the track's elevation [profile] over [lengthM] with a pin at each of [spots] — red where the
 * day picked has 强降水 or 大风 there ([risky], 雷阵雨 doesn't mark, #245) — and the same spots as choices with that day's high / low ([temps], null
 * while loading). Tapping a pin or a choice picks the spot [chosen]. The heights at the side; without any forecast
 * ([forecast] false) the choices don't show (§8.2 第 13 条).
 */
@Composable
fun TrackSpots(
  profile: List<Pair<Double, Double>>,
  lengthM: Double,
  spots: List<TrackSpot>,
  temps: List<String?>,
  risky: List<Boolean>,
  chosen: Int,
  forecast: Boolean,
  onChoose: (Int) -> Unit,
) {
  val maxDist = lengthM.coerceAtLeast(1.0)
  val minEle = profile.minOfOrNull { it.second } ?: 0.0
  val span = ((profile.maxOfOrNull { it.second } ?: 0.0) - minEle).coerceAtLeast(1.0)
  val c = MaterialTheme.colorScheme
  val measurer = rememberTextMeasurer()
  val axisLabel = MaterialTheme.typography.labelMedium.copy(color = c.onSurfaceVariant)
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
      drawPath(fill, c.primary.copy(alpha = 0.08f))
      drawPath(line, c.primary, style = Stroke(width = 2.dp.toPx()))
      // The 纵轴: highest and lowest, at the side.
      val top = measurer.measure("${Math.round(minEle + span)} m", axisLabel)
      val bottom = measurer.measure("${Math.round(minEle)} m", axisLabel)
      drawText(top, topLeft = Offset(0f, 0f))
      drawText(bottom, topLeft = Offset(0f, size.height - bottom.size.height))
    } else drawLine(c.outlineVariant, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
    spots.forEachIndexed { i, s ->
      val cx = x(s.distM)
      val cy = s.point.ele?.takeIf { profile.size >= 2 }?.let(::y) ?: (size.height / 2)
      val color = if (risky.getOrElse(i) { false }) c.error else if (i == chosen) c.primary else c.onSurfaceVariant
      drawLine(color.copy(alpha = if (i == chosen) 1f else 0.4f), Offset(cx, cy), Offset(cx, size.height), (if (i == chosen) 2 else 1).dp.toPx())
      drawCircle(c.surface, (if (i == chosen) 7 else 5).dp.toPx(), Offset(cx, cy))
      drawCircle(color, (if (i == chosen) 5f else 3.5f).dp.toPx(), Offset(cx, cy))
    }
  }
  if (forecast) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    spots.forEachIndexed { i, s ->
      Pill(s.label + (temps.getOrNull(i)?.let { "  $it" } ?: ""), i == chosen, dot = risky.getOrElse(i) { false }) { onChoose(i) }
    }
  }
}
