package com.starsdom.trail

// 队伍会话 (spec §2.11): the one team this phone is in, for the whole process — joining and leaving, the live link and
// catching up without it, position reports, the 对话's outbox and what's read, the 队伍轨迹. Its state changes on one
// thread (the main one; the tests' own) and goes out as one flow; the network is [TeamTransport]'s. Location permission,
// the foreground service and its GPS, notifications and the 行程轨迹 file are Android's ([TeamEffects], [teamSession]).

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.starsdom.trail.track.ParsedTrack
import com.starsdom.trail.track.TrackLibrary
import com.starsdom.trail.track.TrackStart
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** SharedPreferences: sharing my position (停止共享 is ours to say, offline too); location allowed ("yes" / "no"). */
const val PREF_TEAM_SHARING = "team_sharing"
const val PREF_TEAM_LOCATION = "team_location"
/** SharedPreferences: what's typed in the 对话; a 队伍轨迹 come and not yet followed ([TrackCame.text]). */
const val PREF_TEAM_DRAFT = "team_draft"
const val PREF_TEAM_TRACK_CAME = "team_track_came"

/** How the team is heard: live; live but down 10 s or more (重新连接中); asked every so often (no socket); no team. */
enum class Link { Live, Reconnecting, Polling, None }

/** A member's copy of the 队伍轨迹 come into 我的轨迹 as [copy] with its 起算点; [last] my copy of the one before. */
data class TrackCame(val copy: Long, val last: Long?, val start: TrackStart, val name: String) {
  val text get() = "$copy:${last ?: 0}:${start.reversed}:${start.startM}:$name"

  companion object {
    fun parse(text: String?): TrackCame? {
      val p = text?.split(':', limit = 5)?.takeIf { it.size == 5 } ?: return null
      return TrackCame(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull()?.takeIf { it != 0L }, TrackStart(p[2].toBoolean(), p[3].toDoubleOrNull() ?: 0.0), p[4])
    }
  }
}

/** The 队伍 as the screens see it. */
data class TeamState(
  /** The team this phone is in (0: none), known before its [team] comes (back after the app was killed, offline). */
  val id: Long = 0,
  val team: Team? = null,
  val link: Link = Link.None,
  /** Sharing my position, as I said; in [team] my member says so too. */
  val sharing: Boolean = true,
  /** Location allowed (null: not told yet since joining): without it, no socket and not sharing (#141). */
  val location: Boolean? = null,
  val saver: Boolean = false,
  /** Mine on their way to the 对话 (§8.4 第 14 条), oldest first. */
  val outbox: List<Outgoing> = emptyList(),
  val draft: String = "",
  /** The last 对话 message read, and the 对话 on screen (read as it comes, no notification). */
  val readSeq: Long = 0,
  val chatShown: Boolean = false,
  /** The 队伍轨迹 this phone has: my copy, or my own as the 发起人 (track 0: my copy went from 我的轨迹). */
  val teamTrack: TeamTrackHere? = null,
  val fetchingTrack: Boolean = false,
  val trackCame: TrackCame? = null,
) {
  val noLocation get() = location == false
  /** In a team without a socket (the trip ended, or no location): caught up by asking. */
  val asking get() = id != 0L && (team?.ended == true || location == false)
  /** In a trip that's on, location allowed: the foreground service shares and the socket is up. */
  val active get() = id != 0L && team?.ended != true && location == true
  /** Teammates' messages not read yet. */
  val unread get() = team?.let { unread(it, readSeq) }.orEmpty()
}

/** What the session leaves to Android. */
interface TeamEffects {
  /** [p] went to team [team] as where I was then, for the 行程轨迹 (§2.11). */
  fun reported(team: Long, p: TeamPosition)
  fun stoppedSharing(team: Long)
  /** Out of trip [team] (ended, left, removed): its 行程轨迹 becomes a track; [byInitiator] the 发起人 ended it, say so. */
  fun tripOver(team: Long, byInitiator: Boolean)
  /** Below 10 %: reports slow down, say so once a trip. */
  fun lowBattery()
}

