package com.starsdom.trail

/** 合并 (#189): tracks by when they began, plans in the order picked. */
fun mergeOrder(tracks: List<TrackSummary>): List<TrackSummary> = if (tracks.all { it.planned }) tracks else tracks.sortedBy { it.startedMs }

/** Whether any two of [tracks] (each its segments) share a moment; those without times share none. */
fun timesOverlap(tracks: List<List<List<TrackPoint>>>): Boolean =
  tracks.mapNotNull { t -> t.flatten().map { it.timeMs }.filter { it != 0L }.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() } }
    .sortedBy { it.first }.zipWithNext().any { (a, b) -> b.first < a.second }

/** Tracks one after another, each its own segments (a gap between them); plans joined into one line, as a <rte>. */
fun mergeSegments(tracks: List<List<List<TrackPoint>>>, planned: Boolean): List<List<TrackPoint>> =
  if (planned) listOf(tracks.flatten().flatten()) else tracks.flatten()
