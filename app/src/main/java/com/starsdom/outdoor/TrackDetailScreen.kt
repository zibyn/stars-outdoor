package com.starsdom.outdoor

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How far a 我的轨迹 drawer is up (ux-v3 §5.5): 轨迹详情's 窄条 alone ([Peek]), half, or the whole screen. */
enum class DrawerStop { Peek, Half, Full }

/** 轨迹详情's 窄条 until it's measured; the map's keys stand on it and the camera fits the track above it. */
val TrackPeekHeight = 180.dp

/**
 * 我的轨迹's drawer (ux-v3 §5.5), whatever is in it: over the map and the 底栏, its height on the spring (§3.4) at
 * [stop], at [DrawerStop.Peek] as tall as its content ([onPeek] gets that). Its handle drags it [onUp] or [onDown] and
 * a tap is [onTap]. Recording, the 窄条's line stays at its top (ADR 0012). A Surface: it takes every touch (#138).
 */
@Composable
fun StopDrawer(stop: DrawerStop, onUp: () -> Unit, onDown: () -> Unit, onTap: () -> Unit, onPeek: (Dp) -> Unit, content: @Composable ColumnScope.() -> Unit) {
  var drag by remember { mutableFloatStateOf(0f) }
  val density = LocalDensity.current
  BoxWithConstraints(Modifier.fillMaxSize()) {
    DrawerSurface(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
        .then(when (stop) { DrawerStop.Peek -> Modifier; DrawerStop.Half -> Modifier.height(maxHeight / 2); DrawerStop.Full -> Modifier.height(maxHeight) })
        // Full, the 提示条 goes over its foot instead of off the top.
        .then(if (stop == DrawerStop.Full) Modifier else Modifier.hintAnchor())
        .onSizeChanged { if (stop == DrawerStop.Peek) onPeek(with(density) { it.height.toDp() }) },
    ) { Column(Modifier.then(if (stop == DrawerStop.Full) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()) {
      Box(
        Modifier.fillMaxWidth().draggable(
          rememberDraggableState { drag += it }, Orientation.Vertical,
          onDragStarted = { drag = 0f },
          onDragStopped = { if (drag < -60) onUp() else if (drag > 60) onDown() },
        // 48 dp tall to hit, the bar in its middle.
        ).clickable(onClick = onTap).padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
      ) { Box(Modifier.size(40.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) }
      Box(Modifier.padding(horizontal = Space.L)) { DrawerTopLine() }
      content()
    } }
  }
}

/** An icon-only button in a drawer: 48 dp to hit (§4.1), its label read out (R26). */
@Composable
internal fun DrawerIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) =
  Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label) }

/**
 * 轨迹详情 (ux-v3 §8.2 第 5–9 条) in the 我的轨迹 drawer. Its 窄条: ← (back to the list, or closed), the name (a 计划轨迹
 * after its icon, 「已公开」 beside it), 沿途天气 (ADR 0011) and ⋮; the four numbers ([detailCells]); where I am
 * ([hereLine]), a tap bringing the map onto me. Pulled up: 设为参考 / 叠加 / 下载沿线 (only the first filled), the
 * elevation profile, the direction, and on the 参考轨迹 the 出发前检查 row ([PreTripRow]).
 * ⋮: 改名, 坐标来源 ([imported] only), 导出, 截取 and 合并 (not while [recording]), 公开 / 撤回公开, 删除 (not while
 * [recording], and a [teamTrack] only says why not).
 */
