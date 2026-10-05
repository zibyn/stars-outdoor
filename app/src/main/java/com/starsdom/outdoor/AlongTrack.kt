package com.starsdom.outdoor

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** §2.7 沿轨里程 (mvp): beyond this from the 参考轨迹 there's none, 「不在轨迹上」. */
const val ON_TRACK_M = 60.0

/** §3.7 (ux-v2): a fix worse than this greys the 参考轨迹条; elsewhere it's 「定位不准」. */
const val POOR_FIX_M = 50.0

/** A fix that doesn't say how good it is doesn't pass (as for 标注). */
fun poorFix(accuracyM: Double?) = accuracyM == null || accuracyM > POOR_FIX_M

/** Places on the track closer than this along it are the same place (and a track whose ends are this close is a loop). */
private const val SAME_PLACE_M = 250.0

/**
 * The track cut [m] along it: the part before (ending at the cut) and after (starting there); all of it is before
 * when it's shorter. Gaps between segments add nothing, as in [trackStats].
 */
private fun cutAt(segments: List<List<TrackPoint>>, m: Double): Pair<List<List<TrackPoint>>, List<List<TrackPoint>>> {
  var at = 0.0
  for ((j, seg) in segments.withIndex()) for (i in 1 until seg.size) {
    val length = haversine(seg[i - 1], seg[i])
    if (at + length >= m) {
      val cut = lerp(seg[i - 1], seg[i], if (length == 0.0) 0.0 else ((m - at) / length).coerceAtLeast(0.0))
      return segments.take(j) + listOf(seg.take(i) + cut) to listOf(listOf(cut) + seg.drop(i)) + segments.drop(j + 1)
    }
    at += length
  }
  return segments to emptyList()
}

/** The point [m] along the track (its end if it's shorter). */
private fun pointAt(segments: List<List<TrackPoint>>, m: Double): TrackPoint? =
  cutAt(segments, m).second.firstOrNull()?.first() ?: segments.lastOrNull { it.isNotEmpty() }?.last()

private fun lerp(a: TrackPoint, b: TrackPoint, t: Double) =
  TrackPoint(a.timeMs, a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon), a.ele?.let { e -> b.ele?.let { e + t * (it - e) } })

/**
 * Whether the track goes round and ends where it starts, so its 起点 can move. An out-and-back's ends meet too, but
 * it comes back the way it went: the last 100 m head more than 120° off the first 100 m.
 */
fun isLoop(segments: List<List<TrackPoint>>): Boolean {
  val ends = segments.filter { it.isNotEmpty() }
  if (ends.isEmpty()) return false
  val first = ends.first().first()
  val last = ends.last().last()
  if (haversine(first, last) >= SAME_PLACE_M) return false
  val length = trackStats(segments).distanceM
  val out = pointAt(segments, 100.0) ?: return false
  val back = pointAt(segments, length - 100) ?: return false
  val k = cos(Math.toRadians(first.lat))
  val ox = (out.lon - first.lon) * k
  val oy = out.lat - first.lat
  val bx = (last.lon - back.lon) * k
  val by = last.lat - back.lat
  return ox * bx + oy * by > -0.5 * hypot(ox, oy) * hypot(bx, by)
}

/** [atM]: 沿轨里程 in metres from the start, smallest first, empty when off the track; [offM]: distance to the track, at [nearestM] along it. */
data class AlongTrack(val atM: List<Double>, val offM: Double, val nearestM: Double = 0.0)

/**
 * The 参考轨迹's 起算点 (mvp §2.7), kept on this phone only: [reversed] for 反向, and a loop's start moved to
 * [startM] along the track as stored (0: its own start).
 */
data class TrackStart(val reversed: Boolean = false, val startM: Double = 0.0)

/**
 * The track as walked from its 起算点: moved to start [TrackStart.startM] along (the part before it goes on the end),
 * then turned round if [TrackStart.reversed]. 沿轨里程, 里程标注, arrows and 起 / 终 all read off this.
 */
