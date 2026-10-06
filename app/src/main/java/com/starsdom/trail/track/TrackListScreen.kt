package com.starsdom.trail.track

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Icon
import com.starsdom.trail.ui.Page
import com.starsdom.trail.ui.Space
import com.starsdom.trail.ui.Spinner
import com.starsdom.trail.ui.overlay
import com.starsdom.trail.ui.semantic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的轨迹 (ux-v3 §5.5), in its [StopDrawer] at half or full height: 轨迹 (the last to come onto this phone first,
 * §8.5) with ＋ to import (§8.2 第 4 条), and 标注, which lists the 标注组 (#121), then the 标注 in none ([waypoints]),
 * each with its 叠加. [tab] and [listState] live outside, so going into a track and back keeps them.
 */
@Composable
fun ColumnScope.TrackList(
  tab: Int,
  onTab: (Int) -> Unit,
  listState: LazyListState,
  tracks: List<TrackSummary>,
  /** Each track's numbers, as they're worked out ([trackLine]). */
  stats: Map<Long, TrackStats>,
  now: Long,
  reference: Long?,
  /** 叠加: track id → its place in the order overlaid ([Semantic.overlay]). */
  overlays: Map<Long, Int>,
  importing: Boolean,
  onOpen: (Long) -> Unit,
  onOverlay: (Long) -> Unit,
  onImport: () -> Unit,
  groups: List<WaypointGroup>,
  waypoints: List<Waypoint>,
  onWaypoint: (Waypoint) -> Unit,
  onGroup: (Long) -> Unit,
  onGroupShown: (WaypointGroup) -> Unit,
  onWaypointShown: (Waypoint) -> Unit,
  /** 新建标注组: its 小抽屉. */
  onNewGroup: () -> Unit,
  /** 标注 页签's ⋮ → 导出不在组里的标注 (#72); only while there are some. */
  onExportLoose: () -> Unit,
  /** The row lit up a moment ("t5", "g3", "w7"): just made, or back from 撤销. */
  highlighted: String?,
  onBackToMap: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.XS), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.bar_tracks), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    // C2-25: importing, the ＋ turns into a spinner. On 标注 it's 新建组 (C5-09).
    if (tab == 0) Box(
      Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !importing, role = Role.Button, onClick = onImport),
      contentAlignment = Alignment.Center,
    ) { if (importing) Spinner(Modifier.size(24.dp)) else Icon(R.drawable.add_wght500_24px, stringResource(R.string.import_label)) }
    else {
      DrawerIconButton(R.drawable.add_wght500_24px, stringResource(R.string.new_group_short), onNewGroup)
      MoreMenu(if (waypoints.isEmpty()) emptyList() else listOf(stringResource(R.string.export_loose) to onExportLoose))
    }
  }
  PrimaryTabRow(tab, containerColor = Color.Transparent) {
    for ((i, label) in listOf(R.string.tab_tracks, R.string.tab_waypoints).withIndex()) Tab(tab == i, { onTab(i) }, text = { Text(stringResource(label)) })
  }
  if (tab == 0) {
    if (tracks.isEmpty()) EmptyState(
      R.drawable.route_wght500_24px, stringResource(R.string.tracks_empty), stringResource(R.string.import_label), onImport, stringResource(R.string.import_formats),
    )
    else LazyColumn(Modifier.weight(1f), listState) {
      items(tracks, key = { it.id }) { t ->
        TrackRow(t, trackLine(t.startedMs, t.planned, stats[t.id], now), t.id == reference, overlays[t.id]?.let { semantic.overlay(it) }, { onOpen(t.id) }, highlighted == "t${t.id}") { onOverlay(t.id) }
      }
    }
  } else WaypointTab(groups, waypoints, now, highlighted, onGroup, onGroupShown, onWaypoint, onWaypointShown, onBackToMap)
}

/**
 * A track's row (C5-06): the name (a 计划轨迹's after its icon) over [line]; 参考中 and 已公开 as small icons at its end.
 * Only a tap, a long press the same (§8.5 第 3 条). With [onOverlay], its 叠加 switch last: hollow, or a check on the
 * line's [overlay] colour when on. Without, a plain pick (选择模式, e.g. the 队伍轨迹); with [checked] too, a tick box
 * last, holding its [number] when the order is the picking's (合并, #189), greyed and not to be picked unless [enabled].
 */
