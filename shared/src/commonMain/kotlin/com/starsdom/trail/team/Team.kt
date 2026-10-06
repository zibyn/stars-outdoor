package com.starsdom.trail.team

// 队伍 (spec §2.11): the team as the server sends it, the 上报 rules, and how teammates are shown.

import com.starsdom.trail.net.model.MessageDto
import com.starsdom.trail.net.model.PositionDto
import com.starsdom.trail.net.model.PositionsDto
import com.starsdom.trail.net.model.TeamCardDto
import com.starsdom.trail.net.model.TeamDto
import com.starsdom.trail.net.model.TeamTrackDto
import com.starsdom.trail.net.model.TeamTrackRequestDto
import com.starsdom.trail.net.option
import com.starsdom.trail.net.orNull
import com.starsdom.trail.net.wire
import com.starsdom.trail.track.SyncPoint
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.TrackStart
import com.starsdom.trail.track.compass
import com.starsdom.trail.track.distanceText
import com.starsdom.trail.track.haversine
import com.starsdom.trail.track.kmText
import com.starsdom.trail.track.kmsText
import com.starsdom.trail.track.radians
import com.starsdom.trail.track.toDto
import com.starsdom.trail.track.toSyncPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** [Prefs]: the id of the team this phone is in (0 = none), and 省电模式. */
const val PREF_TEAM = "team"
const val PREF_TEAM_SAVER = "team_saver"
/** [Prefs]: the seq of the last 队伍对话 message read, for 未读. */
const val PREF_TEAM_READ = "team_read"
/** [Prefs]: 尾迹 shown on the map (§2.11, a layer switch). */
const val PREF_TRAILS = "trails"

data class TeamPosition(val timeS: Long, val lat: Double, val lon: Double, val battery: Int?)

/**
 * 由位置共享生成轨迹 (§2.11): each of my reports during a trip is kept as a [tripLine]; [TRIP_BREAK] marks
 * 停止共享. The last point of a line can be lost to a crash mid-write; [tripSegments] skips what it can't read.
 */
const val TRIP_BREAK = "-"

/** Its source in 我的轨迹 (ux-v2 §4.2). */
const val TRIP_SOURCE = "由队伍位置共享生成"

/**
 * A teammate's 沿轨里程 on the 队伍轨迹 (ux-v2 §4.4), [mate] theirs and [me] mine (null: no fix): every value of
 * theirs; 领先 / 落后 (theirs against mine, along its direction) only when we each have one, and not when that
 * rounds to 0.0 (side by side).
 */
fun mateAlongText(mate: List<Double>, me: List<Double>?): String {
  if (mate.isEmpty()) return "不在轨迹上"
  val text = "沿轨 " + kmsText(mate)
  val gap = mate.singleOrNull()?.let { m -> me?.singleOrNull()?.let { m - it } } ?: return text
  val by = kmText(abs(gap))
  return if (by == "0.0") text else text + (if (gap > 0) " · 领先 " else " · 落后 ") + by + " km"
}

/** [Prefs]: the 队伍轨迹 as this phone has it ([TeamTrackHere.text]). */
const val PREF_TEAM_TRACK = "team_track"

/** The 队伍轨迹 as this phone has it: in [team], as [track] (my copy, or the 发起人's own), at [version] (0: mine). */
data class TeamTrackHere(val team: Long, val track: Long, val version: Long) {
  val text get() = "$team:$track:$version"

  companion object {
    fun parse(text: String?) = text?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 }?.let { TeamTrackHere(it[0], it[1], it[2]) }
  }
}

/**
 * My copy's id (synced, so 32 hex digits) for the 发起人's track [uuid]: the same on each of my phones, so the same
 * 队伍轨迹 is in 我的轨迹 once however often or wherever it comes.
 */
