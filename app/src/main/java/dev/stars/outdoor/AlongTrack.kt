package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** §2.7 沿轨里程 (mvp): beyond this from the 参考轨迹 there's none, 「不在轨迹上」. */
const val ON_TRACK_M = 60.0

/** §3.7 (ux-v2): a fix worse than this greys the 参考轨迹条; elsewhere it's 「定位不准」. */
const val POOR_FIX_M = 50.0

/** A fix that doesn't say how good it is doesn't pass (as for 标注). */
fun poorFix(accuracyM: Double?) = accuracyM == null || accuracyM > POOR_FIX_M

/** Places on the track closer than this along it are the same place (and a track whose ends are this close is a loop). */
private const val SAME_PLACE_M = 250.0

/** [atM]: 沿轨里程 in metres from the start, smallest first, empty when off the track; [offM]: distance to the track. */
data class AlongTrack(val atM: List<Double>, val offM: Double)

/** Where a stretch of track passes the fix: [alongM] along it, [offM] off it, heading ([dx], [dy]) unscaled. */
private data class Hit(val alongM: Double, val offM: Double, val dx: Double, val dy: Double)

/**
 * Where (lat, lon) projects onto the track, as distances along it. Every stretch within [ON_TRACK_M] counts, so
 * an out-and-back gives both legs; stretches less than [SAME_PLACE_M] apart along the track are one place, taken at
 * its nearest. Across the start of a loop too, but not if they run opposite ways: an out-and-back's ends meet
 * as well. Gaps between segments add nothing, as in [trackStats].
 */
fun alongTrack(lat: Double, lon: Double, segments: List<List<TrackPoint>>): AlongTrack {
  // Local flat projection around the fix: well under 1% off within a few km, plenty for these thresholds.
  val my = 111_320.0
  val mx = my * cos(Math.toRadians(lat))
  val hits = mutableListOf<Hit>()
  var best = Double.MAX_VALUE
  var start = 0.0
  for (seg in segments) {
    if (seg.size == 1) best = minOf(best, hypot((seg[0].lon - lon) * mx, (seg[0].lat - lat) * my))
    for (i in 1 until seg.size) {
      val ax = (seg[i - 1].lon - lon) * mx
      val ay = (seg[i - 1].lat - lat) * my
      val dx = (seg[i].lon - lon) * mx - ax
      val dy = (seg[i].lat - lat) * my - ay
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
      val d = hypot(ax + t * dx, ay + t * dy)
      val length = haversine(seg[i - 1], seg[i])
      best = minOf(best, d)
      if (d <= ON_TRACK_M) hits += Hit(start + t * length, d, dx, dy)
      start += length
    }
  }
  val ends = segments.filter { it.isNotEmpty() }
  val loop = ends.isNotEmpty() && haversine(ends.first().first(), ends.last().last()) < SAME_PLACE_M
  fun same(a: Hit, b: Hit): Boolean {
    val gap = abs(a.alongM - b.alongM)
    // Headings more than 120° apart are the two legs of an out-and-back.
    return gap < SAME_PLACE_M || loop && start - gap < SAME_PLACE_M && a.dx * b.dx + a.dy * b.dy > -0.5 * hypot(a.dx, a.dy) * hypot(b.dx, b.dy)
  }
  val places = mutableListOf<Hit>()
  for (h in hits.sortedBy { it.alongM }) {
    val i = places.indexOfFirst { same(it, h) }
    if (i < 0) places += h else if (h.offM < places[i].offM) places[i] = h
  }
  return AlongTrack(places.map { it.alongM }.sorted(), best)
}

/** What the 参考轨迹条 says (ux-v2 §3.2): [value] in big type, [side] on the right; [grey]: the fix isn't good enough. */
data class ReferenceBarText(val label: String, val value: String, val side: String, val grey: Boolean)

/** [at] null: no fix yet (the 状态条 says 正在定位). [accuracyM] null with a fix: it doesn't say, so it doesn't pass. */
fun referenceBarText(at: AlongTrack?, accuracyM: Double?, lengthM: Double): ReferenceBarText {
  fun km(m: Double) = String.format(Locale.ROOT, "%.1f", m / 1000)
  val poor = at != null && poorFix(accuracyM)
  val value = when {
    at == null -> "—"
    at.atM.isEmpty() -> "不在轨迹上"
    else -> at.atM.joinToString(" / ", transform = ::km) + " km"
  }
  val side = listOfNotNull(
    at?.takeIf { it.atM.isEmpty() }?.let { "离轨迹 ${it.offM.roundToInt()} m" },
    at?.let { if (poor) "精度差" + accuracyText(accuracyM) else accuracyText(accuracyM).trim() },
    "全长 ${km(lengthM)} km",
  ).joinToString(" · ")
  // ponytail: always 正向 until the 起算点 setting comes.
  return ReferenceBarText("沿轨里程 · 正向", value, side, poor)
}

/** 参考轨迹条: under the top bar while planning with a 参考轨迹. */
@Composable
fun ReferenceBar(text: ReferenceBarText, modifier: Modifier = Modifier) = Row(
  modifier.fillMaxWidth().border(1.5.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).background(Color.White, RoundedCornerShape(16.dp))
    .padding(horizontal = 16.dp, vertical = 8.dp),
  verticalAlignment = Alignment.CenterVertically,
) {
  Column(Modifier.weight(1f)) {
    BasicText(text.label, style = TextStyle(color = Color.Gray, fontSize = 13.sp))
    BasicText(text.value, style = TextStyle(color = if (text.grey) Color.Gray else Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
  }
  BasicText(text.side, Modifier.padding(start = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.End))
}
