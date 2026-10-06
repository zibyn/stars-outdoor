package com.starsdom.trail.team

import com.starsdom.trail.Prefs
import com.starsdom.trail.account.Account
import com.starsdom.trail.errorCode
import com.starsdom.trail.track.ParsedTrack
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.TrackStart
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// 队伍会话 on the in-memory server, in virtual time.
class TeamSessionTest {
  private val prefs = MapPrefs()
  private val dir = Path(SystemTemporaryDirectory, "team-${Uuid.random()}").also { SystemFileSystem.createDirectories(it) }
  private val tracks = Tracks()
  private val effects = Effects()
  private var account: Account? = null

  @AfterTest fun close() {
    SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
    SystemFileSystem.delete(dir)
  }

  private class MapPrefs : Prefs {
    private val map = mutableMapOf<String, Any>()
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getString(key: String, default: String?) = map[key] as? String ?: default
    override fun put(vararg values: Pair<String, Any?>) {
      for ((key, value) in values) if (value == null) map.remove(key) else map[key] = value
    }
  }

  /** 我的轨迹, as the session sees it: names by id, a 队伍轨迹 copy kept once per uuid. */
  private class Tracks : TeamTracks {
    val names = mutableMapOf<Long, String>()
    private val uuids = mutableMapOf<String, Long>()
    fun add(name: String) = (names.size + 1L).also { names[it] = name }
    override suspend fun save(track: ParsedTrack, name: String, uuid: String?) = uuid?.let(uuids::get) ?: add(name).also { id -> uuid?.let { uuids[it] = id } }
    override suspend fun given(id: Long) = names[id]?.let { GivenTrack(id.toString().padStart(32, '0'), it, listOf(listOf(TrackPoint(0, 34.0 + id, 108.0, null)))) }
  }

  private class Effects : TeamEffects {
    val said = mutableListOf<String>()
    override fun reported(team: Long, p: TeamPosition) { said += "reported ${p.timeS}" }
    override fun stoppedSharing(team: Long) { said += "stopped" }
    override fun tripOver(team: Long, byInitiator: Boolean) { said += "over $team" + if (byInitiator) " by initiator" else "" }
    override fun lowBattery() { said += "low battery" }
  }

  private lateinit var server: MemoryTeamTransport

  /** The server, and the session of [user] on it (logged in), on the test's clock; [scope] to kill it with. */
  private fun TestScope.session(user: Long = 1, scope: CoroutineScope = backgroundScope): TeamSession {
    if (!::server.isInitialized) server = MemoryTeamTransport { testScheduler.currentTime }
    account = server.account(user)
    val io = StandardTestDispatcher(testScheduler)
    return TeamSession(server, prefs, dir.toString(), tracks, effects, { account }, { 80 }, scope, io) { testScheduler.currentTime }
  }

  /** A scope of its own, to kill a session as the system kills the process. */
  private fun TestScope.process() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

  /** In a new team of [user]'s, sharing, its socket up. */
  private suspend fun TestScope.inTeam(user: Long = 1): TeamSession {
    val s = session(user)
    s.join(null).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    return s
  }

  /** Another member, [user], in [s]'s team, from their own phone. */
  private suspend fun joins(s: TeamSession, user: Long) = server.join(server.account(user), s.state.value.team!!.code)

  private fun pos(timeS: Long, lat: Double = 34.0) = TeamPosition(timeS, lat, 108.0, 80)

  // —— 连接 (#212) ——

