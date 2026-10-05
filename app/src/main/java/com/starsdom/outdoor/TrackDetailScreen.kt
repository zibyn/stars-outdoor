package com.starsdom.outdoor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/** How far 轨迹详情's drawer is up (ux-v2 §4.2): the 窄条 under a full-screen map, half, or the whole screen. */
enum class DrawerStop { Peek, Half, Full }

/** The 窄条: handle, numbers and 我的位置; the map's buttons sit above it. */
val TrackPeekHeight = 132.dp

/**
 * 轨迹详情's top bar over the map, where the search box was: the name, its source, 关闭 (the track leaves the map),
 * and 沿途天气 (ADR 0011).
 */
@Composable
fun TrackTopBar(name: String, source: String?, planned: Boolean, onClose: () -> Unit, onWeather: () -> Unit) {
  Floating(Modifier.fillMaxWidth(), CircleShape) { Row(
    Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(R.drawable.close_wght500_24px, "关闭轨迹") }
    Column(Modifier.weight(1f).padding(start = 4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        // C2-38: a plan's icon instead of 「（计划）」.
        if (planned) Icon(R.drawable.conversion_path_wght500_24px, stringResource(R.string.planned), Modifier.padding(end = 4.dp), size = 20.dp)
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      source?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onWeather), contentAlignment = Alignment.Center) {
      Icon(R.drawable.partly_cloudy_day_wght500_24px, "沿途天气")
    }
  } }
}

/**
 * 轨迹详情 (ux-v2 §4.2): the track fills the map, its name in [TrackTopBar], and a drawer that pulls down only to the
 * 窄条 (numbers and where I am on it), never away; [TrackTopBar]'s 关闭 or back closes it. Pulled up: the elevation
 * profile (§2.5), 设为参考 and 叠加, 沿线离线地图 (§2.3), the 出发前 battery row, then 改名, 坐标纠偏, export (§2.6),
 * 公开 (§2.8) and 删除 (再点一次, ux-v2 §6.3). 沿途天气 opens the 天气 page on this track (ADR 0010).
 */
