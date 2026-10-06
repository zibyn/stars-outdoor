package com.starsdom.trail.track

// The core of 轨迹, 标注 and 标注组, shared with iOS.

/** [timeMs] is 0 when unknown (an imported GPX <rte>, or a line without times). */
data class TrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?)

/** A row of 我的轨迹: [startedMs] is when it was walked (its date), [public] 已公开. */
data class TrackSummary(val id: Long, val name: String, val planned: Boolean, val startedMs: Long = 0, val public: Boolean = false)

/**
 * 标注. [trackId] is set when it was added while recording (or imported with a track), else it may be in 标注组
 * [groupId]; [photo] is a file path. [shown]: 叠加 on, its group's if in one (a track's goes with the track).
 */
data class Waypoint(
  val id: Long, val trackId: Long?, val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?,
  val name: String, val description: String, val photo: String?, val groupId: Long? = null, val shown: Boolean = true,
)

/** 标注组 with its [count] of 标注; [shown]: 叠加 on. */
data class WaypointGroup(val id: Long, val name: String, val shown: Boolean, val count: Int)

/** [name], or numbered with the first 「 (n)」 not [taken] (a number it had is replaced, not added to). */
fun uniqueName(name: String, taken: Set<String>): String {
  if (name !in taken) return name
  val base = name.replace(Regex(""" \(\d+\)$"""), "")
  return generateSequence(1) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
}
