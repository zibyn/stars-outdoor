package com.starsdom.trail.team

import com.starsdom.trail.OfflineError
import com.starsdom.trail.account.Account
import com.starsdom.trail.track.TrackStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [TeamTransport] in memory, by the server's rules (server/teams.go, chat.go, teamtrack.go): positions and messages share
 * one seq, which a [Team]'s cursor counts; a stream gets the team after its cursor first, then each change, and closes once
 * the trip has ended or its member is out; a non-member gets team_not_found. Single-threaded, like the tests. Accounts are
 * [account]s.
 */
class MemoryTeamTransport(private val now: () -> Long = { 0L }) : TeamTransport {
  /** False: every call fails offline, and the open streams drop. */
  var online = true
    set(value) {
      field = value
      if (!value) drop()
    }
  /** False: no stream opens (the rest works), as behind a proxy that drops WebSockets. */
  var streamsUp = true
  /** When [live] was asked, by [now], and from which cursor. */
  val connects = mutableListOf<Long>()
  val afters = mutableListOf<Long>()
  /** How often [team] was asked, and how long it takes to answer. */
  var teamCalls = 0
    private set
  var teamDelay = 0L
  /** The next message posted: stored with its answer lost (true), or lost on the way (false); null, as usual. */
  var loseNextMessage: Boolean? = null
  /** How long a stored message's answer takes. */
  var answerDelay = 0L
  /** Photos uploaded. */
  var uploads = 0
    private set

  private var seq = 0L
  private val teams = mutableListOf<MemTeam>()
  private val streams = mutableListOf<Stream>()
  private val images = mutableSetOf<String>()

  private class MemMember(val id: Long, var sharing: Boolean = true)

  private class MemTeam(val id: Long, val code: String, val initiator: Long) {
    var ended = false
    val members = mutableListOf<MemMember>()
    /** seq, user, position. */
    val positions = mutableListOf<Triple<Long, Long, TeamPosition>>()
    /** Each with the key it was sent with. */
    val messages = mutableListOf<Pair<String?, TeamMessage>>()
    var trackVersion = 0L
    var track: JsonObject? = null
  }

  private class Stream(val team: MemTeam, val user: Long, var cursor: Long, val ch: Channel<Team>)

  fun account(user: Long) = Account("1380000000$user", "u$user")

  /** The open streams drop, as when the signal goes for a moment. */
  fun drop() = streams.toList().forEach { it.ch.close(OfflineError("offline")) }

  /** All messages stored in team [id], resends not counted. */
  fun messages(id: Long) = teams.first { it.id == id }.messages.map { it.second }

  /** [user]'s positions stored in team [id], in the order they came. */
  fun positions(id: Long, user: Long) = teams.first { it.id == id }.positions.filter { it.second == user }.map { it.third }

  private fun name(user: Long) = "队员$user"

  private fun userOf(a: Account): Long {
    if (!online) throw OfflineError("offline")
    return a.token.removePrefix("u").toLongOrNull() ?: throw OfflineError("unauthorized")
  }

  private fun member(account: Account, id: Long): Pair<MemTeam, Long> {
    val u = userOf(account)
    val t = teams.firstOrNull { it.id == id && it.members.any { m -> m.id == u } } ?: throw OfflineError("team_not_found")
    return t to u
  }

  private fun view(t: MemTeam, user: Long, after: Long) = Team(
    t.id, t.code, t.initiator, user, t.ended, seq,
    t.members.map { m -> TeamMember(m.id, name(m.id), m.sharing, t.positions.filter { it.first > after && it.second == m.id }.map { it.third }) },
    t.messages.map { it.second }.filter { it.seq > after },
    t.track?.let { TeamTrackRef(t.trackVersion, it["uuid"]!!.jsonPrimitive.content, it["name"]!!.jsonPrimitive.content, TrackStart(it["reversed"]!!.jsonPrimitive.boolean, it["start"]!!.jsonPrimitive.double)) },
  )

  /** Each stream on [t] hears what changed since its cursor; one whose trip ended or member left then closes. */
  private fun changed(t: MemTeam) {
    for (s in streams.filter { it.team === t }) {
      s.ch.trySend(view(t, s.user, s.cursor))
      s.cursor = seq
      if (t.ended || t.members.none { it.id == s.user }) s.ch.close()
    }
  }

  private fun active(code: String) = teams.firstOrNull { it.code == code && !it.ended } ?: throw OfflineError("team_not_found")

  private fun leaveActive(user: Long) {
    for (t in teams.filter { !it.ended && it.members.any { m -> m.id == user } }) {
      t.members.removeAll { it.id == user }
      changed(t)
    }
  }