@Composable
fun TrackRow(
  t: TrackSummary, line: String, reference: Boolean, overlay: Color?, onClick: () -> Unit, highlighted: Boolean = false,
  checked: Boolean? = null, number: Int? = null, enabled: Boolean = true, onOverlay: (() -> Unit)? = null,
) {
  val pick = if (checked == null) Modifier.combinedClickable(onLongClick = onClick, onClick = onClick)
    else Modifier.toggleable(checked, enabled, Role.Checkbox) { onClick() }.alpha(if (enabled) 1f else 0.38f)
  Row(Modifier.fillMaxWidth().highlight(highlighted).then(pick), verticalAlignment = Alignment.CenterVertically) {
    // No ellipsis: at 200 % it wraps (§4.3).
    Column(Modifier.weight(1f).heightIn(min = 56.dp).padding(start = Space.L, top = Space.XS, bottom = Space.XS), Arrangement.Center) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (t.planned) Icon(R.drawable.conversion_path_wght500_24px, stringResource(R.string.planned), Modifier.padding(end = Space.XXS), size = 20.dp)
        Text(t.name)
      }
      if (line.isNotEmpty()) Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    if (reference) Icon(R.drawable.near_me_wght500_24px, stringResource(R.string.reference_on), Modifier.padding(start = Space.XS), semantic.reference, 20.dp)
    if (t.public) Icon(R.drawable.public_wght500_24px, stringResource(R.string.public_on), Modifier.padding(start = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
    when {
      onOverlay != null -> OverlayToggle(overlay, onOverlay)
      number != null -> Box(Modifier.padding(horizontal = Space.M).size(24.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
        Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
      }
      checked != null -> Icon(
        if (checked) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, Modifier.padding(horizontal = Space.M),
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      else -> Spacer(Modifier.width(Space.L))
    }
  }
}

/** 「10月5日 · 3.2 km · ↑860 m」 (C5-06, R4, R5): a plan has no date; the numbers once [stats] are in. */
fun trackLine(startedMs: Long, planned: Boolean, stats: TrackStats?, nowMs: Long): String =
  listOfNotNull(dayText(startedMs, nowMs).takeIf { !planned }, stats?.let { distanceValue(it.distanceM) }, stats?.let { "↑${Math.round(it.ascentM)} m" })
    .joinToString(" · ")

/** A 标注's 「8月10日 · 海拔 2600 m」 (C5-14), or [eleFirst] 「海拔 2600 m · 8月10日」 (C5-17); imported ones may lack either. */
fun waypointLine(w: Waypoint, nowMs: Long, eleFirst: Boolean = false): String {
  val parts = listOfNotNull(w.timeMs.takeIf { it != 0L }?.let { dayText(it, nowMs) }, w.ele?.let { "海拔 ${Math.round(it)} m" })
  return (if (eleFirst) parts.reversed() else parts).joinToString(" · ")
}

/** 「10月5日」, with the year when it isn't this one (R5). */
fun dayText(ms: Long, nowMs: Long): String {
  val year = SimpleDateFormat("yyyy", Locale.CHINA)
  return SimpleDateFormat(if (year.format(Date(ms)) == year.format(Date(nowMs))) "M月d日" else "yyyy年M月d日", Locale.CHINA).format(Date(ms))
}

/** A file with several tracks: the user ticks which to import (§2.6); ← or back drops it (C2-34). */
@Composable
fun ImportPickScreen(fileName: String, tracks: List<ParsedTrack>, checked: Set<Int>, onToggle: (Int) -> Unit, onImport: () -> Unit, onBack: () -> Unit) {
  Page(Modifier.padding(Space.L)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back))
      }
      Text(fileName, Modifier.padding(start = Space.XS), style = MaterialTheme.typography.titleLarge)
    }
    Text(stringResource(R.string.import_pick_count, tracks.size), Modifier.padding(vertical = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.weight(1f)) {
      itemsIndexed(tracks) { i, t ->
        Row(Modifier.fillMaxWidth().toggleable(i in checked, role = Role.Checkbox) { onToggle(i) }.padding(vertical = Space.M), verticalAlignment = Alignment.CenterVertically) {
          Icon(if (i in checked) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, Modifier.padding(end = Space.M))
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (t.planned) Icon(R.drawable.conversion_path_wght500_24px, stringResource(R.string.planned), Modifier.padding(end = Space.XXS), size = 20.dp)
              Text(importName(t, fileName, i, tracks.size))
            }
            Text(distanceValue(remember(t) { trackStats(t.segments).distanceM }), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
    PrimaryButton(stringResource(R.string.import_count, checked.size), enabled = checked.isNotEmpty(), onImport)
  }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
  val c = MaterialTheme.colorScheme
  // Disabled as M3: onSurface at 12 % behind, 38 % on top.
  Text(
    text,
    modifier.background(if (enabled) c.primary else c.onSurface.copy(alpha = 0.12f), CircleShape).clip(CircleShape)
      .clickable(enabled = enabled, onClick = onClick).padding(16.dp),
    if (enabled) c.onPrimary else c.onSurface.copy(alpha = 0.38f), textAlign = TextAlign.Center,
  )
}
