package dev.stars.outdoor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The one foreground service (§2.5): 轨迹记录 and 队伍 position sharing (§2.11) share its GPS and its
 * notification. It runs while recording, while in a team, or both.
 */
// ponytail: platform GPS only; HMS/GMS fused location (spec §3.1) when battery or indoor fixes matter.
class RecordingService : Service(), LocationListener {
  companion object {
    /** Extra: id of an unfinished track to continue in a new segment instead of starting a new track. */
    const val EXTRA_TRACK = "track"
    /** Action: be in team [EXTRA_TEAM] (share and hear its changes) until [ACTION_TEAM_QUIT]. */
    const val ACTION_TEAM = "team"
    const val EXTRA_TEAM = "team_id"
    /** Action: 停止共享 or share again ([EXTRA_SHARING]); works offline, the team is told once there's signal. */
    const val ACTION_SHARE = "share"
    const val EXTRA_SHARING = "sharing"
    /** Action: forget the team (after 退出队伍). */
    const val ACTION_TEAM_QUIT = "team_quit"
    private val _activeTrack = MutableStateFlow<Long?>(null)
    /** Id of the track being recorded, or null. */
    val activeTrack: StateFlow<Long?> = _activeTrack
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _team = MutableStateFlow<Team?>(null)
    /** The 队伍 this phone is in, as last heard from the server; null when in none. */
    val team: StateFlow<Team?> = _team
    /** Latest GPS fix (before the 5 s / 10 m filter), for "标注当前位置" and teammates' distance. Main thread only. */
    var lastFix: Location? = null
      private set

    /** Shows a team just created or joined, before its WebSocket says anything; null forgets it. */
    fun showTeam(t: Team?) {
      _team.value = t?.let { mergeTeam(_team.value, it) }
    }
  }

  private lateinit var db: TrackDb
  private lateinit var wakeLock: PowerManager.WakeLock
  private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
  private val api by lazy { api(prefs) }
  private var trackId = 0L
  private var segment = 0
  private var last: Location? = null
  /** Milliseconds between fixes asked of the GPS; 0 = off. */
  private var gpsMs = 0L
  /** 偏离提醒 against the 参考轨迹 [monitorTrack] (0 = none). */
  private var monitor: OffTrackMonitor? = null
  private var monitorTrack = 0L
  private val handler = Handler(Looper.getMainLooper())
  /** 出行提醒 already known this recording (keys), so each new risk is notified once. Background thread only. */
  @Volatile private var knownRisks: Set<String>? = null
  /** §2.9: while recording, the 参考轨迹's 沿途天气 is refreshed every 2 h (when there's network). */
  private val weatherTick = object : Runnable {
    override fun run() {
      refreshWeather()
      handler.postDelayed(this, 2 * 3_600_000L)
    }
  }

  // 队伍, all on the main thread but [queued].
  private var teamId = 0L
  private var account: Account? = null
  private var sharing = false
  private var live: WebSocket? = null
  private var reconnectMs = RECONNECT_MS
  /** The last position that went to the team's queue, for [shouldReport]. */
  private var lastReport: TeamPosition? = null
  private var lowBatteryNoticed = false
  /** Reports not yet accepted by the server (no signal), oldest first. [uploader] thread only. */
  // ponytail: in memory; a process killed offline loses its backlog, persist it if that turns up in the field.
  private val queued = mutableListOf<TeamPosition>()
  private val uploader = Executors.newSingleThreadExecutor()

  override fun onBind(intent: Intent?) = null