  override suspend fun create(account: Account): Team {
    val u = userOf(account)
    leaveActive(u)
    val t = MemTeam(teams.size + 1L, (1000 + teams.size + 1).toString().padStart(4, '0'), u)
    t.members += MemMember(u)
    teams += t
    return view(t, u, 0)
  }

  override suspend fun card(account: Account, code: String): TeamCard {
    userOf(account)
    val t = active(code)
    return TeamCard(t.id, name(t.initiator), null, t.members.size, 0)
  }

  override suspend fun join(account: Account, code: String): Team {
    val u = userOf(account)
    val t = active(code)
    if (t.members.none { it.id == u }) {
      leaveActive(u)
      t.members += MemMember(u)
      changed(t)
    }
    return view(t, u, 0)
  }

  override suspend fun team(account: Account, team: Long, after: Long): Team {
    teamCalls++
    delay(teamDelay)
    val (t, u) = member(account, team)
    return view(t, u, after)
  }

  override suspend fun leave(account: Account, team: Long) {
    val (t, u) = member(account, team)
    t.members.removeAll { it.id == u }
    changed(t)
  }

  override suspend fun end(account: Account, team: Long) {
    val (t, u) = member(account, team)
    if (u != t.initiator) throw OfflineError("forbidden")
    t.ended = true
    t.members.forEach { it.sharing = false }
    t.track = null
    changed(t)
  }

  override suspend fun setSharing(account: Account, team: Long, sharing: Boolean) {
    val (t, u) = member(account, team)
    t.members.first { it.id == u }.sharing = sharing
    changed(t)
  }

  override suspend fun postPositions(account: Account, team: Long, positions: List<TeamPosition>) {
    val (t, u) = member(account, team)
    if (t.ended) throw OfflineError("team_ended")
    if (!t.members.first { it.id == u }.sharing) return
    for (p in positions) t.positions += Triple(++seq, u, p)
    changed(t)
  }

  override suspend fun postMessage(account: Account, team: Long, message: String): TeamMessage {
    val (t, u) = member(account, team)
    val lose = loseNextMessage.also { loseNextMessage = null }
    if (lose == false) throw OfflineError("timeout")
    val o = Json.parseToJsonElement(message).jsonObject
    val key = o["key"]?.jsonPrimitive?.content
    t.messages.firstOrNull { key != null && it.first == key && it.second.from == u }?.let { return it.second }
    val image = o["image"]?.jsonPrimitive?.content
    if (image != null && image !in images) throw OfflineError("image_not_found")
    val m = TeamMessage(
      ++seq, u, name(u), now() / 1000, o["kind"]!!.jsonPrimitive.content, o["text"]?.jsonPrimitive?.content,
      o["lat"]?.jsonPrimitive?.double, o["lon"]?.jsonPrimitive?.double, image, o["along"]?.jsonArray?.map { it.jsonPrimitive.double },
    )
    t.messages += key to m
    changed(t)
    if (lose == true) throw OfflineError("timeout")
    delay(answerDelay)
    return m
  }

  override suspend fun uploadImage(account: Account, team: Long, jpeg: ByteArray, progress: (Float) -> Unit): String {
    member(account, team)
    val id = "img${++uploads}"
    images += id
    progress(1f)
    return id
  }

  override suspend fun image(account: Account, team: Long, image: String, thumb: Boolean): ByteArray {
    member(account, team)
    return if (image in images) byteArrayOf(1) else throw OfflineError("image_not_found")
  }

  override suspend fun putTrack(account: Account, team: Long, track: String) {
    val (t, u) = member(account, team)
    if (u != t.initiator) throw OfflineError("forbidden")
    if (t.ended) throw OfflineError("team_ended")
    t.track = Json.parseToJsonElement(track).jsonObject
    t.trackVersion++
    changed(t)
  }

  override suspend fun deleteTrack(account: Account, team: Long) {
    val (t, u) = member(account, team)
    if (u != t.initiator) throw OfflineError("forbidden")
    t.track = null
    t.trackVersion++
    changed(t)
  }

  override suspend fun track(account: Account, team: Long): String {
    val (t, _) = member(account, team)
    val o = t.track ?: throw OfflineError("team_track_not_found")
    return buildJsonObject {
      o.forEach { (k, v) -> put(k, v) }
      put("version", t.trackVersion)
    }.toString()
  }

  override fun live(account: Account, team: Long, after: Long): Flow<Live> = flow {
    connects += now()
    afters += after
    if (!streamsUp) throw OfflineError("offline")
    val (t, u) = member(account, team)
    val s = Stream(t, u, seq, Channel(Channel.UNLIMITED))
    s.ch.trySend(view(t, u, after))
    if (t.ended) s.ch.close()
    streams += s
    try {
      emit(Live.Open)
      for (change in s.ch) emit(Live.Change(change))
    } finally {
      streams -= s
    }
  }
}
