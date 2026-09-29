package dev.stars.outdoor

// 队伍 (spec §2.11): the team as the server sends it, the 上报 rules, and how teammates are shown.

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** SharedPreferences: the id of the team this phone is in (0 = none), the name to join with, and 省电模式. */
const val PREF_TEAM = "team"
const val PREF_TEAM_NAME = "team_name"
const val PREF_TEAM_SAVER = "team_saver"
/** SharedPreferences: the seq of the last 队伍对话 message read, for 未读. */
const val PREF_TEAM_READ = "team_read"
/** SharedPreferences: 尾迹 shown on the map (§2.11, a layer switch). */
const val PREF_TRAILS = "trails"

data class TeamPosition(val timeS: Long, val lat: Double, val lon: Double, val battery: Int?)

/** A member and their 尾迹 (oldest first). */
data class TeamMember(val id: Long, val name: String, val sharing: Boolean, val trail: List<TeamPosition>)

/**
 * A 队伍对话 message (openapi.yaml Message): [kind] is text, location, image or sos (一键求助), each with its
 * fields. [from] is null once the sender's account is deleted; [seq] orders it and marks what's read.
 */
data class TeamMessage(
  val seq: Long, val from: Long?, val name: String, val timeS: Long, val kind: String,
  val text: String? = null, val lat: Double? = null, val lon: Double? = null, val battery: Int? = null, val image: String? = null,
)

/** [me] is this phone's account; [cursor] is what the server has sent so far, to resume from. */
data class Team(
  val id: Long, val code: String, val initiator: Long, val me: Long, val ended: Boolean, val cursor: Long, val members: List<TeamMember>,
  val messages: List<TeamMessage> = emptyList(),
)

fun parseMessage(o: JsonObject) = TeamMessage(
  o["seq"]!!.jsonPrimitive.long, o["from"]?.jsonPrimitive?.long, o["name"]!!.jsonPrimitive.content, o["time"]!!.jsonPrimitive.long,
  o["kind"]!!.jsonPrimitive.content, o["text"]?.jsonPrimitive?.content, o["lat"]?.jsonPrimitive?.double, o["lon"]?.jsonPrimitive?.double,
  o["battery"]?.jsonPrimitive?.intOrNull, o["image"]?.jsonPrimitive?.content,
)

/** A MessageRequest: [kind] and what it carries. */
fun messageJson(kind: String, text: String? = null, lat: Double? = null, lon: Double? = null, battery: Int? = null, image: String? = null): String = buildJsonObject {
  put("kind", kind)
  text?.let { put("text", it) }
  lat?.let { put("lat", it) }
  lon?.let { put("lon", it) }
  battery?.let { put("battery", it) }
  image?.let { put("image", it) }
}.toString()

/** What [this] says, in a notification or a one-line preview. */
fun TeamMessage.summary(): String = when (kind) {
  "location" -> "[位置]"
  "image" -> "[图片]"
  "sos" -> "发出求助！" + (battery?.let { "电量 $it%" } ?: "")
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

/** The server's Team (openapi.yaml). Positions come in the order stored: [mergeTeam] sorts them. */
fun parseTeam(json: String): Team {
  val o = Json.parseToJsonElement(json).jsonObject
  return Team(
    o["id"]!!.jsonPrimitive.long, o["code"]!!.jsonPrimitive.content, o["initiator"]!!.jsonPrimitive.long, o["me"]!!.jsonPrimitive.long,
    o["ended"]!!.jsonPrimitive.boolean, o["cursor"]!!.jsonPrimitive.long,
    o["members"]!!.jsonArray.map { it.jsonObject }.map { m ->
      TeamMember(m["id"]!!.jsonPrimitive.long, m["name"]!!.jsonPrimitive.content, m["sharing"]!!.jsonPrimitive.boolean, m["positions"]!!.jsonArray.map { it.jsonObject }.map { p ->
        TeamPosition(p["time"]!!.jsonPrimitive.long, p["lat"]!!.jsonPrimitive.double, p["lon"]!!.jsonPrimitive.double, p["battery"]?.jsonPrimitive?.intOrNull)
      })
    },
    o["messages"]!!.jsonArray.map { parseMessage(it.jsonObject) },
  )
}

fun positionsJson(ps: List<TeamPosition>): String = buildJsonObject {
  putJsonArray("positions") {
    for (p in ps) addJsonObject {
      put("time", p.timeS)
      put("lat", p.lat)
      put("lon", p.lon)
      p.battery?.let { put("battery", it) }
    }
  }
}.toString()

/**
 * [have] updated with a live message: members, flags and cursor as the message says, each 尾迹 with the
 * message's positions added (a reconnect may resend some; backfilled ones arrive late), by time, and
 * the 对话 with its messages, once each.
 */
fun mergeTeam(have: Team?, msg: Team): Team {
  val old = have?.takeIf { it.id == msg.id }
  return msg.copy(
    cursor = maxOf(msg.cursor, old?.cursor ?: 0),
    members = msg.members.map { m ->
      val trail = old?.members?.firstOrNull { it.id == m.id }?.trail.orEmpty() + m.trail
      m.copy(trail = trail.distinctBy { it.timeS }.sortedBy { it.timeS })
    },
    messages = (old?.messages.orEmpty() + msg.messages).distinctBy { it.seq }.sortedBy { it.seq },
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

enum class Presence { Fresh, Stale, Lost }

/** Over 5 min without a report: shown faded; over 30 min: 失联. */
fun presence(lastS: Long, nowMs: Long): Presence = when (nowMs / 1000 - lastS) {
  in Long.MIN_VALUE..300 -> Presence.Fresh
  in 301..1800 -> Presence.Stale
  else -> Presence.Lost
}

/**
 * The 底栏 队伍 label (ux-v2 §3.1) and whether it's red: 队伍 N (sharing and not 失联, me included), or
 * 「N 人失联」 once teammates are. Plain 队伍 out of a team or after 结束行程.
 */
fun teamButton(t: Team?, nowMs: Long): Pair<String, Boolean> {
  if (t == null || t.ended) return "队伍" to false
  val live = t.members.filter { it.sharing }.map { it.id to it.trail.lastOrNull()?.let { p -> presence(p.timeS, nowMs) } }
  val lost = live.count { (id, p) -> id != t.me && p == Presence.Lost }
  if (lost > 0) return "$lost 人失联" to true
  return "队伍 ${live.count { (_, p) -> p != null && p != Presence.Lost }}" to false
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
  val p1 = Math.toRadians(lat1)
  val p2 = Math.toRadians(lat2)
  val dl = Math.toRadians(lon2 - lon1)
  val deg = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
  return (deg + 360) % 360
}

/** Eight-point compass name for a [bearing]. */
fun compass(deg: Double): String = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[(deg / 45).roundToInt() % 8]

/** A member's dot and 尾迹 colour, the same on every phone. */
fun memberColor(id: Long): Long = listOf(0xFFE4572E, 0xFF3B7DD8, 0xFF2F9E6E, 0xFF9C4DCC, 0xFFF2A900, 0xFF17A2B8, 0xFFD63384, 0xFF8B5A2B)[(id % 8).toInt()]
