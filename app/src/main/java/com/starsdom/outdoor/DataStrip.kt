package com.starsdom.outdoor

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 数据窄条 and its 上拉面板 (ux-v3 §5.3, §8.3 第 7–11、13、17 条): what they say, written as R4 / R5 / R18 say.

/** How TalkBack reads a value (§4.5): numbers with their units said in words, by the UI. */
sealed interface Speech {
  data class Distance(val m: Double) : Speech
  data class Distances(val ms: List<Double>) : Speech
  data class Duration(val ms: Long) : Speech
  data class Metres(val m: Double) : Speech
}

/** One cell: its [label], its [value] as shown ([format] around it, if any), and how it's read ([speech]; null: as shown). */
data class Cell(@StringRes val label: Int, val value: String, val speech: Speech? = null, @StringRes val format: Int? = null)

/**
 * The 窄条's three cells; [alert] while 偏离 (warning colour and ⚠), [dim] while paused or the fix is poor (the
 * 偏离提醒 is paused then too), [closable] (✕ 取消参考) only when not recording.
 */
data class Strip(val cells: List<Cell>, val alert: Boolean = false, val dim: Boolean = false, val closable: Boolean = false)

/**
 * The 参考轨迹 as walked from its 起算点: [lengthM] long, its elevation [profile] (distance to metres); [at] where the
 * fix is on it, null without a fix; [offTrack] while the 偏离提醒 is on.
 */
data class Reference(val lengthM: Double, val at: AlongTrack?, val profile: List<Pair<Double, Double>>, val offTrack: Boolean = false)

/** R4: 「850 m」, 「3.2 km」, 「12 km」, by the rounded value (999.6 m is 「1.0 km」). */
fun distanceValue(m: Double): String {
  if (m.roundToInt() < 1_000) return "${m.roundToInt()} m"
  val tenths = (m / 100).roundToInt()
  return if (tenths < 100) "${tenths / 10}.${tenths % 10} km" else "${(m / 1000).roundToInt()} km"
}

/** R5, time going on: 「0:05:32」. */
fun clock(ms: Long): String = (ms / 1000).let { s -> String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }

/** R5: 「8′30″ /km」. */
fun paceValue(ms: Long, m: Double): String = (ms / m).roundToLong().let { s -> "${s / 60}′${String.format(Locale.ROOT, "%02d", s % 60)}″ /km" }

/** R4: 「3.2 km/h」. */
fun speedValue(ms: Long, m: Double): String = String.format(Locale.ROOT, "%.1f km/h", m / 1000 / (ms / 3_600_000.0))

private const val DASH = "—"

private fun distanceCell(@StringRes label: Int, m: Double) = Cell(label, distanceValue(m), Speech.Distance(m))
private fun timeCell(@StringRes label: Int, ms: Long) = Cell(label, clock(ms), Speech.Duration(ms))
private fun heightCell(@StringRes label: Int, arrow: String, m: Double) = Cell(label, "$arrow${m.roundToInt()} m", Speech.Metres(m))

/** Ascent from [fromM] along [profile] on, with [trackStats]'s hysteresis; the start's height interpolated. */
fun ascentAfter(profile: List<Pair<Double, Double>>, fromM: Double): Double {
  val i = profile.indexOfFirst { it.first >= fromM }.takeIf { it >= 0 } ?: return 0.0
  var ref = if (i == 0) profile[0].second
  else profile[i - 1].let { (d0, e0) -> profile[i].let { (d1, e1) -> e0 + (e1 - e0) * ((fromM - d0) / (d1 - d0).coerceAtLeast(1e-9)) } }
  var ascent = 0.0
  for ((_, ele) in profile.drop(i)) {
    if (ele - ref <= -ASCENT_THRESHOLD_M) ref = ele
    else if (ele - ref >= ASCENT_THRESHOLD_M) { ascent += ele - ref; ref = ele }
  }
  return ascent
}

/**
 * 沿轨 / 剩余 / 剩余爬升 on [ref]. Off the track, 沿轨 says how far when [recording] isn't, 「不在轨迹上」 when it is
 * (C2-79, C3-19). 剩余 counts from one place on the track only (several: an out-and-back, unknown).
 */