@Composable
fun TrackDetailScreen(
  name: String,
  planned: Boolean,
  stats: TrackStats,
  /** Elevation along the track as walked from its 起算点 (§2.7), which [reversed] turns round. */
  profile: List<Pair<Double, Double>>,
  reversed: Boolean,
  onReversed: (Boolean) -> Unit,
  /** The line's colour on the map, which the profile's 里程标注 are edged with. */
  color: Color,
  /** The first point's time; null when the track has none. */
  dateMs: Long?,
  /** Where I am against the track as walked; null: no fix yet. */
  here: AlongTrack?,
  /** Tapping 我的位置 brings the map onto me. */
  onHere: () -> Unit,
  datum: Datum,
  reference: Boolean,
  overlaid: Boolean,
  public: Boolean,
  /** The server has it: deleting it deletes it on my other phones too. */
  synced: Boolean,
  /** Being recorded (paused too): no 删除. */
  recording: Boolean,
  /** The 队伍轨迹 of a trip still on: 删除 only says why not ([onDeleteRefused]). */
  teamTrack: Boolean,
  /** [corridorText]; [onDownload] is null when there's nothing to download (已下载, 下载中, or another package downloading). */
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
  /** Opens the 小抽屉 picking GPX or KML. */
  onExport: () -> Unit,
  onDelete: () -> Unit,
  onDeleteRefused: () -> Unit,
) {
  var stop by rememberSaveable { mutableStateOf(DrawerStop.Peek) }
  var drag by remember { mutableFloatStateOf(0f) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // A Surface, so the whole drawer takes every touch inside it (#138).
    DrawerSurface(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .then(when (stop) { DrawerStop.Peek -> Modifier; DrawerStop.Half -> Modifier.height(maxHeight / 2); DrawerStop.Full -> Modifier.fillMaxHeight() })
        // Full, the 提示条 goes over its foot instead of off the top.
        .then(if (stop == DrawerStop.Full) Modifier else Modifier.hintAnchor()),
    ) { Column(Modifier.then(if (stop == DrawerStop.Full) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()) {
      // Recording, the drawer keeps the 窄条's line at its top (ADR 0012).
      Box(Modifier.padding(horizontal = 16.dp)) { DrawerTopLine() }
      // Handle and 窄条 together: drag up a stop or down one (the 窄条 stays); a tap opens to half, or back down.
      Column(
        Modifier.fillMaxWidth().then(if (stop == DrawerStop.Full) Modifier else Modifier.height(TrackPeekHeight)).draggable(
          rememberDraggableState { drag += it }, Orientation.Vertical,
          onDragStarted = { drag = 0f },
          onDragStopped = {
            if (drag < -60) stop = if (stop == DrawerStop.Peek) DrawerStop.Half else DrawerStop.Full
            else if (drag > 60) stop = if (stop == DrawerStop.Full) DrawerStop.Half else DrawerStop.Peek
          },
        ).clickable { stop = if (stop == DrawerStop.Peek) DrawerStop.Half else DrawerStop.Peek }.padding(horizontal = 16.dp),
      ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
          Box(Modifier.size(40.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
        }
        // The top bar is under the drawer at full screen.
        if (stop == DrawerStop.Full) Text(name, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Stat("距离", String.format(Locale.ROOT, "%.2f km", stats.distanceM / 1000))
          Stat("爬升", "${Math.round(stats.ascentM)} m")
          // A plan has no time of its own.
          if (!planned) {
            val min = stats.durationMs / 60_000
            Stat("用时", String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60))
          }
          dateMs?.let { Stat("日期", SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(it)) }
        }
        // The red of the 我的位置 dot on the map, so the line reads as about it.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onHere), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(10.dp).background(semantic.me, CircleShape))
          Text(hereText(here), Modifier.padding(start = 8.dp))
        }
      }
      if (stop != DrawerStop.Peek) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text("海拔剖面", Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        ElevationProfile(profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp), stats.distanceM, color, atM = here?.atM.orEmpty())
        DirectionChips(reversed, onReversed)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PrimaryButton(if (reference) "不再用作参考轨迹" else "设为参考", enabled = true, onReference, Modifier.weight(1f))
          PrimaryButton(if (overlaid) "取消叠加" else "叠加到地图", enabled = true, onOverlay, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("沿线离线地图：$corridor", Modifier.weight(1f))
          onDownload?.let { Text("下载", Modifier.heightIn(min = 56.dp).clickable(onClick = it).padding(horizontal = 12.dp).wrapContentHeight(), MaterialTheme.colorScheme.primary) }
        }
        if (batteryRow) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onBattery), verticalAlignment = Alignment.CenterVertically) {
          Text("出发前：防止手机在后台停掉记录", Modifier.weight(1f))
          Text("去设置", Modifier.padding(horizontal = 12.dp), MaterialTheme.colorScheme.primary)
        }
        Rename(name, onRename)
        Text("坐标来自（只在中国境内纠偏）", Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (d in Datum.entries) Chip(d.label, d == datum) { onDatum(d) }
        }
        PrimaryButton("导出", enabled = true, onExport, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        PrimaryButton(if (public) "撤回公开" else "公开到周边路网", enabled = true, onPublic, Modifier.fillMaxWidth())
        Text(
          if (public) "他人可在周边路网看到这条轨迹（起点和终点各 200 m 不显示）" else "公开后他人可在周边路网看到，起点和终点各 200 m 自动隐藏，可随时撤回；撤回后，别人已保存的副本无法收回",
          Modifier.padding(top = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
        )
        if (teamTrack) Text(
          "删除", Modifier.heightIn(min = 56.dp).clickable(onClick = onDeleteRefused).padding(horizontal = 12.dp).wrapContentHeight(),
          MaterialTheme.colorScheme.error,
        ) else if (!recording) TapAgain("删除", deleteConfirm(synced, public), onConfirm = onDelete)
      }
    } }
  }
}

