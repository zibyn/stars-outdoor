package com.starsdom.outdoor

/** §2.7 偏离提醒 default threshold. */
const val OFF_TRACK_M = 50.0

/** SharedPreferences key holding the 参考轨迹 id (0 = none). */
const val PREF_REFERENCE = "reference_track"

/**
 * SharedPreferences keys, followed by the track id, holding its 起算点 (mvp §2.7: this phone only, not synced), the
 * same for 轨迹详情 and as 参考轨迹.
 */
// Named before 轨迹详情 shared them; kept so settings already saved stay.
const val PREF_TRACK_REVERSED = "reference_reversed_"
const val PREF_TRACK_START = "reference_start_"

/** Notification id of the 偏离提醒 (1 is the recording's own). */
const val OFF_TRACK_NOTIFICATION = 2

/** Straight-line distance from (lat, lon) to the nearest segment of the track, in metres. */
fun distanceToTrackM(lat: Double, lon: Double, segments: List<List<TrackPoint>>) = alongTrack(lat, lon, segments).offM

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