private fun referenceCells(ref: Reference, recording: Boolean): List<Cell> {
  val at = ref.at
  val one = at?.atM?.singleOrNull()
  val along = when {
    at == null -> Cell(R.string.cell_along, DASH)
    at.atM.isEmpty() && recording -> Cell(R.string.cell_along, "", format = R.string.not_on_track)
    at.atM.isEmpty() -> Cell(R.string.cell_along, distanceValue(at.offM), Speech.Distance(at.offM), R.string.off_track_by)
    // 「3.1 / 14 km」: one unit when they share it.
    at.atM.all { it >= 1_000 } -> Cell(R.string.cell_along, at.atM.joinToString(" / ") { distanceValue(it).removeSuffix(" km") } + " km", Speech.Distances(at.atM))
    else -> Cell(R.string.cell_along, at.atM.joinToString(" / ", transform = ::distanceValue), Speech.Distances(at.atM))
  }
  return listOf(
    along,
    one?.let { distanceCell(R.string.cell_left, ref.lengthM - it) } ?: Cell(R.string.cell_left, DASH),
    one?.let { heightCell(R.string.cell_left_ascent, "↑", ascentAfter(ref.profile, it)) } ?: Cell(R.string.cell_left_ascent, DASH),
  )
}

private fun walkedCells(stats: TrackStats, pausedMs: Long?) = listOf(
  distanceCell(R.string.cell_walked, stats.distanceM),
  if (pausedMs != null) timeCell(R.string.cell_paused, pausedMs) else timeCell(R.string.cell_time, stats.durationMs),
  heightCell(R.string.cell_ascent, "↑", stats.ascentM),
)

/**
 * The 窄条: [recording] so far (null when not recording), [pausedMs] how long it's been paused (null when not),
 * [ref] the 参考轨迹 if any, [fixAccuracyM] null without a fix. None without recording or 参考. A poor fix greys the
 * numbers; none at all and what needs one is 「—」, 用时 going on.
 */
fun strip(recording: TrackStats?, pausedMs: Long?, ref: Reference?, fixAccuracyM: Double?): Strip? {
  val dim = pausedMs != null || fixAccuracyM != null && poorFix(fixAccuracyM)
  if (recording == null) return ref?.let { Strip(referenceCells(it, recording = false), dim = dim, closable = true) }
  val walked = walkedCells(recording, pausedMs)
  if (ref == null) return Strip(walked, dim = dim)
  val (_, left, leftAscent) = referenceCells(ref, recording = true)
  // §8.3 第 11 条: 偏离 takes 剩余's place, the whole strip in the warning colour.
  val off = ref.offTrack && pausedMs == null
  val offCell = ref.at?.let { heightCell(R.string.cell_off, "", it.offM) } ?: Cell(R.string.cell_off, DASH)
  return Strip(listOf(if (off) offCell else left, leftAscent, walked[1]), alert = off, dim = dim)
}

/**
 * The 上拉面板 (three columns): the recording's nine, then with a 参考轨迹 its 沿轨 / 剩余 / 剩余爬升. [altitudeM]
 * from the last recorded point, as 爬升 and 最高海拔 are (#139). Under 100 m a pace or speed would be noise.
 */
fun panel(recording: TrackStats, altitudeM: Double?, battery: Int?, ref: Reference?): List<Cell> {
  val moved = recording.distanceM >= 100 && recording.durationMs > 0
  return walkedCells(recording, null) + listOf(
    heightCell(R.string.cell_descent, "↓", recording.descentM),
    altitudeM?.let { heightCell(R.string.cell_altitude, "", it) } ?: Cell(R.string.cell_altitude, DASH),
    recording.profile.maxOfOrNull { it.second }?.let { heightCell(R.string.cell_max_altitude, "", it) } ?: Cell(R.string.cell_max_altitude, DASH),
    Cell(R.string.cell_pace, if (moved) paceValue(recording.durationMs, recording.distanceM) else DASH),
    Cell(R.string.cell_speed, if (moved) speedValue(recording.durationMs, recording.distanceM) else DASH),
    Cell(R.string.cell_battery, battery?.let { "$it%" } ?: DASH),
  ) + ref?.let { referenceCells(it, recording = true) }.orEmpty()
}