@Composable
fun ColumnScope.TrackDetail(
  stop: DrawerStop,
  name: String,
  /** Where it came from, small under the name: 「由队伍位置共享生成」, 「来自 佳明 fēnix 7」; null for most. */
  source: String?,
  planned: Boolean,
  public: Boolean,
  stats: TrackStats,
  /** Elevation along the track as walked from its 起算点 (§2.7), which [reversed] turns round. */
  profile: List<Pair<Double, Double>>,
  reversed: Boolean,
  onReversed: (Boolean) -> Unit,
  /** The line's colour on the map, which the profile's 里程标注 are edged with. */
  color: Color,
  /** Where I am against the track as walked; null: no fix yet. */
  here: AlongTrack?,
  onHere: () -> Unit,
  reference: Boolean,
  overlaid: Boolean,
  corridor: Corridor,
  imported: Boolean,
  recording: Boolean,
  teamTrack: Boolean,
  /** The 出发前检查's items not right, on the 参考轨迹 (empty on others and when all are). */
  preTrip: Set<Check>,
  onPreTrip: () -> Unit,
  onBack: () -> Unit,
  onWeather: () -> Unit,
  onReference: () -> Unit,
  onOverlay: () -> Unit,
  onDownload: () -> Unit,
  onRename: () -> Unit,
  onDatum: () -> Unit,
  onExport: () -> Unit,
  onTrim: () -> Unit,
  onMerge: () -> Unit,
  onPublic: () -> Unit,
  onDelete: () -> Unit,
  onDeleteRefused: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS), verticalAlignment = Alignment.CenterVertically) {
    DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onBack)
    // No ellipsis: at 200 % it wraps (§4.3).
    Column(Modifier.weight(1f).padding(horizontal = Space.XXS)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (planned) Icon(R.drawable.conversion_path_wght500_24px, stringResource(R.string.planned), Modifier.padding(end = Space.XXS), size = 20.dp)
        Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
        if (public) Text(
          stringResource(R.string.public_on),
          Modifier.padding(start = Space.XS).border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small).padding(horizontal = Space.XS, vertical = 2.dp),
          MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
        )
      }
      source?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
    }
    DrawerIconButton(R.drawable.partly_cloudy_day_wght500_24px, stringResource(R.string.track_weather), onWeather)
    Box {
      var menu by remember { mutableStateOf(false) }
      DrawerIconButton(R.drawable.more_vert_wght500_24px, stringResource(R.string.more)) { menu = true }
      DropdownMenu(menu, { menu = false }) {
        fun item(@StringRes label: Int, onClick: () -> Unit): @Composable () -> Unit = {
          DropdownMenuItem({ Text(stringResource(label)) }, { menu = false; onClick() }, Modifier.heightIn(min = 48.dp))
        }
        item(R.string.rename, onRename)()
        if (imported) item(R.string.datum, onDatum)()
        item(R.string.export, onExport)()
        if (!recording) item(R.string.trim, onTrim)()
        if (!recording) item(R.string.merge, onMerge)()
        item(if (public) R.string.unpublish else R.string.publish, onPublic)()
        // C2-74: at once, with 撤销 (§8.5 第 15 条).
        if (teamTrack) item(R.string.delete, onDeleteRefused)() else if (!recording) item(R.string.delete, onDelete)()
      }
    }
  }
  val cells = detailCells(stats, planned)
  val speech = spokenRow(cells)
  Row(Modifier.fillMaxWidth().padding(horizontal = Space.L).clearAndSetSemantics { contentDescription = speech }) {
    for (c in cells) CellText(stringResource(c.label), c.value, false, Modifier.weight(1f))
  }
  // The 我的位置 dot's colour, so the line reads as about it.
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onHere).padding(horizontal = Space.L),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(10.dp).background(semantic.me, CircleShape))
    Text(hereLine(here, stats.distanceM), Modifier.padding(start = Space.XS))
  }
  if (stop != DrawerStop.Peek) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.L, end = Space.L, bottom = Space.L)) {
    // §8.2 第 6 条: one filled, and no size on the download.
    Row(Modifier.fillMaxWidth().padding(vertical = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
      Button(onReference, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(if (reference) R.string.stop_reference else R.string.set_reference), textAlign = TextAlign.Center) }
      OutlinedButton(onOverlay, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(if (overlaid) R.string.unoverlay else R.string.overlay), textAlign = TextAlign.Center) }
      OutlinedButton(onDownload, Modifier.weight(1f).heightIn(min = 48.dp), enabled = corridor == Corridor.Download || corridor == Corridor.Update) {
        Text(
          when (corridor) {
            Corridor.Download -> stringResource(R.string.download_corridor)
            Corridor.Update -> stringResource(R.string.update_corridor)
            Corridor.Done -> stringResource(R.string.downloaded)
            Corridor.TooLarge -> stringResource(R.string.reason_too_large)
            is Corridor.Percent -> "${corridor.n}%"
          },
          textAlign = TextAlign.Center,
        )
      }
    }
    ElevationProfile(profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = Space.XS), stats.distanceM, color, atM = here?.atM.orEmpty())
    DirectionRow(reversed, onReversed)
    PreTripRow(preTrip, onPreTrip)
  }
}

/** 「方向 · 正向」 and ⇄ to turn it round (C2-62, §2.7). */
@Composable
internal fun DirectionRow(reversed: Boolean, onReversed: (Boolean) -> Unit) =
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(if (reversed) R.string.direction_reversed else R.string.direction_forward), Modifier.weight(1f))
    DrawerIconButton(R.drawable.swap_horiz_wght500_24px, stringResource(R.string.swap_direction)) { onReversed(!reversed) }
  }

