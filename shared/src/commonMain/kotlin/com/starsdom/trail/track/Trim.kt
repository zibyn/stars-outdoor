package com.starsdom.trail.track


/** 截取 (#88): a planned track's 标注 within this of the piece go with it. */
const val TRIM_WAYPOINT_M = 50.0

/** Points [range] (indices into the flattened [segments]) as they were split: a pause in it stays one. */
fun trimSegments(segments: List<List<TrackPoint>>, range: IntRange): List<List<TrackPoint>> {
  var start = 0
  return segments.mapNotNull { seg ->
    val from = maxOf(range.first - start, 0)
    val to = minOf(range.last - start, seg.lastIndex)
    start += seg.size
    if (from <= to) seg.subList(from, to + 1) else null
  }
}

/** Each point's distance along [segments] (flattened), a pause counting none, as [trackStats]. */
fun alongDistances(segments: List<List<TrackPoint>>): DoubleArray {
  val d = DoubleArray(segments.sumOf { it.size })
  var i = 0
  var total = 0.0
  for (seg in segments) seg.forEachIndexed { j, p ->
    if (j > 0) total += haversine(seg[j - 1], p)
    d[i++] = total
  }
  return d
}

/** The point nearest [m] along, by [distances] (ascending). */
fun nearestIndex(distances: DoubleArray, m: Double): Int {
  val i = distances.asList().binarySearch(m).let { if (it >= 0) it else -it - 1 }
  return if (i == 0) 0 else if (i == distances.size) i - 1 else if (m - distances[i - 1] <= distances[i] - m) i - 1 else i
}

/** Two points or more, and not the whole of its [size] points. */
fun canSaveTrim(size: Int, range: IntRange) = range.last > range.first && !(range.first == 0 && range.last == size - 1)

/** 标注 that go with [piece]: with times, those made within them; without (a plan), those within [TRIM_WAYPOINT_M]. */
fun trimWaypoints(waypoints: List<Waypoint>, piece: List<List<TrackPoint>>): List<Waypoint> {
  val times = piece.flatten().map { it.timeMs }.filter { it != 0L }
  if (times.isEmpty()) return waypoints.filter { distanceToTrackM(it.lat, it.lon, piece) <= TRIM_WAYPOINT_M }
  val span = times.min()..times.max()
  return waypoints.filter { it.timeMs in span }
}