/**
 * [stats] with 用时 run on to [now] from its last point or from [since] (started or went on), whichever is later,
 * and stood still from [pausedAt]; and how long it's been paused. Runs without a fix too (§8.3 第 11 条).
 */
fun liveStats(stats: TrackStats, lastPointMs: Long?, since: Long?, pausedAt: Long?, now: Long): Pair<TrackStats, Long?> {
  val upTo = pausedAt ?: now
  val from = maxOf(lastPointMs ?: 0L, since ?: upTo)
  return stats.copy(durationMs = stats.durationMs + (upTo - from).coerceAtLeast(0)) to pausedAt?.let { (now - it).coerceAtLeast(0) }
}

/** The recording as the service last gave it ([liveStats]). */
data class RecordingNow(val stats: TrackStats, val lastPointMs: Long?, val since: Long?, val pausedAt: Long?)

/** [rec] on the wall clock, ticking once a second: 用时 and 已暂停 go on between points. */
@Composable
private fun live(rec: RecordingNow): Pair<TrackStats, Long?> {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
  return liveStats(rec.stats, rec.lastPointMs, rec.since, rec.pausedAt, now)
}

/** A cell's value as shown, its [Cell.format] applied. */
@Composable
private fun shown(c: Cell) = c.format?.let { stringResource(it, c.value) } ?: c.value

/** A cell as TalkBack reads it: 「已走 3.2 公里」. */
@Composable
private fun spoken(c: Cell): String {
  fun km(m: Double) = distanceValue(m).let { v -> if (v.endsWith(" km")) v.removeSuffix(" km") to true else v.removeSuffix(" m") to false }
  @Composable fun distance(m: Double) = km(m).let { (n, isKm) -> stringResource(if (isKm) R.string.speech_km else R.string.speech_m, n) }
  val value = when (val sp = c.speech) {
    null -> shown(c)
    is Speech.Distance -> c.format?.let { stringResource(it, distance(sp.m)) } ?: distance(sp.m)
    is Speech.Distances -> sp.ms.map { distance(it) }.joinToString(" / ")
    is Speech.Metres -> stringResource(R.string.speech_m, sp.m.roundToInt().toString())
    is Speech.Duration -> (sp.ms / 1000).let { s ->
      when {
        s >= 3600 -> stringResource(R.string.speech_hours, s / 3600, s / 60 % 60)
        s >= 60 -> stringResource(R.string.speech_minutes, s / 60, s % 60)
        else -> stringResource(R.string.speech_seconds, s)
      }
    }
  }
  return stringResource(c.label) + " " + value
}

/** A row of cells read as one (§4.5): 「已走 3.2 公里，用时 1 小时 5 分」. */
@Composable
internal fun spokenRow(cells: List<Cell>) = cells.map { spoken(it) }.joinToString("，")

/** What a drawer over the 底栏 keeps at its top while recording (ADR 0012): one read-only line, a tap closes it. */
class DrawerTop(val rec: RecordingNow, val ref: Reference?, val fixAccuracyM: Double?, val onClose: () -> Unit)

val LocalDrawerTop = compositionLocalOf<DrawerTop?> { null }

/** The [DrawerTop] line, if recording; read as one, like the 窄条. */
@Composable
fun DrawerTopLine() {
  val top = LocalDrawerTop.current ?: return
  val (stats, pausedMs) = live(top.rec)
  val s = strip(stats, pausedMs, top.ref, top.fixAccuracyM) ?: return
  val text = s.cells.map { stringResource(it.label) + " " + shown(it) }.joinToString(" · ")
  val speech = spokenRow(s.cells)
  Text(
    text,
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = top.onClose).padding(vertical = Space.M)
      .clearAndSetSemantics { contentDescription = speech },
    if (s.alert) semantic.warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
  )
}