class TeamSession(
  private val transport: TeamTransport,
  private val prefs: SharedPreferences,
  /** Where the outbox, its photos and the reports waiting for signal are kept, to outlive the process. */
  private val dir: File,
  private val library: TrackLibrary,
  private val effects: TeamEffects,
  private val account: () -> Account?,
  private val battery: () -> Int?,
  /** Single-threaded: all of the session's state changes on it. */
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val _state = MutableStateFlow(TeamState())
  val state: StateFlow<TeamState> = _state
  private val s get() = _state.value
  private var online = true
  private var pageOpen = false
  private var live: Job? = null
  private var down: Job? = null
  private var poll: Job? = null
  private var heartbeat: Job? = null
  private var trackFetch: Job? = null
  /** The last fix the service gave, the last that went to the team ([shouldReport]), and the low-battery notice given. */
  private var lastFix: TeamPosition? = null
  private var lastReport: TeamPosition? = null
  private var lowBatteryNoticed = false
  /** Reports the server doesn't have yet (no signal), oldest first, kept in [queueFile]. */
  private val queue = mutableListOf<TeamPosition>()
  /** Reports and 共享 go one at a time, in order: none sent before 停止共享 is lost, none after goes out. */
  private val uplink = Mutex()
  private val queueFile = File(dir, "team-queue.csv")
  private val outboxFile = File(dir, "team-outbox.json")
  /** The session's thread, for the suspend calls that change state, whoever calls them. */
  private val home = scope.coroutineContext.minusKey(Job)

  // ponytail: the outbox and queue files are read and written on the main thread, small writes at most every few seconds;
  // move them to [io] behind the same order if a long offline queue ever shows.
  init {
    dir.mkdirs()
    queue += queueFile.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull(::parseQueued)
    _state.value = TeamState(
      id = prefs.getLong(PREF_TEAM, 0L),
      sharing = prefs.getBoolean(PREF_TEAM_SHARING, true),
      location = prefs.getString(PREF_TEAM_LOCATION, null)?.let { it == "yes" },
      saver = prefs.getBoolean(PREF_TEAM_SAVER, false),
      // Killed on the way, it goes again by itself; only ⚠ 没发出 waits for a tap.
      outbox = outboxFile.takeIf { it.exists() }?.let { runCatching { parseOutbox(it.readText()) }.getOrNull() }.orEmpty()
        .map { if (it.state == SendState.Failed) it else it.copy(state = SendState.Queued) },
      draft = prefs.getString(PREF_TEAM_DRAFT, null).orEmpty(),
      readSeq = prefs.getLong(PREF_TEAM_READ, 0L),
      teamTrack = TeamTrackHere.parse(prefs.getString(PREF_TEAM_TRACK, null)),
      trackCame = TrackCame.parse(prefs.getString(PREF_TEAM_TRACK_CAME, null)),
    )
    val id = s.id
    if (id != 0L) scope.launch { restore(id) }
  }

  /** Back in team [id] after the app was killed: still in it (caught up, then on as before), or out. */
  private suspend fun restore(id: Long) {
    if (s.id != id) return
    val acct = account() ?: return forget()
    try {
      val t = transport.team(acct, id, 0)
      if (s.id == id) onTeam(t)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Offline: on as before, the socket (or a poll) catches up once there's signal.
      if (gone(e) && s.id == id) return forget()
    }
    if (s.id != id) return
    link()
    sendQueued()
  }

  private fun gone(e: Throwable) = e.errorCode == "team_not_found" || e.errorCode == "unauthorized"

  // —— 建队 / 加入 / 退出 / 结束 ——

  /** The 队伍卡片 for [code], joining nothing. */
  suspend fun card(code: String): TeamCard = transport.card(account() ?: throw OfflineError("unauthorized"), code)

  /**
   * 创建队伍 ([code] null) or joins [code]; the team left for it (the server leaves it too) is over here. Not sharing
   * until [locationAllowed] says so.
   */
  suspend fun join(code: String?): Result<Team> = withContext(home) {
    val acct = account() ?: return@withContext Result.failure(OfflineError("unauthorized"))
    runCatching { if (code == null) transport.create(acct) else transport.join(acct, code) }.onSuccess { t ->
      forget()
      prefs.edit().putLong(PREF_TEAM, t.id).remove(PREF_TEAM_LOCATION).apply()
      _state.update { it.copy(id = t.id, location = null, sharing = true) }
      onTeam(t)
      link()
    }
  }

  /** 退出队伍: out here as soon as the server says so (or says I'm out already). */
  suspend fun leave(): Result<Unit> = withContext(home) {
    val id = s.id
    val acct = account() ?: return@withContext Result.failure(OfflineError("unauthorized"))
    runCatching {
      try {
        transport.leave(acct, id)
      } catch (e: OfflineError) {
        if (e.code != "team_not_found") throw e
      }
    }.onSuccess { if (s.id == id) forget() }
  }

  /** 结束行程 (发起人): ended here as soon as the server has it, not when the socket says so (#137). */
  suspend fun end(): Result<Unit> = withContext(home) {
    val id = s.id
    val acct = account() ?: return@withContext Result.failure(OfflineError("unauthorized"))
    runCatching { transport.end(acct, id) }.onSuccess {
      val t = s.team?.takeIf { it.id == id && !it.ended } ?: return@onSuccess
      _state.update { it.copy(team = t.copy(ended = true, members = t.members.map { m -> m.copy(sharing = false) })) }
      tripEnded(byInitiator = false)
    }
  }

  /** Out of the team here (left, removed, logged out): what was kept for it goes. */
  fun forget() {
    val id = s.id
    if (id == 0L) return
    stopTrip()
    poll?.cancel()
    poll = null
    trackFetch?.cancel()
    if (s.team?.ended != true) effects.tripOver(id, byInitiator = false)
    for (o in s.outbox) o.photo?.let { File(it).delete() }
    outboxFile.delete()
    prefs.edit().remove(PREF_TEAM).remove(PREF_TEAM_SHARING).remove(PREF_TEAM_LOCATION).remove(PREF_TEAM_DRAFT).apply()
    _state.value = TeamState(saver = s.saver, readSeq = s.readSeq, teamTrack = s.teamTrack, trackCame = s.trackCame, chatShown = s.chatShown)
  }

  /** Location allowed or not (the activity asked, or found it changed): share, or tell the team I don't and ask instead. */
  fun locationAllowed(ok: Boolean) {
    if (s.id == 0L || s.location == ok) return
    prefs.edit().putString(PREF_TEAM_LOCATION, if (ok) "yes" else "no").apply()
    _state.update { it.copy(location = ok) }
    s.team?.let(::showTeam)
    val id = s.id
    if (!ok) account()?.let { acct -> scope.launch { runCatching { transport.setSharing(acct, id, false) } } }
    link()
  }

  // —— Hearing the team ——

  /** The team as heard ([msg], what changed): merged in; ended or without me, the trip is over. */
  private fun onTeam(msg: Team) {
    if (msg.id != s.id) return
    val before = s.team?.takeIf { it.id == msg.id }
    val t = mergeTeam(before, msg)
    showTeam(t)
    if (t.members.none { it.id == t.me }) return forget()
    if (t.ended && before?.ended != true) tripEnded(byInitiator = before != null && t.initiator != t.me)
    markRead()
    fetchTrack()
  }

  /** [t] with my own member sharing as I said (the server hears it on the next connect). */
  private fun showTeam(t: Team) {
    val on = s.sharing && s.location != false
    _state.update { it.copy(team = if (t.ended) t else t.copy(members = t.members.map { m -> if (m.id == t.me) m.copy(sharing = on) else m })) }
  }

  /** The trip ended (here, or heard): sharing stops, the 对话 stays and is caught up by asking. */
  private fun tripEnded(byInitiator: Boolean) {
    stopTrip()
    effects.tripOver(s.id, byInitiator)
    prefs.edit().remove(PREF_TEAM_SHARING).apply()
    trackFetch?.cancel()
    link()
  }

  private fun stopTrip() {
    live?.cancel()
    live = null
    down?.cancel()
    down = null
    heartbeat?.cancel()
    heartbeat = null
    lastReport = null
    lowBatteryNoticed = false
    queue.clear()
    queueFile.delete()
  }

  /** The socket while the trip is on and location allowed; else asking while the 队伍页 is open (#141). */
  private fun link() {
    val socket = s.active
    if (socket && live == null) {
      live = connect(s.id)
      heartbeat = scope.launch { beat() }
    }
    if (!socket) {
      live?.cancel()
      live = null
      heartbeat?.cancel()
      heartbeat = null
      down?.cancel()
      down = null
    }
    poll()
    _state.update {
      it.copy(link = when {
        it.id == 0L -> Link.None
        socket -> if (it.link == Link.Reconnecting) Link.Reconnecting else Link.Live
        it.asking -> Link.Polling
        else -> Link.None
      })
    }
  }

  /** The socket to team [id], again after it drops: 5 s, doubling up to a minute; out once the server says I'm out. */
  private fun connect(id: Long) = scope.launch {
    var wait = RECONNECT_MS
    while (true) {
      val acct = account() ?: return@launch forget()
      try {
        transport.live(acct, id, s.team?.takeIf { it.id == id }?.cursor ?: 0L).collect { e ->
          when (e) {
            Live.Open -> {
              wait = RECONNECT_MS
              up()
              opened(acct, id)
            }
            is Live.Change -> onTeam(e.team)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (gone(e)) return@launch forget()
      }
      if (s.id != id || !s.active) return@launch
      // §8.4 第 15 条: 重新连接中 only once it has been down 10 s, so a short gap shows nothing.
      if (down == null) down = scope.launch {
        delay(10_000)
        _state.update { it.copy(link = Link.Reconnecting) }
      }
      delay(wait)
      wait = minOf(wait * 2, 60_000L)
    }
  }

  private fun up() {
    down?.cancel()
    down = null
    _state.update { it.copy(link = Link.Live) }
  }

  /** Signal is back: the team hears whether I share (it may have changed offline), then what queued up. */
  private fun opened(acct: Account, id: Long) {
    val on = s.sharing
    scope.launch {
      uplink.withLock {
        runCatching { transport.setSharing(acct, id, on) }
        sendQueue()
      }
    }
    sendQueued()
  }

  /** What's stored since the last heard, without a socket. */
  private suspend fun catchUp() {
    val id = s.id
    val acct = account() ?: return
    try {
      val t = transport.team(acct, id, s.team?.takeIf { it.id == id }?.cursor ?: 0L)
      if (s.id == id) onTeam(t)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (gone(e) && s.id == id) forget()
    }
  }

  /** Without a socket (ended, or no location): every 10 s while the 队伍页 is open. */
  private fun poll() {
    val want = pageOpen && s.asking
    if (!want) {
      poll?.cancel()
      poll = null
    } else if (poll == null) poll = scope.launch {
      while (true) {
        catchUp()
        delay(10_000)
      }
    }
  }

  /** A 队伍页 open or not. */
  fun pageOpen(open: Boolean) {
    pageOpen = open
    poll()
  }

  /** The app came to the front: without a socket, catch up once. */
  fun foreground() {
    if (s.asking) scope.launch { catchUp() }
  }

  /** The network came or went: what waited for it goes. */
  fun network(up: Boolean) {
    val back = up && !online
    online = up
    if (!back) return
    sendQueued()
    if (s.active) scope.launch { uplink.withLock { sendQueue() } }
  }

  // —— Position reports (§2.11 上报) ——

  /** A GPS fix from the service; reported if [shouldReport] says so. */
  fun fix(p: TeamPosition) {
    lastFix = p
    if (s.active && s.sharing) report(p)
  }

  /**
   * §2.11 心跳: a phone lying still may get no fixes at all; every minute, the last fix counts as a report of now, so
   * [shouldReport] still sends one every 3 min (or the low-battery interval).
   */
  private suspend fun beat() {
    while (true) {
      val fix = lastFix
      // A fix over 2 min old (no GPS) still tells the team, but isn't a place I was then: the 行程轨迹 breaks there.
      if (s.sharing && fix != null) report(TeamPosition(now() / 1000, fix.lat, fix.lon, battery()), kept = now() - fix.timeS * 1000 < 120_000)
      delay(60_000)
    }
  }

  private fun report(p: TeamPosition, kept: Boolean = true) {
    if (!shouldReport(lastReport, p, s.saver)) return
    lastReport = p
    if (kept) effects.reported(s.id, p)
    if (p.battery != null && p.battery < 10 && !lowBatteryNoticed) {
      lowBatteryNoticed = true
      effects.lowBattery()
    }
    queue += p
    queueFile.appendText(queuedLine(p) + "\n")
    scope.launch { uplink.withLock { sendQueue() } }
  }

  /** [queue] to the server ([uploadOrder]); without signal it waits for the next report or connect. Under [uplink]. */
  private suspend fun sendQueue() {
    val id = s.id
    val acct = account() ?: return
    val sending = queue.toList().takeIf { it.isNotEmpty() } ?: return
    try {
      transport.postPositions(acct, id, uploadOrder(sending))
      if (s.id != id) return
      queue.subList(0, minOf(sending.size, queue.size)).clear()
      queueFile.writeText(queue.joinToString("") { queuedLine(it) + "\n" })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (s.id != id) return
      val t = s.team
      // Not heard yet (back offline after the app was killed): asked, the ended team comes as any change does.
      if (e.errorCode == "team_ended") if (t == null) catchUp() else if (!t.ended) {
        showTeam(t.copy(ended = true))
        tripEnded(byInitiator = true)
      }
      else if (gone(e)) forget()
    }
  }

  /** 停止共享 / share again: here at once (the team's state, reports), the server now or on the next connect. */
  fun setSharing(on: Boolean) {
    val id = s.id
    if (id == 0L || s.sharing == on) return
    prefs.edit().putBoolean(PREF_TEAM_SHARING, on).apply()
    _state.update { it.copy(sharing = on) }
    s.team?.let(::showTeam)
    lastReport = null
    val acct = account() ?: return
    scope.launch {
      uplink.withLock {
        if (!on) {
          effects.stoppedSharing(id)
          queue.clear()
          queueFile.delete()
        }
        runCatching { transport.setSharing(acct, id, on) }
      }
    }
  }

  /** 省电模式: reports every 2 min however far (§2.11). */
  fun setSaver(on: Boolean) {
    prefs.edit().putBoolean(PREF_TEAM_SAVER, on).apply()
    _state.update { it.copy(saver = on) }
  }

  // —— 队伍对话 ——

  fun setDraft(text: String) {
    prefs.edit().putString(PREF_TEAM_DRAFT, text).apply()
    _state.update { it.copy(draft = text) }
  }

  fun send(text: String) = add(Outgoing(UUID.randomUUID().toString(), s.id, "text", text = text)) { messageJson("text", text = text, key = it) }

  /** 📍: where I am, with my 沿轨里程 on the 队伍轨迹 ([along], null without one). */
  fun sendLocation(lat: Double, lon: Double, along: List<Double>?) =
    add(Outgoing(UUID.randomUUID().toString(), s.id, "location")) { messageJson("location", lat = lat, lon = lon, along = along, key = it) }

  private fun add(o: Outgoing, json: (String) -> String) {
    if (o.team == 0L) return
    _state.update { it.copy(outbox = it.outbox + o.copy(json = json(o.id))) }
    saveOutbox()
    attempt(o.id)
  }

  /**
   * A photo into the 对话 (§3.2): in the outbox at once, [read] (shrunk) off the main thread and kept until it's sent.
   * False if it couldn't be read (sending again won't help, C4-36).
   */
  suspend fun sendPhoto(read: () -> ByteArray): Boolean = withContext(home) { addPhoto(read) }

  private suspend fun addPhoto(read: () -> ByteArray): Boolean {
    val id = UUID.randomUUID().toString()
    if (s.id == 0L) return true
    _state.update { it.copy(outbox = it.outbox + Outgoing(id, it.id, "image", progress = 0f)) }
    val file = File(dir, "outbox-$id.jpg")
    val kept = try {
      withContext(io) { file.writeBytes(read()) }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }
    if (!kept) {
      _state.update { it.copy(outbox = it.outbox.filter { o -> o.id != id }) }
      return false
    }
    // Left the team meanwhile.
    if (s.outbox.none { it.id == id }) {
      file.delete()
      return true
    }
    outgoing(id) { it.copy(photo = file.path) }
    saveOutbox()
    attempt(id)
    return true
  }

  /** ⚠ 没发出 tapped. */
  fun resend(id: String) = attempt(id)

  private fun outgoing(id: String, f: (Outgoing) -> Outgoing) = _state.update { it.copy(outbox = it.outbox.map { o -> if (o.id == id) f(o) else o }) }

  /**
   * Sends outbox [id] (its photo first, once): gone once the server has it; offline it waits for the network
   * ([SendState.Queued]), otherwise ⚠ 没发出 until tapped. Its key makes a resend after a lost answer the same message.
   */
  private fun attempt(id: String) {
    val o = s.outbox.firstOrNull { it.id == id } ?: return
    val acct = account() ?: run {
      outgoing(id) { it.copy(state = SendState.Failed) }
      return saveOutbox()
    }
    if (!online) return outgoing(id) { it.copy(state = SendState.Queued) }
    outgoing(id) { it.copy(state = SendState.Sending) }
    scope.launch {
      try {
        val json = o.json ?: run {
          val jpeg = withContext(io) { File(o.photo!!).readBytes() }
          val image = transport.uploadImage(acct, o.team, jpeg) { p -> scope.launch { outgoing(id) { it.copy(progress = p) } } }
          // Uploaded once: a resend only posts the message.
          messageJson("image", image = image, key = o.id).also { j -> outgoing(id) { it.copy(json = j) }; saveOutbox() }
        }
        val m = transport.postMessage(acct, o.team, json)
        o.photo?.let { File(it).delete() }
        _state.update { it.copy(outbox = it.outbox.filter { x -> x.id != id }) }
        saveOutbox()
        s.team?.takeIf { it.id == o.team }?.let { onTeam(it.copy(messages = listOf(m))) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Its photo gone (storage cleared): sending again won't help (C4-36).
        if (o.json == null && o.photo?.let { File(it).exists() } != true) _state.update { it.copy(outbox = it.outbox.filter { x -> x.id != id }) }
        else outgoing(id) { it.copy(state = sendStateAfter(e.errorCode, online), progress = null) }
        saveOutbox()
      }
    }
  }

  /** What waited for the network. */
  private fun sendQueued() = s.outbox.filter { it.state == SendState.Queued }.forEach { attempt(it.id) }

  private fun saveOutbox() {
    // One still being read has nothing to send yet.
    val keep = s.outbox.filter { it.json != null || it.photo != null }
    if (keep.isEmpty()) outboxFile.delete() else outboxFile.writeText(outboxJson(keep))
  }

  /** The 对话 on screen or not: on screen, everything in it is read. */
  fun chatShown(shown: Boolean) {
    _state.update { it.copy(chatShown = shown) }
    markRead()
  }

  private fun markRead() {
    if (!s.chatShown) return
    val last = s.team?.messages?.lastOrNull()?.seq ?: return
    if (last <= s.readSeq) return
    prefs.edit().putLong(PREF_TEAM_READ, last).apply()
    _state.update { it.copy(readSeq = last) }
  }

  /** A 对话 photo (or its thumbnail); null if it can't be had. */
  suspend fun image(id: String, thumb: Boolean): ByteArray? {
    val acct = account() ?: return null
    return runCatching { transport.image(acct, s.id, id, thumb) }.getOrNull()
  }

  // —— 队伍轨迹 ——

  /**
   * A member's side: a 队伍轨迹 given or changed is fetched into 我的轨迹 (once per track, by its uuid), in the background
   * too; a failed fetch (no signal) tries again every 30 s while the trip is on.
   */
  private fun fetchTrack() {
    val t = s.team ?: return
    val ref = t.track ?: return
    if (t.ended || t.initiator == t.me || trackFetch?.isActive == true) return
    if ((s.teamTrack?.takeIf { it.team == t.id }?.version ?: -1) >= ref.version) return
    trackFetch = scope.launch {
      _state.update { it.copy(fetchingTrack = true) }
      try {
        while (true) {
          val got = try {
            val acct = account() ?: return@launch
            val (r, segments) = parseTeamTrack(transport.track(acct, t.id))
            r to library.save(ParsedTrack(r.name, false, segments), r.name, uuid = teamTrackCopyUuid(r.uuid))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          }
          if (got != null) {
            if (s.id == t.id) trackCame(t.id, got.first, got.second)
            break
          }
          delay(30_000)
        }
      } finally {
        _state.update { it.copy(fetchingTrack = false) }
      }
    }
  }

  private fun trackCame(team: Long, r: TeamTrackRef, copy: Long) {
    val last = s.teamTrack?.takeIf { it.team == team }?.track?.takeIf { it != 0L }
    val came = TrackCame(copy, last, r.start, r.name)
    keepTrack(TeamTrackHere(team, copy, r.version))
    prefs.edit().putString(PREF_TEAM_TRACK_CAME, came.text).apply()
    _state.update { it.copy(trackCame = came) }
    // Changed again while this one came.
    s.team?.let { if ((it.track?.version ?: 0) > r.version) fetchTrack() }
  }

  private fun keepTrack(h: TeamTrackHere?) {
    prefs.edit().apply { if (h == null) remove(PREF_TEAM_TRACK) else putString(PREF_TEAM_TRACK, h.text) }.apply()
    _state.update { it.copy(teamTrack = h) }
  }

  /** The screen has followed [TeamState.trackCame] (参考, 起算点). */
  fun trackSeen() {
    prefs.edit().remove(PREF_TEAM_TRACK_CAME).apply()
    _state.update { it.copy(trackCame = null) }
  }

  /** My copy of the 队伍轨迹 went from 我的轨迹 for good ([knownRefs]): not fetched again for this version. */
  fun trackGone() = s.teamTrack?.let { keepTrack(it.copy(track = 0)) }

  /** 发起人: track [id] with its 起算点 [start] becomes the 队伍轨迹; the one it replaced, for 撤销. */
  suspend fun giveTrack(id: Long, start: TrackStart): Result<Long?> = withContext(home) { runCatching {
    val team = s.id
    val acct = account() ?: throw OfflineError("unauthorized")
    val replaced = s.teamTrack?.takeIf { it.team == team && it.version == 0L }?.track
    val d = library.detail(id).first() ?: throw OfflineError(null)
    // Its segments are WGS-84 whatever the track's 纠偏, as the snapshot wants.
    transport.putTrack(acct, team, teamTrackJson(d.uuid, d.name, start, d.segments))
    if (s.id == team) keepTrack(TeamTrackHere(team, id, 0))
    replaced
  } }

  /** 发起人: no 队伍轨迹. */
  suspend fun dropTrack(): Result<Unit> = runCatching { transport.deleteTrack(account() ?: throw OfflineError("unauthorized"), s.id) }

  companion object {
    @Volatile private var instance: TeamSession? = null

    /** The process's one, on the main thread, wired to Android. */
    fun get(context: Context): TeamSession = instance ?: synchronized(this) { instance ?: teamSession(context.applicationContext).also { instance = it } }
  }
}

private const val RECONNECT_MS = 5_000L

private fun queuedLine(p: TeamPosition) = "${p.timeS},${p.lat},${p.lon},${p.battery ?: ""}"

private fun parseQueued(line: String): TeamPosition? {
  val f = line.split(',').takeIf { it.size == 4 } ?: return null
  return TeamPosition(f[0].toLongOrNull() ?: return null, f[1].toDoubleOrNull() ?: return null, f[2].toDoubleOrNull() ?: return null, f[3].toIntOrNull())
}

private fun outboxJson(outbox: List<Outgoing>) = buildJsonArray {
  for (o in outbox) add(buildJsonObject {
    put("id", o.id)
    put("team", o.team)
    put("kind", o.kind)
    o.text?.let { put("text", it) }
    o.json?.let { put("json", it) }
    o.photo?.let { put("photo", it) }
    put("failed", o.state == SendState.Failed)
  })
}.toString()

private fun parseOutbox(text: String) = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }.map { o ->
  Outgoing(
    o["id"]!!.jsonPrimitive.content, o["team"]!!.jsonPrimitive.long, o["kind"]!!.jsonPrimitive.content, o["text"]?.jsonPrimitive?.content,
    o["json"]?.jsonPrimitive?.content, o["photo"]?.jsonPrimitive?.content, if (o["failed"]!!.jsonPrimitive.boolean) SendState.Failed else SendState.Queued,
  )
}

/** The session on Android: the HTTP transport, the 行程轨迹 and notices, the network, 对话 notifications, the service. */
private fun teamSession(ctx: Context): TeamSession {
  val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
  val accounts = AccountStore(prefs)
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val session = TeamSession(
    HttpTeamTransport(trailClient(prefs, quiet = true)), prefs, ctx.filesDir, TrackLibrary.get(ctx), AndroidTeamEffects(ctx),
    account = accounts::get, battery = ctx::battery, scope = scope,
  )
  val main = Handler(Looper.getMainLooper())
  val cm = ctx.getSystemService(ConnectivityManager::class.java)
  session.network(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
  cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
      val up = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      main.post { session.network(up) }
    }

    override fun onLost(n: Network) {
      main.post { session.network(false) }
    }
  })
  // 通知栏 by the session's state: teammates' messages not read or announced yet.
  scope.launch { session.state.collect { s -> s.team?.let { ChatAlerts.announce(ctx, it, s.readSeq, s.chatShown) } } }
  // In a trip with location allowed, the foreground service runs (GPS, the notification); it stops itself once out.
  scope.launch {
    session.state.map { it.active }.distinctUntilChanged().collect { active ->
      if (active && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        // Only ever from the front (joining, allowing location, opening the app); the system refuses it otherwise.
        runCatching { ctx.startForegroundService(Intent(ctx, RecordingService::class.java)) }
      }
    }
  }
  return session
}
