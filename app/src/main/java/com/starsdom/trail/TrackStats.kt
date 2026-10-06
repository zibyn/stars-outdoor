package com.starsdom.trail

import com.starsdom.trail.track.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance, ascent, descent and time cover the recorded segments only; gaps while paused count for none of them. */
data class TrackStats(val distanceM: Double, val ascentM: Double, val durationMs: Long, val profile: List<Pair<Double, Double>>, val descentM: Double = 0.0)

// ponytail: fixed hysteresis against GPS altitude noise; smooth or use the barometer if ascent reads off in the field.
internal const val ASCENT_THRESHOLD_M = 5.0

fun trackStats(segments: List<List<TrackPoint>>): TrackStats {
  var distance = 0.0
  var ascent = 0.0
  var descent = 0.0
  val profile = mutableListOf<Pair<Double, Double>>()
  for (seg in segments) {
    var ref: Double? = null
    seg.forEachIndexed { i, p ->
      if (i > 0) distance += haversine(seg[i - 1], p)
      val ele = p.ele ?: return@forEachIndexed
      profile += distance to ele
      val r = ref
      if (r == null) ref = ele
      else if (ele - r <= -ASCENT_THRESHOLD_M) { descent += r - ele; ref = ele }
      else if (ele - r >= ASCENT_THRESHOLD_M) { ascent += ele - r; ref = ele }
    }
  }
  val duration = segments.filter { it.isNotEmpty() }.sumOf { it.last().timeMs - it.first().timeMs }
  return TrackStats(distance, ascent, duration, profile, descent)
}

fun haversine(a: TrackPoint, b: TrackPoint): Double {
  val dLat = Math.toRadians(b.lat - a.lat)
  val dLon = Math.toRadians(b.lon - a.lon)
  val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
  return 2 * 6_371_000.0 * asin(sqrt(h))
}