/**
 * The 数据窄条 over the 底栏 (§5.3): three cells in big type, recording ([rec]) or with a 参考轨迹 ([ref]). Recording,
 * a tap or a pull up opens the 上拉面板 ([altitudeM], [battery], and [onStopReference] with a 参考); not recording,
 * it opens the 参考轨迹抽屉 ([onOpenReference]) and ✕ is [onStopReference]. 偏离 turns it the warning colour with
 * ⚠, so it reads without colour too. Numbers just change (§3.6). Map overlays grow with the font to 1.3× (§4.3).
 */
@Composable
fun DataStrip(
  rec: RecordingNow?,
  ref: Reference?,
  fixAccuracyM: Double?,
  altitudeM: Double?,
  battery: Int?,
  onOpenReference: () -> Unit,
  onStopReference: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val (stats, pausedMs) = rec?.let { live(it) } ?: (null to null)
  val s = strip(stats, pausedMs, ref, fixAccuracyM) ?: return
  var open by remember(rec != null) { mutableStateOf(false) }
  var drag by remember { mutableFloatStateOf(0f) }
  val density = LocalDensity.current
  CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
    val shape = if (open) MaterialTheme.shapes.large else CircleShape
    val toggle = { if (rec != null) open = !open else onOpenReference() }
    StripSurface(s.alert, shape, modifier.draggable(
      rememberDraggableState { drag += it }, Orientation.Vertical,
      onDragStarted = { drag = 0f },
      onDragStopped = {
        val far = with(density) { 24.dp.toPx() }
        if (drag < -far && rec != null) open = true else if (drag < -far) onOpenReference() else if (drag > far) open = false
      },
    )) {
      Column {
        if (open && stats != null) Panel(panel(stats, altitudeM, battery, ref), s.dim, ref != null, onStopReference)
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
          if (s.alert) Icon(R.drawable.warning_fill1_24px, null, Modifier.padding(start = Space.L))
          val label = stringResource(if (rec != null) R.string.cell_more else R.string.cell_reference)
          val speech = spokenRow(s.cells)
          Row(
            Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = toggle).padding(horizontal = Space.M)
              .clearAndSetSemantics { contentDescription = speech; onClick(label) { toggle(); true } },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            for (c in s.cells) CellText(stringResource(c.label), shown(c), s.dim, Modifier.weight(1f))
          }
          if (s.closable) Box(Modifier.size(56.dp).clickable(onClick = onStopReference), contentAlignment = Alignment.Center) { Icon(R.drawable.close_wght500_24px, stringResource(R.string.stop_reference)) }
        }
      }
    }
  }
}

/** The strip's ground: a 浮层, or the warning colour while 偏离 (its content in the 描边 colour, which reads on it). */
@Composable
private fun StripSurface(alert: Boolean, shape: Shape, modifier: Modifier, content: @Composable () -> Unit) =
  if (!alert) Floating(modifier.fillMaxWidth(), shape, content)
  else Surface(modifier.fillMaxWidth(), shape, semantic.warn, semantic.stroke, shadowElevation = 2.dp, content = content)

@Composable
internal fun CellText(label: String, value: String, dim: Boolean, modifier: Modifier) = Column(modifier) {
  val color = LocalContentColor.current.let { if (dim) it.copy(alpha = 0.5f) else it }
  Text(value, color = color, maxLines = 1, style = MaterialTheme.typography.headlineSmall)
  Text(label, color = color.copy(alpha = color.alpha * 0.8f), maxLines = 1, style = MaterialTheme.typography.labelMedium)
}

/** The 上拉面板: three columns, then ［取消参考］ with a 参考 (C3-12). */
@Composable
private fun Panel(cells: List<Cell>, dim: Boolean, reference: Boolean, onStopReference: () -> Unit) =
  Column(Modifier.padding(start = Space.L, end = Space.L, top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.M)) {
    for (row in cells.chunked(3)) {
      val speech = spokenRow(row)
      Row(Modifier.clearAndSetSemantics { contentDescription = speech }) {
        for (c in row) CellText(stringResource(c.label), shown(c), dim, Modifier.weight(1f))
      }
    }
    if (reference) Text(
      stringResource(R.string.stop_reference),
      Modifier.heightIn(min = 48.dp).clickable(onClick = onStopReference).padding(vertical = Space.M),
      MaterialTheme.colorScheme.primary, textAlign = TextAlign.Start,
    )
  }