fun oriented(segments: List<List<TrackPoint>>, start: TrackStart): List<List<TrackPoint>> {
  val moved = if (start.startM > 0) cutAt(segments, start.startM).let { (before, after) -> after + before } else segments
  return if (start.reversed) moved.reversed().map { it.reversed() } else moved
}

/** Where a stretch of track passes the fix: [alongM] along it, [offM] off it. */
private data class Hit(val alongM: Double, val offM: Double)

/**
 * Where (lat, lon) projects onto the track, as distances along it. Every stretch within [ON_TRACK_M] counts, so
 * an out-and-back gives both legs; stretches less than [SAME_PLACE_M] apart along the track are one place, taken at
 * its nearest, across the start of a loop ([isLoop]) too. Gaps between segments add nothing, as in [trackStats].
 */
fun alongTrack(lat: Double, lon: Double, segments: List<List<TrackPoint>>): AlongTrack {
  // Local flat projection around the fix: well under 1% off within a few km, plenty for these thresholds.
  val my = 111_320.0
  val mx = my * cos(Math.toRadians(lat))
  val hits = mutableListOf<Hit>()
  var best = Double.MAX_VALUE
  var nearest = 0.0
  var start = 0.0
  for (seg in segments) {
    if (seg.size == 1) hypot((seg[0].lon - lon) * mx, (seg[0].lat - lat) * my).let { if (it < best) { best = it; nearest = start } }
    for (i in 1 until seg.size) {
      val ax = (seg[i - 1].lon - lon) * mx
      val ay = (seg[i - 1].lat - lat) * my
      val dx = (seg[i].lon - lon) * mx - ax
      val dy = (seg[i].lat - lat) * my - ay
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
      val d = hypot(ax + t * dx, ay + t * dy)
      val length = haversine(seg[i - 1], seg[i])
      if (d < best) { best = d; nearest = start + t * length }
      if (d <= ON_TRACK_M) hits += Hit(start + t * length, d)
      start += length
    }
  }
  val loop = isLoop(segments)
  fun same(a: Hit, b: Hit) = abs(a.alongM - b.alongM).let { it < SAME_PLACE_M || loop && start - it < SAME_PLACE_M }
  val places = mutableListOf<Hit>()
  for (h in hits.sortedBy { it.alongM }) {
    val i = places.indexOfFirst { same(it, h) }
    if (i < 0) places += h else if (h.offM < places[i].offM) places[i] = h
  }
  return AlongTrack(places.map { it.alongM }.sorted(), best, nearest)
}

/** Metres as km to one decimal, as 沿轨里程 is shown. */
fun kmText(m: Double): String = String.format(Locale.ROOT, "%.1f", m / 1000)

/** Several 沿轨里程, smallest first as they come: 「3.1 / 13.7 km」. */
fun kmsText(ms: List<Double>): String = ms.joinToString(" / ", transform = ::kmText) + " km"

/**
 * 轨迹详情's second line (C2-49…51), any track, 参考 or not, [lengthM] long as walked: 「沿轨 3.2 km · 剩余 15 km」, or
 * several places with no 剩余, or 「距我 2.4 km」 off it; [at] null: no fix yet, 「—」.
 */
fun hereLine(at: AlongTrack?, lengthM: Double): String {
  val one = at?.atM?.singleOrNull()
  return when {
    at == null -> "—"
    at.atM.isEmpty() -> "距我 ${distanceValue(at.offM)}"
    one != null -> "沿轨 ${distanceValue(one)} · 剩余 ${distanceValue(lengthM - one)}"
    // 「3.1 / 14 km」: one unit when they share it, as the 窄条 writes it.
    at.atM.all { it >= 1_000 } -> "沿轨 " + at.atM.joinToString(" / ") { distanceValue(it).removeSuffix(" km") } + " km"
    else -> "沿轨 " + at.atM.joinToString(" / ", transform = ::distanceValue)
  }
}
