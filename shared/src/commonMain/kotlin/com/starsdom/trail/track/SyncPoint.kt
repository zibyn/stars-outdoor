package com.starsdom.trail.track

import com.starsdom.trail.net.model.SyncPointDto
import com.starsdom.trail.net.option
import com.starsdom.trail.net.orNull

/** A point as stored (before 纠偏) and its segment. */
data class SyncPoint(val segment: Int, val p: TrackPoint)

/** openapi.yaml SyncPoint, as synced and in a 队伍轨迹. */
fun SyncPoint.toDto() = SyncPointDto(p.timeMs, p.lat, p.lon, p.ele.option(), segment)

fun SyncPointDto.toSyncPoint() = SyncPoint(s, TrackPoint(t, lat, lon, ele.orNull()))