/** A 小抽屉 with a [title], [content], and at its foot 取消 and a filled [confirm] (when given). Rises over the keyboard. */
@Composable
fun ActionSheet(
  title: String,
  modifier: Modifier,
  onCancel: () -> Unit,
  confirm: String? = null,
  confirmEnabled: Boolean = true,
  onConfirm: () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) = Sheet(modifier.imePadding()) {
  Text(title, Modifier.padding(bottom = Space.XS), style = MaterialTheme.typography.titleLarge)
  content()
  if (confirm != null) Row(Modifier.fillMaxWidth().padding(top = Space.M), Arrangement.spacedBy(Space.XS, Alignment.End)) {
    TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
    Button(onConfirm, Modifier.heightIn(min = 48.dp), enabled = confirmEnabled) { Text(confirm) }
  }
}

/**
 * A name typed in a 小抽屉: 改名 (C2-69, C5-28) or 新建标注组 (C5-10, with a [placeholder]). Blank can't be [confirm]ed;
 * one [taken] says so under the field (C5-11) and can't either.
 */
@Composable
fun NameSheet(
  title: String,
  initial: String,
  confirm: String,
  onSave: (String) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier,
  placeholder: String? = null,
  taken: (String) -> Boolean = { false },
) {
  var draft by rememberSaveable { mutableStateOf(initial) }
  val clash = draft.trim() != initial && taken(draft.trim())
  ActionSheet(title, modifier, onCancel, confirm, draft.isNotBlank() && !clash, { onSave(draft.trim()) }) {
    OutlinedTextField(
      draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true, isError = clash,
      label = { Text(stringResource(R.string.name)) },
      placeholder = placeholder?.let { { Text(it) } },
      supportingText = if (clash) { { Text(stringResource(R.string.group_name_taken)) } } else null,
    )
  }
}

/** 坐标来源 (C2-67, C2-68): picking one is it. */
@Composable
fun DatumSheet(datum: Datum, onPick: (Datum) -> Unit, onCancel: () -> Unit, modifier: Modifier) =
  ActionSheet(stringResource(R.string.datum), modifier, onCancel) {
    for (d in Datum.entries) Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(d == datum, role = Role.RadioButton) { onPick(d) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(d == datum, null)
      Text(d.label, Modifier.padding(start = Space.XS))
    }
  }

/** 公开到周边路网 (C2-70): what it means in two lines, then 公开. Not undone by 撤销: others may have saved it. */
@Composable
fun PublicSheet(onPublish: () -> Unit, onCancel: () -> Unit, modifier: Modifier) =
  ActionSheet(stringResource(R.string.publish_title), modifier, onCancel, stringResource(R.string.publish), onConfirm = onPublish) {
    for ((icon, text) in listOf(R.drawable.visibility_wght500_24px to R.string.publish_seen, R.drawable.block_wght500_24px to R.string.publish_hidden)) Row(
      Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(stringResource(text), Modifier.padding(start = Space.M))
    }
  }

/**
 * 导出 (§8.5 第 14 条, C5-31…33): GPX or KML; with [photos] a line that GPX goes as a zip. [busy] is the one being
 * written (true: KML), its row spinning after 300 ms; meanwhile neither can be tapped.
 */
