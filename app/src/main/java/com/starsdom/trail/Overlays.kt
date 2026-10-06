package com.starsdom.trail

import com.starsdom.trail.track.Waypoint

// 叠加 (ux-v2 §9.2): my tracks drawn on the map at once, each in its colour. Kept on this phone only.
// 标注组 and 标注 not on a track keep theirs in TrackDb (shown), also on this phone only.

/** Track id → its place in the order overlaid, as "id:n,…". */
const val PREF_OVERLAYS = "overlays"

/**
 * [id] overlaid after the others, as many as you like (ux-v3 §2.4); already there, unchanged. Its place picks the
 * colour ([Semantic.overlay]), kept when an earlier one is taken off; with none left the order starts over.
 */
fun Map<Long, Int>.overlay(id: Long): Map<Long, Int> = if (id in this) this else this + (id to (values.maxOrNull()?.plus(1) ?: 0))

fun overlaysText(m: Map<Long, Int>) = m.entries.joinToString(",") { "${it.key}:${it.value}" }

/** The stored set, less tracks no longer in [tracks]. */
fun readOverlays(text: String?, tracks: Set<Long>): Map<Long, Int> =
  text.orEmpty().split(',').mapNotNull { e ->
    val (id, color) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    (id.toLongOrNull() ?: return@mapNotNull null) to (color.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null)
  }.filter { it.first in tracks }.toMap()

/**
 * 标注 on the map: those of a track drawn there ([drawn]: 轨迹详情, 参考, 叠加, the one recording), and the others
 * with 叠加 on, their own or their 标注组's (#121).
 */
fun shownWaypoints(all: List<Waypoint>, drawn: Set<Long>) = all.filter { if (it.trackId != null) it.trackId in drawn else it.shown }