fun teamTrackCopyUuid(uuid: String): String =
  md5("team-track:$uuid".encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** MD5 (RFC 1321), which common code has no library for; only for ids made with it before, never for security. */
internal fun md5(message: ByteArray): ByteArray {
  val s = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)
  val k = IntArray(64) { (abs(sin(it + 1.0)) * 4294967296.0).toLong().toInt() }
  val padded = message.copyOf((message.size + 8) / 64 * 64 + 64).also { it[message.size] = 0x80.toByte() }
  val bits = message.size.toLong() * 8
  for (i in 0 until 8) padded[padded.size - 8 + i] = (bits ushr (8 * i)).toByte()
  val h = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
  for (chunk in padded.indices step 64) {
    val m = IntArray(16) { w -> (0 until 4).fold(0) { acc, b -> acc or ((padded[chunk + w * 4 + b].toInt() and 0xFF) shl (8 * b)) } }
    var (a, b, c, d) = h
    for (i in 0 until 64) {
      val (f, g) = when (i / 16) {
        0 -> (b and c or (b.inv() and d)) to i
        1 -> (d and b or (d.inv() and c)) to (5 * i + 1) % 16
        2 -> (b xor c xor d) to (3 * i + 5) % 16
        else -> (c xor (b or d.inv())) to 7 * i % 16
      }
      val rotated = (a + f + k[i] + m[g]).rotateLeft(s[i / 16 * 4 + i % 4])
      a = d; d = c; c = b; b += rotated
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d
  }
  return ByteArray(16) { (h[it / 4] ushr (8 * (it % 4))).toByte() }
}

/** [Prefs]: the trip (team id) a recording ran during. */
const val PREF_TRIP_RECORDED = "trip_recorded"

fun tripLine(p: TeamPosition) = "${p.timeS},${p.lat},${p.lon}"

/** The kept reports as a track, broken at 停止共享. */
fun tripSegments(lines: List<String>): List<List<TrackPoint>> {
  val segments = mutableListOf<List<TrackPoint>>()
  var current = mutableListOf<TrackPoint>()
  fun cut() {
    if (current.isNotEmpty()) segments += current
    current = mutableListOf()
  }
  for (line in lines) {
    if (line == TRIP_BREAK) { cut(); continue }
    val (t, lat, lon) = line.split(',').takeIf { it.size == 3 } ?: continue
    current += TrackPoint((t.toLongOrNull() ?: continue) * 1000, lat.toDoubleOrNull() ?: continue, lon.toDoubleOrNull() ?: continue, null)
  }
  cut()
  return segments
}

/** A member and their 尾迹 (oldest first); [avatar] their 头像 id, null for none. */
data class TeamMember(val id: Long, val name: String, val sharing: Boolean, val trail: List<TeamPosition>, val avatar: String? = null)

/**
 * A 队伍对话 message (openapi.yaml Message): [kind] is text, location, image or system (the
 * server's note, e.g. of a 队伍轨迹 change), each with its fields. [from] is null once the sender's account is deleted; [seq] orders it and marks what's read.
 */
data class TeamMessage(
  val seq: Long, val from: Long?, val name: String, val timeS: Long, val kind: String,
  val text: String? = null, val lat: Double? = null, val lon: Double? = null, val image: String? = null,
  /** The sender's 沿轨里程 on the 队伍轨迹 when sent: empty off it, null without one (§2.11). */
  val along: List<Double>? = null,
)

/**
 * The 队伍轨迹 (openapi.yaml TeamTrackRef, §2.11) without its points: [version] grows with every change; [uuid] is
 * the 发起人's track, so it comes into 我的轨迹 once; [start] is its 起算点.
 */
data class TeamTrackRef(val version: Long, val uuid: String, val name: String, val start: TrackStart)

/** [me] is this phone's account; [cursor] is what the server has sent so far, to resume from; [track] the 队伍轨迹. */
data class Team(
  val id: Long, val code: String, val initiator: Long, val me: Long, val ended: Boolean, val cursor: Long, val members: List<TeamMember>,
  val messages: List<TeamMessage> = emptyList(), val track: TeamTrackRef? = null,
)

/** A TeamTrackRequest: track [segments] (WGS-84) as the 发起人 gives them, with its 起算点. */
fun teamTrackJson(uuid: String, name: String, start: TrackStart, segments: List<List<TrackPoint>>): String = wire.encodeToString(
  TeamTrackRequestDto(uuid, name, start.reversed, start.startM, segments.flatMapIndexed { s, seg -> seg.map { SyncPoint(s, it).toDto() } }),
)

/** The server's TeamTrack: its ref and its points as segments. */
fun parseTeamTrack(json: String): Pair<TeamTrackRef, List<List<TrackPoint>>> {
  val t = wire.decodeFromString<TeamTrackDto>(json)
  val segments = t.points.map { it.toSyncPoint() }.groupBy({ it.segment }) { it.p }.values.toList()
  return TeamTrackRef(t.version, t.uuid, t.name, TrackStart(t.reversed, t.start)) to segments
}

/**
 * Whether a 队伍轨迹 just come becomes my 参考轨迹 (§2.11): the first one of the trip always does; on 更换, only if
 * I'm still on the last one ([lastTeamTrack], my copy of it), not once I chose another or none.
 */
fun followTeamTrack(reference: Long?, lastTeamTrack: Long?): Boolean = lastTeamTrack == null || reference == lastTeamTrack

fun MessageDto.toMessage() = TeamMessage(seq, from.orNull(), name, time, kind.value, text.orNull(), lat.orNull(), lon.orNull(), image.orNull(), along.orNull())

/** A MessageRequest: [kind] and what it carries; [key] the same on every resend, so the server stores it once. */
fun messageJson(
  kind: String, text: String? = null, lat: Double? = null, lon: Double? = null, image: String? = null, along: List<Double>? = null,
  key: String? = null,
): String = buildJsonObject {
  put("kind", kind)
  key?.let { put("key", it) }
  text?.let { put("text", it) }
  lat?.let { put("lat", it) }
  lon?.let { put("lon", it) }
  image?.let { put("image", it) }
  along?.let { a -> putJsonArray("along") { a.forEach { add(it) } } }
}.toString()

/** 「📍 位置 · 1.3 km」, [awayM] from me (C4-32): 「—」 without a fix; my own just 「📍 位置」. */
fun locationLine(awayM: Double?, mine: Boolean) = if (mine) "📍 位置" else "📍 位置 · " + (awayM?.let(::distanceText) ?: "—")

/** C4-29: 「小李 · 7:52」 today, else 「小李 · 10月5日 7:52」. */
fun senderLine(name: String, timeS: Long, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()) = name + " · " + chatTime(timeS, nowMs, zone)

/** 「7:52」 today, else 「10月5日 7:52」. */
fun chatTime(timeS: Long, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
  val at = Instant.fromEpochSeconds(timeS).toLocalDateTime(zone)
  val clock = "${at.hour}:${at.minute.toString().padStart(2, '0')}"
  return if (at.date == Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date) clock else "${at.month.ordinal + 1}月${at.day}日 $clock"
}

/** What [this] says, in a notification or a one-line preview. */
fun TeamMessage.summary(): String = when (kind) {
  "location" -> "[位置]"
  "image" -> "[图片]"
  else -> text.orEmpty()
}

/** Teammates' messages in [t] after [readSeq]. */
fun unread(t: Team, readSeq: Long): List<TeamMessage> = t.messages.filter { it.seq > readSeq && it.from != t.me }

/** [w] × [h] scaled down (never up) to [long] on the long side. */
fun fitLongSide(w: Int, h: Int, long: Int): Pair<Int, Int> {
  val side = maxOf(w, h)
  if (side <= long) return w to h
  return (w.toLong() * long / side).toInt() to (h.toLong() * long / side).toInt()
}

/** Where 邀请 sends people without the app (§8.4 第 11 条): the latest GitHub Release, for now the one place to change. */
const val DOWNLOAD_URL = "https://github.com/zibyn/stars-trail/releases/latest"

/**
 * The 邀请 text (C4-53). Kept here, not in strings.xml, because [clipboardCode] reads it back: the two change together.
 */
fun inviteText(code: String) = "加入我的队伍：在星径里输入加入码 $code\n还没装星径？下载：$DOWNLOAD_URL"

/** The code in an invitation on the clipboard (「加入码 NNNN」, §8.4 第 1 条), or null: any four digits won't do. */
fun clipboardCode(text: CharSequence?): String? = text?.let { Regex("加入码\\s*([0-9]{4})(?![0-9])").find(it)?.groupValues?.get(1) }

/** The 队伍卡片 (openapi.yaml TeamCard, C4-07): the 发起人's 昵称 and 头像, how many are in it, when it was made. */
data class TeamCard(val id: Long, val initiator: String, val avatar: String?, val members: Int, val createdS: Long)

fun TeamCardDto.toCard() = TeamCard(id, initiator, initiatorAvatar.orNull(), members, createdAt)

/** 「3 人 · 25 分钟前建」. */
fun cardLine(c: TeamCard, nowMs: Long) = "${c.members} 人 · ${agoText(c.createdS, nowMs)}建"

/** The server's Team (openapi.yaml), as answered or in a live frame. Positions come in the order stored: [mergeTeam] sorts them. */
fun TeamDto.toTeam() = Team(
  id, code, initiator, me, ended, cursor,
  members.map { m -> TeamMember(m.id, m.name, m.sharing, m.positions.map { TeamPosition(it.time, it.lat, it.lon, it.battery.orNull()) }, m.avatar.orNull()) },
  messages.map { it.toMessage() },
  track.orNull()?.let { TeamTrackRef(it.version, it.uuid, it.name, TrackStart(it.reversed, it.start)) },
)

fun parseTeam(json: String) = wire.decodeFromString<TeamDto>(json).toTeam()

fun positionsDto(ps: List<TeamPosition>) = PositionsDto(ps.map { PositionDto(it.timeS, it.lat, it.lon, it.battery.option()) })

/**
 * [have] updated with a live message: members, flags and cursor as the message says, each 尾迹 with the
 * message's positions added (a reconnect may resend some; backfilled ones arrive late), by time, and
 * the 对话 with its messages, once each, sent by a member under their name as now (改昵称, #184).
 */
fun mergeTeam(have: Team?, msg: Team): Team {
  val old = have?.takeIf { it.id == msg.id }
  return msg.copy(
    cursor = maxOf(msg.cursor, old?.cursor ?: 0),
    members = msg.members.map { m ->
      val trail = old?.members?.firstOrNull { it.id == m.id }?.trail.orEmpty() + m.trail
      m.copy(trail = trail.distinctBy { it.timeS }.sortedBy { it.timeS })
    },
    messages = (old?.messages.orEmpty() + msg.messages).distinctBy { it.seq }.sortedBy { it.seq }
      .map { m -> msg.members.firstOrNull { it.id == m.from }?.let { m.copy(name = it.name) } ?: m },
  )
}

/**
 * Whether [fix] goes to the team, [last] being the last one that did (§2.11): moving, every 30 s or
 * 50 m, whichever first; standing still, a heartbeat every 3 min. Below 20% battery every 2 min, below
 * 10% every 5 min, and 省电模式 ([saver]) every 2 min, however far. Recording is not affected.
 */
fun shouldReport(last: TeamPosition?, fix: TeamPosition, saver: Boolean): Boolean {
  if (last == null) return true
  val elapsed = fix.timeS - last.timeS
  val battery = fix.battery ?: 100
  fixedIntervalS(battery, saver)?.let { return elapsed >= it }
  val moved = haversine(TrackPoint(0, last.lat, last.lon, null), TrackPoint(0, fix.lat, fix.lon, null))
  // ponytail: displacement over 20 m counts as moving, above GPS jitter; use the fix's speed if walkers read as still.
  return moved >= 50 || (elapsed >= 30 && moved >= 20) || elapsed >= 180
}

/** The fixed report interval for [battery] % and [saver], or null for the moving / still rule. */
fun fixedIntervalS(battery: Int, saver: Boolean): Long? = when {
  battery < 10 -> 300
  battery < 20 || saver -> 120
  else -> null
}

/**
 * What to send of the reports queued while offline (oldest first), in one request: the latest first, so
 * teammates see where we are now, then the rest thinned to one every 2 min for the 尾迹 (§2.11). At most
 * the server's 1000 a request; days offline keep the latest 999 of the thinned trail.
 */
fun uploadOrder(queued: List<TeamPosition>): List<TeamPosition> {
  if (queued.isEmpty()) return emptyList()
  val thinned = mutableListOf<TeamPosition>()
  for (p in queued.dropLast(1)) if (thinned.isEmpty() || p.timeS - thinned.last().timeS >= 120) thinned += p
  return listOf(queued.last()) + thinned.takeLast(999)
}

fun agoText(lastS: Long, nowMs: Long): String {
  val min = (nowMs / 1000 - lastS) / 60
  return when {
    min < 1 -> "刚刚"
    min < 60 -> "$min 分钟前"
    else -> "${min / 60} 小时前"
  }
}

/** Initial bearing from the first point to the second, degrees clockwise from north. */
fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val p1 = radians(lat1)
  val p2 = radians(lat2)
  val dl = radians(lon2 - lon1)
  val deg = atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)) * (180 / PI)
  return (deg + 360) % 360
}

/** 「已停止共享 · 14:05」: when their last position came. */
fun stoppedText(lastS: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
  Instant.fromEpochSeconds(lastS).toLocalDateTime(zone).let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" } + " 停止共享"

/** C4-45, C4-46: 「3 分钟前更新」, or once they stopped sharing 「11:05 停止共享」; null before their first report. */
fun updatedText(m: TeamMember, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String? =
  m.trail.lastOrNull()?.let { at -> if (m.sharing) agoText(at.timeS, nowMs) + "更新" else stoppedText(at.timeS, zone) }

/** C4-47: 距离, 方向 and 电量 of a mate last [at], from [here]; what's unknown is 「—」. */
fun mateValues(at: TeamPosition, here: TeamPosition?): Triple<String, String, String> = Triple(
  here?.let { distanceText(haversine(TrackPoint(0, it.lat, it.lon, null), TrackPoint(0, at.lat, at.lon, null))) } ?: "—",
  here?.let { compass(bearing(it.lat, it.lon, at.lat, at.lon)) } ?: "—",
  at.battery?.let { "$it%" } ?: "—",
)
