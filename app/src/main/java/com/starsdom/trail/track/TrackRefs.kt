package com.starsdom.trail.track

// What this phone keeps by a track's id, never synced: the 参考轨迹 (§2.7), 叠加, each track's 起算点, and the
// 队伍轨迹 it has (§2.11).

/** [starts]: the tracks a 起算点 is kept for. */
data class TrackRefs(val reference: Long?, val overlays: Map<Long, Int>, val starts: Set<Long>, val teamTrack: Long?)

/**
 * [refs] against the tracks [known] here (listed, being recorded, or deleted with 撤销 still on offer): those to a track
 * gone for good — deleted here once 撤销 was over, or on another phone — go. One deleted with 撤销 on offer stays, only
 * not shown meanwhile, so 撤销 brings it all back as it was.
 */
fun knownRefs(refs: TrackRefs, known: Set<Long>) = TrackRefs(
  refs.reference?.takeIf { it in known },
  refs.overlays.filterKeys { it in known },
  refs.starts.filterTo(mutableSetOf()) { it in known },
  refs.teamTrack?.takeIf { it in known },
)

// 叠加 (ux-v2 §9.2): my tracks drawn on the map at once, each in its colour. Kept on this phone only.
// 标注组 and 标注 not on a track keep theirs in TrackDb (shown), also on this phone only.

/** Track id → its place in the order overlaid, as "id:n,…". */
const val PREF_OVERLAYS = "overlays"
