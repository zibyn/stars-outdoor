package com.starsdom.trail.track

/** 偏离提醒 thresholds offered in 设置, in metres, and the default (ux-v3 §8.6 第 3 条). */
val OFF_TRACK_CHOICES = listOf(30, 50, 100)
const val OFF_TRACK_M = 50

/** Straight-line distance from (lat, lon) to the nearest segment of the track, in metres. */
fun distanceToTrackM(lat: Double, lon: Double, segments: List<List<TrackPoint>>) = alongTrack(lat, lon, segments).offM

/**
 * Tracks whether fixes are off the 参考轨迹. Goes off beyond [thresholdM], and back only within 80% of it,
 * so GPS jitter around the threshold doesn't repeat the alert.
 */
class OffTrackMonitor(private val segments: List<List<TrackPoint>>, private val thresholdM: Double = OFF_TRACK_M.toDouble()) {
  var off = false
    private set

  fun update(lat: Double, lon: Double) {
    val d = distanceToTrackM(lat, lon, segments)
    off = if (off) d > thresholdM * 0.8 else d > thresholdM
  }
}