/** 改名 (§6.5), among the other properties: the name, and a field once tapped. */
@Composable
private fun Rename(name: String, onRename: (String) -> Unit) {
  var renaming by remember(name) { mutableStateOf<String?>(null) }
  val draft = renaming
  if (draft == null) Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { renaming = name },
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("名称", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Icon(R.drawable.edit_wght500_24px, "改名")
  } else Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicTextField(
      draft, { renaming = it }, Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(8.dp),
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
    Button("保存", primary = true, { onRename(draft); renaming = null }, Modifier)
  }
}

/** 删除轨迹's 再点一次 (ux-v2 §6 文案表): what else goes with it. */
internal fun deleteConfirm(synced: Boolean, public: Boolean) =
  "再点一次删除" + if (!synced) "" else "，其他手机上也会删除" + if (public) "，并从周边路网撤下" else ""

/** 正向 / 反向 (§2.7), as a pair of segment buttons. */
@Composable
internal fun DirectionChips(reversed: Boolean, onReversed: (Boolean) -> Unit) =
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Chip("正向", !reversed) { onReversed(false) }
    Chip("反向", reversed) { onReversed(true) }
  }

@Composable
internal fun RowScope.Chip(label: String, selected: Boolean, weight: Float = 1f, onClick: () -> Unit) = Text(
  label,
  Modifier.weight(weight).border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
    .clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(8.dp),
  if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
)

@Composable
private fun Stat(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleLarge)
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  }
}

/**
 * Elevation over distance, across [lengthM] (else to the last point with an elevation). With [kmColor], each whole km is
 * ticked along the bottom, and 里程标注 as on the map (edged with it) stand on them spaced as there ([kmStep]); [atM]
 * (where I am) draws a line in 我的位置's red at each of those places.
 */
@Composable
internal fun ElevationProfile(profile: List<Pair<Double, Double>>, modifier: Modifier, lengthM: Double? = null, kmColor: Color? = null, atM: List<Double> = emptyList()) {
  if (profile.size < 2) return Text("无海拔数据", modifier, MaterialTheme.colorScheme.onSurfaceVariant)
  val maxDist = (lengthM ?: profile.last().first).coerceAtLeast(1.0)
  val minEle = profile.minOf { it.second }
  val span = (profile.maxOf { it.second } - minEle).coerceAtLeast(1.0)
  val measurer = rememberTextMeasurer()
  val stroke = semantic.stroke
  val plate = remember(kmColor, stroke) { kmColor?.let { PlatePainter(it, stroke) } }
  val line = MaterialTheme.colorScheme.primary
  val grey = MaterialTheme.colorScheme.onSurfaceVariant
  val me = semantic.me
  // As on the map's 里程标注 plates.
  val style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
  Column(modifier) {
    Text("${Math.round(minEle + span)} m", color = grey, style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().weight(1f)) {
      val path = Path()
      profile.forEachIndexed { i, (d, e) ->
        val o = Offset((d / maxDist * size.width).toFloat(), ((1 - (e - minEle) / span) * size.height).toFloat())
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
      }
      drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
      fun x(d: Double) = (d / maxDist * size.width).toFloat()
      if (plate != null) {
        val step = kmStep(size.width.toDp().value / (maxDist / 1000))
        val tick = size.height - 6.dp.toPx()
        for (k in 1..(maxDist / 1000).toInt()) {
          drawLine(grey, Offset(x(k * 1000.0), size.height), Offset(x(k * 1000.0), tick), 1.dp.toPx())
          if (k % step != 0) continue
          val text = measurer.measure("$k", style)
          val w = text.size.width + 10.dp.toPx()
          val h = text.size.height + 2.dp.toPx()
          translate(x(k * 1000.0) - w / 2, tick - h) { with(plate) { draw(Size(w, h)) } }
          drawText(text, topLeft = Offset(x(k * 1000.0) - text.size.width / 2, tick - h + 1.dp.toPx()))
        }
      }
      for (d in atM) drawLine(me, Offset(x(d), 0f), Offset(x(d), size.height), 2.dp.toPx())
    }
    Text("${Math.round(minEle)} m", color = grey, style = MaterialTheme.typography.labelMedium)
  }
}
