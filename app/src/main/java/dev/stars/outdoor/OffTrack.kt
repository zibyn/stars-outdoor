package dev.stars.outdoor

import kotlin.math.cos
import kotlin.math.hypot

/** §2.7 偏离提醒 default threshold. */
const val OFF_TRACK_M = 50.0

/** SharedPreferences key holding the 参考轨迹 id (0 = none). */
const val PREF_REFERENCE = "reference_track"

/** Notification id of the 偏离提醒 (1 is the recording's own). */
const val OFF_TRACK_NOTIFICATION = 2

/** Straight-line distance from (lat, lon) to the nearest segment of the track, in metres. */
fun distanceToTrackM(lat: Double, lon: Double, segments: List<List<TrackPoint>>): Double {
  // Local flat projection around the fix: well under 1% off within a few km, plenty for a 50 m threshold.
  val my = 111_320.0
  val mx = my * cos(Math.toRadians(lat))
  var best = Double.MAX_VALUE
  for (seg in segments) {
    for (i in seg.indices) {
      val ax = (seg[i].lon - lon) * mx
      val ay = (seg[i].lat - lat) * my
      if (i == 0) { best = minOf(best, hypot(ax, ay)); continue }
      val bx = (seg[i - 1].lon - lon) * mx
      val by = (seg[i - 1].lat - lat) * my
      val dx = bx - ax
      val dy = by - ay
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
      best = minOf(best, hypot(ax + t * dx, ay + t * dy))
    }
  }
  return best
}

/**
 * Tracks whether fixes are off the 参考轨迹. Goes off beyond [thresholdM], and back only within 80% of it,
 * so GPS jitter around the threshold doesn't repeat the alert.
 */
class OffTrackMonitor(private val segments: List<List<TrackPoint>>, private val thresholdM: Double = OFF_TRACK_M) {
  var off = false
    private set

  fun update(lat: Double, lon: Double) {
    val d = distanceToTrackM(lat, lon, segments)
    off = if (off) d > thresholdM * 0.8 else d > thresholdM
  }
}