  // §2.11: 5 s, doubling to a minute between tries; 重新连接中 only after 10 s down; back up, live again.
  @Test fun reconnectsWithBackoff() = runTest {
    val s = inTeam()
    assertEquals(Link.Live, s.state.value.link)
    server.online = false
    advanceTimeBy(9_999)
    assertEquals(Link.Live, s.state.value.link)
    advanceTimeBy(2)
    assertEquals(Link.Reconnecting, s.state.value.link)
    advanceTimeBy(200_000)
    assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000, 135_000, 195_000), server.connects)
    server.online = true
    advanceTimeBy(60_001)
    assertEquals(Link.Live, s.state.value.link)
    // Up again, the wait starts over.
    val dropped = testScheduler.currentTime
    server.drop()
    advanceTimeBy(5_001)
    assertEquals(dropped + 5_000, server.connects.last())
  }

  // Back up, it goes on from what it had: what came meanwhile once, nothing twice.
  @Test fun reconnectsFromTheCursor() = runTest {
    val s = inTeam()
    val t = joins(s, 2)
    server.postMessage(server.account(2), t.id, messageJson("text", text = "出发"))
    runCurrent()
    val cursor = s.state.value.team!!.cursor
    server.drop()
    server.postMessage(server.account(2), t.id, messageJson("text", text = "到垭口了"))
    advanceTimeBy(5_001)
    assertEquals(cursor, server.afters.last())
    assertEquals(listOf("出发", "到垭口了"), s.state.value.team!!.messages.map { it.text })
  }

  // Joined another team: the old one's changes don't reach this one.
  @Test fun anOldTeamsChangesStayOut() = runTest {
    val s = inTeam()
    val old = s.state.value.team!!
    joins(s, 3)
    runCurrent()
    val other = server.create(server.account(2))
    s.join(other.code).getOrThrow()
    s.locationAllowed(true)
    server.postMessage(server.account(3), old.id, messageJson("text", text = "旧队伍"))
    runCurrent()
    assertEquals(other.id, s.state.value.team!!.id)
    assertEquals(emptyList<TeamMessage>(), s.state.value.team!!.messages)
    assertEquals(setOf(1L, 2L), s.state.value.team!!.members.map { it.id }.toSet())
  }

  // The 发起人 ends it: ended here, sharing stops, said so; 对话 kept, caught up by asking.
  @Test fun endedByTheInitiator() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    server.end(server.account(1), t.id)
    runCurrent()
    assertTrue(s.state.value.team!!.ended)
    assertEquals(Link.Polling, s.state.value.link)
    assertFalse(s.state.value.active)
    assertEquals(listOf("over ${t.id} by initiator"), effects.said)
  }

  // Out on another phone (or removed): the socket says so, out here too.
  @Test fun notAMemberAnyMore() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    server.leave(server.account(2), t.id)
    advanceTimeBy(5_001)
    assertEquals(TeamState(), s.state.value)
    assertEquals(0L, prefs.getLong(PREF_TEAM, 0L))
  }

  // No socket (ended, or no location): asked every 10 s while the 队伍页 is open, once on coming to the front; not otherwise.
  @Test fun pollsOnlyWithoutASocketAndThePageOpen() = runTest {
    val s = inTeam()
    s.pageOpen(true)
    advanceTimeBy(30_000)
    assertEquals(0, server.teamCalls)
    s.end().getOrThrow()
    runCurrent()
    assertEquals(1, server.teamCalls)
    advanceTimeBy(30_001)
    assertEquals(4, server.teamCalls)
    s.pageOpen(false)
    advanceTimeBy(60_000)
    assertEquals(4, server.teamCalls)
    s.foreground()
    runCurrent()
    assertEquals(5, server.teamCalls)
  }

  // —— 建队 / 加入 / 退出 / 结束 (#213) ——

  // Logged out, the session can't join: the 队伍页 logs in first, then joins.
  @Test fun joiningNeedsALogin() = runTest {
    val s = session()
    account = null
    assertEquals("unauthorized", s.join(null).exceptionOrNull()?.errorCode)
    account = server.account(1)
    assertTrue(s.join(null).isSuccess)
  }

  // C4-10: a code that isn't there says so; no signal is another failure, 重试 then.
  @Test fun joinFailures() = runTest {
    val s = session()
    assertEquals("team_not_found", s.join("9999").exceptionOrNull()?.errorCode)
    val t = server.create(server.account(2))
    server.online = false
    assertEquals("offline", s.join(t.code).exceptionOrNull()?.errorCode)
    server.online = true
    assertEquals(t.id, s.join(t.code).getOrThrow().id)
    assertEquals(t.id, s.state.value.id)
  }

  // 退出 and 结束 show at once, the socket or not; the trip's 行程轨迹 is over.
  @Test fun leaveAndEndShowAtOnce() = runTest {
    val s = inTeam()
    val t = s.state.value.team!!
    server.online = false
    assertTrue(s.end().isFailure)
    server.online = true
    s.end().getOrThrow()
    assertTrue(s.state.value.team!!.ended)
    assertEquals(listOf("over ${t.id}"), effects.said)
    s.leave().getOrThrow()
    assertEquals(0L, s.state.value.id)
    assertNull(s.state.value.team)
  }

  // Killed and opened again: still in it, caught up, on as before.
  @Test fun restoresAfterTheProcessWasKilled() = runTest {
    val p = process()
    val first = session(scope = p)
    first.join(null).getOrThrow()
    first.locationAllowed(true)
    runCurrent()
    val t = first.state.value.team!!
    p.cancel()
    val s = session()
    runCurrent()
    assertEquals(t.id, s.state.value.team!!.id)
    assertEquals(Link.Live, s.state.value.link)
    assertTrue(s.state.value.active)
  }

  // Killed, and meanwhile out of the team, or logged out: out here too.
  @Test fun restoresOutOfAGoneTeam() = runTest {
    val p = process()
    session(scope = p).join(null).getOrThrow()
    runCurrent()
    val id = prefs.getLong(PREF_TEAM, 0L)
    p.cancel()
    server.leave(server.account(1), id)
    assertEquals(0L, session().also { runCurrent() }.state.value.id)
    prefs.put(PREF_TEAM to server.create(server.account(1)).id)
    account = null
    val loggedOut = session(1).also { account = null }
    runCurrent()
    assertEquals(0L, loggedOut.state.value.id)
  }

  // Joined another while the old one (its trip over, its 对话 there) was still being caught up: nothing of it comes in.
  @Test fun joinedWhileRestoring() = runTest {
    server = MemoryTeamTransport { testScheduler.currentTime }
    val old = server.create(server.account(1))
    server.postMessage(server.account(1), old.id, messageJson("text", text = "旧的"))
    server.end(server.account(1), old.id)
    prefs.put(PREF_TEAM to old.id)
    server.teamDelay = 5_000
    val s = session()
    runCurrent()
    val other = server.create(server.account(2))
    s.join(other.code).getOrThrow()
    advanceTimeBy(5_001)
    assertEquals(other.id, s.state.value.team!!.id)
    assertEquals(emptyList<TeamMessage>(), s.state.value.team!!.messages)
    assertFalse(s.state.value.team!!.ended)
  }

  // Without location: still in it, not sharing, the server told so; allowed later, it shares.
  @Test fun withoutLocation() = runTest {
    val s = session()
    val t = s.join(null).getOrThrow()
    s.locationAllowed(false)
    runCurrent()
    assertTrue(s.state.value.noLocation)
    assertEquals(Link.Polling, s.state.value.link)
    assertFalse(server.team(server.account(1), t.id, 0).members.single().sharing)
    assertFalse(s.state.value.team!!.members.single().sharing)
    s.locationAllowed(true)
    runCurrent()
    assertEquals(Link.Live, s.state.value.link)
    assertTrue(server.team(server.account(1), t.id, 0).members.single().sharing)
  }

  // —— 上报 (#214) ——

  // No signal: reports wait, in order, then go as one ([uploadOrder]); kept in the 行程轨迹 as they're made.
  @Test fun reportsQueueOfflineAndGoOnceConnected() = runTest {
    val s = inTeam()
    val id = s.state.value.id
    server.online = false
    val fixes = listOf(pos(0), pos(150, 34.01), pos(300, 34.02))
    for (f in fixes) {
      s.fix(f)
      runCurrent()
    }
    assertEquals(fixes.map { "reported ${it.timeS}" }, effects.said)
    server.online = true
    advanceTimeBy(5_001)
    assertEquals(uploadOrder(fixes), server.positions(id, 1))
    s.fix(pos(450, 34.03))
    runCurrent()
    assertEquals(uploadOrder(fixes) + pos(450, 34.03), server.positions(id, 1))
  }

  // The server says the trip has ended (the socket didn't): out of the trip, said so.
  @Test fun reportingIntoAnEndedTrip() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    server.streamsUp = false
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    server.end(server.account(1), t.id)
    s.fix(pos(0))
    runCurrent()
    assertTrue(s.state.value.team!!.ended)
    assertEquals(listOf("reported 0", "over ${t.id} by initiator"), effects.said)
  }

  // §2.11 心跳: lying still with no fixes, the last fix goes again every 3 min (as now, not where I was then).
  @Test fun heartbeatStandingStill() = runTest {
    val s = inTeam()
    s.fix(pos(0))
    advanceTimeBy(400_000)
    assertEquals(listOf(0L, 180L, 360L), server.positions(s.state.value.id, 1).map { it.timeS })
    assertEquals(listOf("reported 0"), effects.said)
  }

  // 停止共享: at once in the team's state, the 行程轨迹 breaks, nothing more goes; the server hears it.
  @Test fun stopSharing() = runTest {
    val s = inTeam()
    val id = s.state.value.id
    s.setSharing(false)
    assertFalse(s.state.value.team!!.members.single().sharing)
    runCurrent()
    s.fix(pos(0))
    advanceTimeBy(200_000)
    assertEquals(emptyList<TeamPosition>(), server.positions(id, 1))
    assertFalse(server.team(server.account(1), id, 0).members.single().sharing)
    assertEquals(listOf("stopped"), effects.said)
  }

  // —— 队伍对话 (#215) ——

  // No network: it waits, and goes by itself once there is.
  @Test fun offlineQueuesAndGoesOnceOnline() = runTest {
    val s = inTeam()
    s.network(false)
    s.send("到了")
    assertEquals(SendState.Queued, s.state.value.outbox.single().state)
    s.network(true)
    runCurrent()
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
    assertEquals(listOf("到了"), s.state.value.team!!.messages.map { it.text })
  }

  // A failure on a network that's up waits for a tap; the server had it already (answer lost): stored once.
  @Test fun failedWaitsForATapAndAResendIsTheSameMessage() = runTest {
    val s = inTeam()
    server.loseNextMessage = true
    s.send("到了")
    runCurrent()
    assertEquals(SendState.Failed, s.state.value.outbox.single().state)
    s.resend(s.state.value.outbox.single().id)
    runCurrent()
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
    assertEquals(1, server.messages(s.state.value.id).size)
    assertEquals(listOf("到了"), s.state.value.team!!.messages.map { it.text })
  }

  // A photo goes up once: a resend only posts the message. One that can't be read says so.
  @Test fun photos() = runTest {
    val s = inTeam()
    server.loseNextMessage = false
    assertTrue(s.sendPhoto { byteArrayOf(1, 2) })
    runCurrent()
    assertEquals(SendState.Failed, s.state.value.outbox.single().state)
    s.resend(s.state.value.outbox.single().id)
    runCurrent()
    assertEquals(1, server.uploads)
    assertEquals(listOf("image"), s.state.value.team!!.messages.map { it.kind })
    assertFalse(s.sendPhoto { error("gone") })
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
  }

  // Its kept photo gone (storage cleared): dropped, not ⚠ 没发出 forever.
  @Test fun aPhotoGoneIsDropped() = runTest {
    val s = inTeam()
    s.network(false)
    s.sendPhoto { byteArrayOf(1) }
    SystemFileSystem.list(dir).filter { it.name.endsWith(".jpg") }.forEach { SystemFileSystem.delete(it) }
    s.network(true)
    runCurrent()
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
  }

  // Out while the socket was down: no 重新连接中 left over for the next team.
  @Test fun noReconnectingAfterLeaving() = runTest {
    val s = inTeam()
    server.drop()
    runCurrent()
    s.forget()
    advanceTimeBy(20_000)
    s.join(null).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    assertEquals(Link.Live, s.state.value.link)
  }

  // 📍 carries where and my 沿轨里程.
  @Test fun location() = runTest {
    val s = inTeam()
    s.sendLocation(34.0, 108.0, listOf(1200.0))
    runCurrent()
    val m = s.state.value.team!!.messages.single()
    assertEquals(listOf(34.0, 108.0), listOf(m.lat, m.lon))
    assertEquals(listOf(1200.0), m.along)
  }

  // Teammates' messages are 未读 until the 对话 is on screen; on screen, what comes is read.
  @Test fun unreadAndRead() = runTest {
    val s = inTeam()
    val t = joins(s, 2)
    val mate = server.account(2)
    server.postMessage(mate, t.id, messageJson("text", text = "a"))
    server.postMessage(mate, t.id, messageJson("text", text = "b"))
    runCurrent()
    assertEquals(2, s.state.value.unread.size)
    s.chatShown(true)
    assertEquals(0, s.state.value.unread.size)
    server.postMessage(mate, t.id, messageJson("text", text = "c"))
    runCurrent()
    assertEquals(0, s.state.value.unread.size)
    s.chatShown(false)
    server.postMessage(mate, t.id, messageJson("text", text = "d"))
    runCurrent()
    assertEquals(listOf("d"), s.state.value.unread.map { it.text })
    assertEquals(s.state.value.readSeq, prefs.getLong(PREF_TEAM_READ, 0L))
  }

  // —— 队伍轨迹 (#216) ——

  private fun trackJson(uuid: String, name: String) =
    teamTrackJson(uuid, name, TrackStart(true, 100.0), listOf(listOf(TrackPoint(0, 34.0, 108.0, null), TrackPoint(0, 34.01, 108.0, null))))

  // Given: a member gets it into 我的轨迹 (by itself, no screen), its id in the state with its 起算点; changed, the new one
  // replaces it, the old one said for the screen's 参考 rule.
  @Test fun aTeamTrackComesToMembers() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    server.putTrack(server.account(1), t.id, trackJson("u1", "鳌太线"))
    runCurrent()
    val first = s.state.value.trackCame!!
    assertEquals(TrackCame(first.copy, null, TrackStart(true, 100.0), "鳌太线"), first)
    assertEquals(TeamTrackHere(t.id, first.copy, 1), s.state.value.teamTrack)
    assertEquals("鳌太线", tracks.names[first.copy])
    s.trackSeen()
    server.putTrack(server.account(1), t.id, trackJson("u2", "西线"))
    runCurrent()
    val second = s.state.value.trackCame!!
    assertEquals(first.copy, second.last)
    assertEquals(TeamTrackHere(t.id, second.copy, 2), s.state.value.teamTrack)
    // The same track given again is the same copy.
    server.putTrack(server.account(1), t.id, trackJson("u2", "西线"))
    runCurrent()
    assertEquals(second.copy, s.state.value.teamTrack!!.track)
  }

  // Heard of, but no signal to fetch it: tried again every 30 s until it comes.
  @Test fun aTeamTrackComesOnceThereIsSignal() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    runCurrent()
    server.putTrack(server.account(1), t.id, trackJson("u1", "鳌太线"))
    server.online = false
    runCurrent()
    assertTrue(s.state.value.fetchingTrack)
    server.online = true
    advanceTimeBy(30_001)
    assertTrue(s.state.value.trackCame != null)
    assertFalse(s.state.value.fetchingTrack)
  }

  // 发起人: gives one, changes it (撤销 gets the one before), drops it.
  @Test fun theInitiatorGivesAndDrops() = runTest {
    val s = inTeam()
    val id = s.state.value.id
    val a = tracks.add("甲")
    val b = tracks.add("乙")
    assertNull(s.giveTrack(a, TrackStart()).getOrThrow())
    assertEquals(a, s.giveTrack(b, TrackStart(true, 0.0)).getOrThrow())
    runCurrent()
    assertEquals(TeamTrackHere(id, b, 0), s.state.value.teamTrack)
    assertEquals("乙", s.state.value.team!!.track!!.name)
    s.dropTrack().getOrThrow()
    runCurrent()
    assertNull(s.state.value.team!!.track)
  }

  // My copy gone from 我的轨迹 for good (#208's rule): not fetched back for the same version.
  @Test fun aCopyGoneIsNotFetchedAgain() = runTest {
    val s = session(2)
    val t = server.create(server.account(1))
    s.join(t.code).getOrThrow()
    s.locationAllowed(true)
    server.putTrack(server.account(1), t.id, trackJson("u1", "鳌太线"))
    runCurrent()
    s.trackGone()
    server.postMessage(server.account(1), t.id, messageJson("text", text = "x"))
    runCurrent()
    assertEquals(0L, s.state.value.teamTrack!!.track)
    assertFalse(s.state.value.fetchingTrack)
  }

  // —— 进程被杀 (#217) ——

  // Killed with a message, a draft and reports waiting: back, the draft is there and the rest goes, once each.
  @Test fun outboxDraftAndReportsOutliveTheProcess() = runTest {
    val p = process()
    val first = session(scope = p)
    first.join(null).getOrThrow()
    first.locationAllowed(true)
    runCurrent()
    val id = first.state.value.id
    first.setDraft("草稿")
    server.online = false
    first.network(false)
    first.send("到了")
    first.fix(pos(0))
    runCurrent()
    p.cancel()
    server.online = true
    val s = session()
    runCurrent()
    assertEquals("草稿", s.state.value.draft)
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
    assertEquals(listOf("到了"), server.messages(id).map { it.text })
    assertEquals(listOf(pos(0)), server.positions(id, 1))
  }

  // Killed mid-send (stored, the answer not back yet): it goes again by itself, and the server keeps it once.
  @Test fun killedMidSendIsStoredOnce() = runTest {
    val p = process()
    val first = session(scope = p)
    first.join(null).getOrThrow()
    first.locationAllowed(true)
    runCurrent()
    val id = first.state.value.id
    server.answerDelay = 10_000
    first.send("到了")
    runCurrent()
    p.cancel()
    server.answerDelay = 0
    val s = session()
    runCurrent()
    assertEquals(emptyList<Outgoing>(), s.state.value.outbox)
    assertEquals(1, server.messages(id).size)
  }
}