  override fun onCreate() {
    super.onCreate()
    db = TrackDb(this)
    // Recording must survive 30+ min with the screen locked (§2.5); keep the CPU up between fixes.
    wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stars:recording")
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel("recording", "轨迹记录", NotificationManager.IMPORTANCE_LOW))
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      // Vibration is ours (below), so it still comes with notifications denied or the channel muted.
      NotificationChannel("offtrack", "偏离提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(false) }
    )
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("weather", "出行提醒", NotificationManager.IMPORTANCE_HIGH))
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("team", "队伍", NotificationManager.IMPORTANCE_DEFAULT))
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(1, notification())
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      "stop" -> stopRecording()
      "pause" -> if (trackId != 0L && !_paused.value) {
        // No fixes recorded while paused: drop a stale 偏离提醒 and start fresh on resume.
        monitorTrack = 0L
        monitor = null
        getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
        _paused.value = true
        updateGps()
        updateNotification()
      }
      "resume" -> if (trackId != 0L && _paused.value) {
        segment++
        last = null
        _paused.value = false
        updateGps()
        updateNotification()
      }
      ACTION_TEAM -> startTeam(intent.getLongExtra(EXTRA_TEAM, 0L))
      ACTION_SHARE -> share(intent.getBooleanExtra(EXTRA_SHARING, true))
      ACTION_TEAM_QUIT -> leaveTeam(null)
      else -> if (trackId == 0L) {
        val resumed = intent?.getLongExtra(EXTRA_TRACK, 0L) ?: 0L
        if (resumed != 0L) {
          trackId = resumed
          segment = db.lastSegment(resumed) + 1
        } else {
          trackId = db.startTrack(System.currentTimeMillis())
        }
        last = null
        _activeTrack.value = trackId
        updateGps()
        updateNotification()
        handler.post(weatherTick)
      }
    }
    // A killed recording is not restarted (a sticky restart would carry no track id); the app asks
    // "继续记录 / 结束并保存" on next launch instead (§2.5), and rejoins its team.
    return START_NOT_STICKY
  }

  /** 停止记录: the track ends; the service stays on while in a team. */
  private fun stopRecording() {
    if (trackId != 0L) {
      handler.removeCallbacks(weatherTick)
      getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
      db.endTrack(trackId, System.currentTimeMillis())
      trackId = 0L
      monitorTrack = 0L
      monitor = null
      knownRisks = null
      _activeTrack.value = null
      _paused.value = false
    }
    idleOrUpdate()
  }

  private fun idleOrUpdate() {
    if (trackId == 0L && teamId == 0L) return stopSelf()
    updateGps()
    updateNotification()
  }

  /**
   * GPS runs while recording (not paused), every second, or while sharing with a team, every 10 s: plenty
   * for 30 s / 50 m reports and easier on the battery.
   */
  private fun updateGps() {
    val want = if (trackId != 0L && !_paused.value) 1000L else if (teamId != 0L && sharing) 10_000L else 0L
    if (want == gpsMs) return
    if (want == 0L) stopUpdates() else startUpdates(want)
  }

  private fun startUpdates(ms: Long) {
    try {
      // Ask for every fix and apply the "5 s or 10 m, whichever first" rule ourselves (§2.5);
      // LocationManager's minTime/minDistance would require both.
      getSystemService(LocationManager::class.java)
        .requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, this, Looper.getMainLooper())
      gpsMs = ms
      if (!wakeLock.isHeld) wakeLock.acquire()
    } catch (e: SecurityException) {
      stopSelf()
    }
  }

  private fun stopUpdates() {
    getSystemService(LocationManager::class.java).removeUpdates(this)
    gpsMs = 0L
    if (wakeLock.isHeld) wakeLock.release()
  }

  private fun action(label: String, action: String, extra: Boolean? = null) = Notification.Action.Builder(null, label,
    PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action).apply { extra?.let { putExtra(EXTRA_SHARING, it) } },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

  /** One notification for both (§2.5), e.g. "正在记录轨迹 · 正在与队伍 4827 共享位置". */
  private fun notification(): Notification {
    val code = _team.value?.code.orEmpty()
    val title = listOfNotNull(
      if (trackId == 0L) null else if (_paused.value) "轨迹记录已暂停" else "正在记录轨迹",
      if (teamId == 0L) null else if (sharing) "正在与队伍 $code 共享位置" else "队伍 $code · 已暂停共享",
    ).joinToString(" · ")
    return Notification.Builder(this, "recording")
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle(title.ifEmpty { "Stars Outdoor" })
      .setOngoing(true)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .apply {
        if (teamId != 0L) addAction(if (sharing) action("暂停共享", ACTION_SHARE, false) else action("继续共享", ACTION_SHARE, true))
        if (trackId != 0L) {
          addAction(if (_paused.value) action("继续记录", "resume") else action("暂停记录", "pause"))
          addAction(action("停止记录", "stop"))
        }
      }
      .build()
  }

  private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(1, notification())

  override fun onLocationChanged(location: Location) {
    lastFix = location
    if (teamId != 0L && sharing) report(TeamPosition(location.time / 1000, location.latitude, location.longitude, battery()))
    if (trackId == 0L || _paused.value) return
    checkOffTrack(location)
    val prev = last
    if (prev != null && location.time - prev.time < 5000 && location.distanceTo(prev) < 10f) return
    last = location
    db.addPoint(trackId, segment, TrackPoint(location.time, location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null))
  }

  /** §2.7: every fix (about 1 s) is checked, well inside the 10 s the alert must take. */
  private fun checkOffTrack(location: Location) {
    // A fix that could be 50 m off on its own would raise false alerts.
    if (location.hasAccuracy() && location.accuracy > OFF_TRACK_M) return
    val ref = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L)
    val notifications = getSystemService(NotificationManager::class.java)
    if (ref != monitorTrack) {
      monitorTrack = ref
      // ponytail: loaded once per reference; a 纠偏 change on it mid-recording applies from the next recording.
      monitor = if (ref == 0L) null else db.segments(ref).takeIf { it.any { s -> s.isNotEmpty() } }?.let(::OffTrackMonitor)
      notifications.cancel(OFF_TRACK_NOTIFICATION)
    }
    val m = monitor ?: return
    val was = m.off
    m.update(location.latitude, location.longitude)
    if (m.off == was) return
    if (!m.off) return notifications.cancel(OFF_TRACK_NOTIFICATION)
    @Suppress("DEPRECATION") // VibratorManager needs API 31; this works on all.
    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    notifications.notify(OFF_TRACK_NOTIFICATION, Notification.Builder(this, "offtrack")
      .setSmallIcon(android.R.drawable.ic_dialog_alert)
      .setContentTitle("已偏离参考轨迹")
      .setContentText("离参考轨迹超过 ${OFF_TRACK_M.toInt()} m")
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .setAutoCancel(true)
      .build())
  }

  /**
   * Fetches the 参考轨迹's 沿途天气 from here on and notifies risks not known yet this recording (at first,
   * those of the forecast the app last showed). Offline: nothing (§2.9).
   */
  // ponytail: rides on the recording service like 偏离提醒; following without recording gets no refresh.
  private fun refreshWeather() {
    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
    val ref = prefs.getLong(PREF_REFERENCE, 0L).takeIf { it != 0L } ?: return
    val here = lastFix?.let { it.latitude to it.longitude }
    thread {
      val known = knownRisks ?: cachedTrackWeather(this, ref)?.alerts().orEmpty().map { it.key }.toSet()
      val w = runCatching { fetchTrackWeather(this, api(prefs), ref, System.currentTimeMillis(), pace(prefs), from = here) }.getOrNull()?.takeIf { !it.offline } ?: return@thread
      val alerts = w.alerts()
      knownRisks = known + alerts.map { it.key }
      val fresh = alerts.filter { it.key !in known }
      if (fresh.isEmpty()) return@thread
      val text = fresh.joinToString("\n") { it.text }
      getSystemService(NotificationManager::class.java).notify(WEATHER_NOTIFICATION, Notification.Builder(this, "weather")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("出行提醒")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true)
        .build())
    }
  }

  private fun startTeam(id: Long) {
    if (id == 0L || id == teamId) return
    if (teamId != 0L) leaveTeam(null)
    account = AccountStore(prefs).get() ?: return idleOrUpdate()
    teamId = id
    // Ours to say (停止共享 works offline); the server hears it on every connect.
    sharing = prefs.getBoolean(PREF_TEAM_SHARING, true)
    lastReport = null
    lowBatteryNoticed = false
    connect()
    handler.removeCallbacks(heartbeat)
    handler.post(heartbeat)
    idleOrUpdate()
  }

  /**
   * §2.11 心跳: a phone lying still may get no fixes at all; every minute, the last fix counts as a report
   * of now, so [shouldReport] still sends one every 3 min (or the low-battery interval).
   */
  private val heartbeat = object : Runnable {
    override fun run() {
      if (teamId == 0L) return
      val fix = lastFix
      if (sharing && fix != null) report(TeamPosition(System.currentTimeMillis() / 1000, fix.latitude, fix.longitude, battery()))
      handler.postDelayed(this, 60_000L)
    }
  }

  private fun connect() {
    val id = teamId
    val acct = account ?: return
    val after = _team.value?.takeIf { it.id == id }?.cursor ?: 0L
    live = api.teamLive(acct, id, after, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        handler.post {
          if (webSocket !== live) return@post
          reconnectMs = RECONNECT_MS
          // Signal is back: tell the team whether we share (it may have changed offline), then send what queued up.
          val on = sharing
          uploader.execute {
            runCatching { api.setSharing(acct, id, on) }
            flush(id, acct)
          }
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val t = runCatching { parseTeam(text) }.getOrNull() ?: return
        handler.post { if (webSocket === live) onTeam(t) }
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        handler.post { if (webSocket === live) reconnect() }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        handler.post {
          if (webSocket !== live) return@post
          // Not in the team any more (left on another phone), or logged out: nothing to come back to.
          if (response?.code == 404 || response?.code == 401) leaveTeam(null) else reconnect()
        }
      }
    })
  }

  private fun reconnect() {
    live = null
    val id = teamId
    handler.postDelayed({ if (teamId == id && live == null) connect() }, reconnectMs)
    reconnectMs = minOf(reconnectMs * 2, 60_000L)
  }

  private fun onTeam(msg: Team) {
    val t = mergeTeam(_team.value, msg)
    _team.value = t
    if (t.ended) return leaveTeam(TRIP_ENDED)
    if (t.members.none { it.id == t.me }) return leaveTeam(null)
    updateNotification()
  }

  /** 停止共享 / share again: at once here (GPS, reports), and on the server now or on the next connect. */
  private fun share(on: Boolean) {
    val id = teamId
    val acct = account ?: return
    sharing = on
    prefs.edit().putBoolean(PREF_TEAM_SHARING, on).apply()
    lastReport = null
    updateGps()
    updateNotification()
    // Queued behind any reports, so none sent before 停止共享 is lost and none after goes out.
    uploader.execute {
      if (!on) queued.clear()
      runCatching { api.setSharing(acct, id, on) }
    }
  }

  /** Forgets the team, telling the user [notice] if given. */
  private fun leaveTeam(notice: String?) {
    if (teamId == 0L) return idleOrUpdate()
    live?.cancel()
    live = null
    handler.removeCallbacks(heartbeat)
    teamId = 0L
    account = null
    _team.value = null
    prefs.edit().remove(PREF_TEAM).remove(PREF_TEAM_SHARING).apply()
    uploader.execute { queued.clear() }
    notice?.let { notify(TEAM_NOTIFICATION, it) }
    idleOrUpdate()
  }

  private fun notify(id: Int, text: String) = getSystemService(NotificationManager::class.java).notify(id, Notification.Builder(this, "team")
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle(text)
    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    .setAutoCancel(true)
    .build())

  private fun battery() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

  /** §2.11 上报: [p] goes to the team if [shouldReport] says so, through the offline queue. */
  private fun report(p: TeamPosition) {
    if (!shouldReport(lastReport, p, prefs.getBoolean(PREF_TEAM_SAVER, false))) return
    lastReport = p
    if (p.battery != null && p.battery < 10 && !lowBatteryNoticed) {
      lowBatteryNoticed = true
      notify(LOW_BATTERY_NOTIFICATION, "电量低，已降低共享频率")
    }
    val id = teamId
    val acct = account ?: return
    uploader.execute {
      queued += p
      flush(id, acct)
    }
  }

  /** Sends [queued] ([uploadOrder]); if it doesn't get through, it waits for the next report or reconnect. Uploader thread. */
  private fun flush(id: Long, acct: Account) {
    if (queued.isEmpty()) return
    try {
      api.postPositions(acct, id, uploadOrder(queued))
      queued.clear()
    } catch (e: Exception) {
      val code = (e as? OfflineError)?.code
      if (code == "team_ended" || code == "team_not_found") {
        queued.clear()
        handler.post { if (teamId == id) leaveTeam(if (code == "team_ended") TRIP_ENDED else null) }
      }
    }
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    stopUpdates()
    if (trackId != 0L) db.endTrack(trackId, System.currentTimeMillis())
    db.close()
    live?.cancel()
    uploader.shutdown()
    _activeTrack.value = null
    _paused.value = false
    lastFix = null
    super.onDestroy()
  }
}

private const val RECONNECT_MS = 5_000L
private const val PREF_TEAM_SHARING = "team_sharing"
private const val TRIP_ENDED = "发起人已结束行程，位置共享已停止"
private const val TEAM_NOTIFICATION = 4
private const val LOW_BATTERY_NOTIFICATION = 5
