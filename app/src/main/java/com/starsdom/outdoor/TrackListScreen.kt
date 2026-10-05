package com.starsdom.outdoor

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
  /** 新建标注组; false if the name is taken. */
  onNewGroup: (String) -> Boolean,
) {
  Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.XS), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.bar_tracks), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    // C2-25: importing, the ＋ turns into a spinner.
    if (tab == 0) Box(
      Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !importing, role = Role.Button, onClick = onImport),
      contentAlignment = Alignment.Center,
    ) { if (importing) Spinner(Modifier.size(24.dp)) else Icon(R.drawable.add_wght500_24px, stringResource(R.string.import_label)) }
  }
  PrimaryTabRow(tab, containerColor = Color.Transparent) {
    for ((i, label) in listOf(R.string.tab_tracks, R.string.tab_waypoints).withIndex()) Tab(tab == i, { onTab(i) }, text = { Text(stringResource(label)) })
  }
  if (tab == 0) {
    if (tracks.isEmpty()) EmptyTracks(onImport)
    else LazyColumn(Modifier.weight(1f), listState) {
      items(tracks, key = { it.id }) { t ->
        TrackRow(t, trackLine(t.startedMs, t.planned, stats[t.id], now), t.id == reference, overlays[t.id]?.let { semantic.overlay(it) }, { onOpen(t.id) }) { onOverlay(t.id) }
      }
    }
  } else LazyColumn(Modifier.weight(1f).padding(horizontal = Space.L)) {
    item {
      var naming by rememberSaveable { mutableStateOf(false) }
      if (naming) NameEntry("", "建立") { if (onNewGroup(it)) naming = false }
      else Text("新建标注组", Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { naming = true }.wrapContentHeight(), MaterialTheme.colorScheme.primary)
    }
    items(groups, key = { "g${it.id}" }) { g ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${g.name}（${g.count} 个）", Modifier.weight(1f).heightIn(min = 56.dp).clickable { onGroup(g.id) }.wrapContentHeight())
        OverlayToggle(MaterialTheme.colorScheme.primary.takeIf { g.shown }) { onGroupShown(g) }
      }
    }
    items(waypoints, key = { "w${it.id}" }) { w ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WaypointRow(w, Modifier.weight(1f)) { onWaypoint(w) }
        OverlayToggle(MaterialTheme.colorScheme.primary.takeIf { w.shown }) { onWaypointShown(w) }
      }
    }
  }
}

/** C5-05: an icon, one line and 导入, with the formats small under it (C2-26). */
@Composable
private fun ColumnScope.EmptyTracks(onImport: () -> Unit) = Column(
  Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.L),
  Arrangement.Center, Alignment.CenterHorizontally,
) {
  Icon(R.drawable.route_wght500_24px, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 48.dp)
  Text(stringResource(R.string.tracks_empty), Modifier.padding(top = Space.M), style = MaterialTheme.typography.titleMedium)
  PrimaryButton(stringResource(R.string.import_label), enabled = true, onImport, Modifier.padding(top = Space.L).widthIn(min = 160.dp))
  Text(stringResource(R.string.import_formats), Modifier.padding(top = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

/**
 * A track's row (C5-06): the name (a 计划轨迹's after its icon) over [line]; 参考中 and 已公开 as small icons at its end.
 * Only a tap, a long press the same (§8.5 第 3 条). With [onOverlay], its 叠加 switch last: hollow, or a check on the
 * line's [overlay] colour when on. Without, a plain pick (选择模式, e.g. the 队伍轨迹).
 */
@Composable
fun TrackRow(t: TrackSummary, line: String, reference: Boolean, overlay: Color?, onClick: () -> Unit, onOverlay: (() -> Unit)? = null) {
  Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = onClick, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
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
    if (onOverlay != null) OverlayToggle(overlay, onOverlay) else Spacer(Modifier.width(Space.L))
  }
}

/** 「10月5日 · 3.2 km · ↑860 m」 (C5-06, R4, R5): a plan has no date; the numbers once [stats] are in. */
fun trackLine(startedMs: Long, planned: Boolean, stats: TrackStats?, nowMs: Long): String =
  listOfNotNull(dayText(startedMs, nowMs).takeIf { !planned }, stats?.let { distanceValue(it.distanceM) }, stats?.let { "↑${Math.round(it.ascentM)} m" })
    .joinToString(" · ")

/** 「10月5日」, with the year when it isn't this one (R5). */
fun dayText(ms: Long, nowMs: Long): String {
  val year = SimpleDateFormat("yyyy", Locale.CHINA)
  return SimpleDateFormat(if (year.format(Date(ms)) == year.format(Date(nowMs))) "M月d日" else "yyyy年M月d日", Locale.CHINA).format(Date(ms))
}

@Composable
internal fun WaypointRow(w: Waypoint, modifier: Modifier, onClick: () -> Unit) {
  Column(modifier.heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
    Text(w.name.ifBlank { "未命名标注" })
    // Imported 标注 may have no time.
    if (w.timeMs != 0L) Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(w.timeMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  }
}

/** 叠加 switch at a row's end (R12): a hollow box, or one filled in [on]'s colour with a check. */
@Composable
private fun OverlayToggle(on: Color?, onToggle: () -> Unit) {
  val label = stringResource(R.string.overlay)
  Box(
    Modifier.size(56.dp).toggleable(on != null, role = Role.Checkbox) { onToggle() }.semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) { Icon(if (on != null) R.drawable.check_box_wght500_24px else R.drawable.check_box_outline_blank_wght500_24px, null, tint = on ?: MaterialTheme.colorScheme.onSurfaceVariant) }
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

/** The file name; with several tracks, plus the track's own name or its number. Names inside files are mostly auto-generated (#123). */
fun importName(t: ParsedTrack, fileName: String, index: Int, count: Int): String {
  val base = fileName.substringBeforeLast('.')
  return when {
    count == 1 -> base
    t.name.isBlank() -> "$base ${index + 1}"
    else -> "$base · ${t.name}"
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
