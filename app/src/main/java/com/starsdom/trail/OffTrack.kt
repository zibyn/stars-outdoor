package com.starsdom.trail

/** SharedPreferences: the 偏离提醒 threshold picked, read when a recording starts. */
const val PREF_OFF_TRACK = "off_track_m"

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