@Composable
fun ExportSheet(photos: Int, busy: Boolean?, onExport: (kml: Boolean) -> Unit, onCancel: () -> Unit, modifier: Modifier) =
  ActionSheet(stringResource(R.string.export), modifier, onCancel) {
    for ((kml, label) in listOf(false to R.string.export_gpx, true to R.string.export_kml)) Row(
      Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = busy == null) { onExport(kml) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(label), Modifier.weight(1f))
      if (busy == kml) {
        // C5-33: past 10 s, it says so.
        var long by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(10_000); long = true }
        if (long) Text(stringResource(R.string.exporting), Modifier.padding(end = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant)
        Spinner(Modifier.size(24.dp))
      }
    }
    if (photos > 0) Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(R.drawable.image_wght500_24px, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(stringResource(R.string.export_photos, photos), Modifier.padding(start = Space.M), MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * 截取 (#88) in 轨迹详情's place: the profile (without elevations, a bar ticked each km) with what's outside [range]
 * faded, two handles under it snapping to the nearest point; at the foot the piece's 距离, 用时 (not on a plan) and 爬升
 * as they move, and 保存 once [canSaveTrim].
 */
@Composable
fun TrimPanel(segments: List<List<TrackPoint>>, planned: Boolean, range: IntRange, onRange: (IntRange) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
  val along = remember(segments) { alongDistances(segments) }
  val total = along.last()
  val span = total.coerceAtLeast(1.0)
  val profile = remember(segments) { trackStats(segments).profile }
  val stats = remember(segments, range) { trackStats(trimSegments(segments, range)) }
  Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS), verticalAlignment = Alignment.CenterVertically) {
    DrawerIconButton(R.drawable.close_wght500_24px, stringResource(R.string.cancel), onCancel)
    Text(stringResource(R.string.trim), Modifier.padding(horizontal = Space.XXS), style = MaterialTheme.typography.titleMedium)
  }
  Column(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.L, bottom = Space.L)) {
    val fade = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    Box(Modifier.fillMaxWidth().height(120.dp).padding(vertical = Space.XS).drawWithContent {
      drawContent()
      val a = (along[range.first] / span * size.width).toFloat()
      val b = (along[range.last] / span * size.width).toFloat()
      drawRect(fade, size = Size(a, size.height))
      drawRect(fade, Offset(b, 0f), Size(size.width - b, size.height))
    }) {
      if (profile.size >= 2) ElevationProfile(profile, Modifier.fillMaxSize(), total)
      else {
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxSize()) {
          val y = size.height / 2
          drawLine(grey, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
          for (k in 0..(total / 1000).toInt()) (k * 1000 / span * size.width).toFloat().let { drawLine(grey, Offset(it, y - 6.dp.toPx()), Offset(it, y + 6.dp.toPx()), 1.dp.toPx()) }
        }
        Text(distanceValue(total), Modifier.align(Alignment.BottomEnd), grey, style = MaterialTheme.typography.labelMedium)
      }
    }
    RangeSlider(
      along[range.first].toFloat()..along[range.last].toFloat(),
      { r -> onRange(nearestIndex(along, r.start.toDouble())..nearestIndex(along, r.endInclusive.toDouble())) },
      // Its handles start at the screen's edges, where a swipe would be 返回.
      Modifier.fillMaxWidth().systemGestureExclusion(), valueRange = 0f..span.toFloat(),
    )
    val cells = listOfNotNull(
      Cell(R.string.cell_distance, distanceValue(stats.distanceM), Speech.Distance(stats.distanceM)),
      if (!planned && stats.durationMs > 0) Cell(R.string.cell_time, hoursMinutes(stats.durationMs), Speech.Duration(stats.durationMs)) else null,
      Cell(R.string.cell_ascent, "↑${Math.round(stats.ascentM)} m", Speech.Metres(stats.ascentM)),
    )
    val speech = spokenRow(cells)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.weight(1f).clearAndSetSemantics { contentDescription = speech }) {
        for (c in cells) CellText(stringResource(c.label), c.value, false, Modifier.weight(1f))
      }
      Button(onSave, Modifier.heightIn(min = 48.dp), enabled = canSaveTrim(along.size, range)) { Text(stringResource(R.string.save)) }
    }
  }
}

/**
 * 合并 (#189): 我的轨迹 to tick, [picked] in the order ticked; those not of the [planned] kind greyed. Plans show their
 * place in that order; tracks go by their start. 合并 once two are ticked.
 */
@Composable
fun MergeSheet(
  tracks: List<TrackSummary>, stats: Map<Long, TrackStats>, nowMs: Long, planned: Boolean, picked: List<Long>,
  onToggle: (Long) -> Unit, onMerge: () -> Unit, onCancel: () -> Unit, modifier: Modifier,
) = ActionSheet(stringResource(R.string.merge), modifier, onCancel, stringResource(R.string.merge), picked.size >= 2, onMerge) {
  LazyColumn(Modifier.heightIn(max = 400.dp)) {
    items(tracks, key = { it.id }) { t ->
      val i = picked.indexOf(t.id)
      TrackRow(
        t, trackLine(t.startedMs, t.planned, stats[t.id], nowMs), reference = false, overlay = null, { onToggle(t.id) },
        checked = i >= 0, number = if (planned && i >= 0) i + 1 else null, enabled = t.planned == planned,
      )
    }
  }
}

/** R5, a finished track's 用时: 「5 h 32 min」, 「32 min」 under the hour. */
fun hoursMinutes(ms: Long): String = (ms / 60_000).let { min -> if (min < 60) "$min min" else "${min / 60} h ${min % 60} min" }

/**
 * 轨迹详情's four numbers (C2-43…46): 距离, 爬升, 下降, 用时; a plan, or a track with no times, has 最高海拔 instead
 * (#148), 「—」 without elevations.
 */
fun detailCells(stats: TrackStats, planned: Boolean): List<Cell> = listOf(
  Cell(R.string.cell_distance, distanceValue(stats.distanceM), Speech.Distance(stats.distanceM)),
  Cell(R.string.cell_ascent, "↑${Math.round(stats.ascentM)} m", Speech.Metres(stats.ascentM)),
  Cell(R.string.cell_descent, "↓${Math.round(stats.descentM)} m", Speech.Metres(stats.descentM)),
  if (!planned && stats.durationMs > 0) Cell(R.string.cell_time, hoursMinutes(stats.durationMs), Speech.Duration(stats.durationMs))
  else stats.profile.maxOfOrNull { it.second }?.let { Cell(R.string.cell_max_altitude, "${Math.round(it)} m", Speech.Metres(it)) }
    ?: Cell(R.string.cell_max_altitude, "—"),
)
