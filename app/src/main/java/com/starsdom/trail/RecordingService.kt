package com.starsdom.trail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.starsdom.trail.track.OFF_TRACK_M
import com.starsdom.trail.track.OffTrackMonitor
import com.starsdom.trail.track.ParsedTrack
import com.starsdom.trail.track.TrackDb
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.TrackStart
import com.starsdom.trail.track.alongTrack
import com.starsdom.trail.track.distanceValue
import com.starsdom.trail.track.oriented
import com.starsdom.trail.track.trackStats
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one foreground service (§2.5): 轨迹记录 and 队伍 position sharing (§2.11) share its GPS and its
 * notification. It runs while recording, while in a trip with location allowed ([TeamState.active]), or both; for the
 * team it only feeds the [TeamSession] fixes and shows its state.
 */
// ponytail: Android LocationManager GPS only; HMS/GMS fused location (spec §3.1) when battery or indoor fixes matter.
class RecordingService : Service(), LocationListener {
  companion object {
    /** Action: start recording; [EXTRA_TRACK] the id of an unfinished track to continue in a new segment instead. */
    const val ACTION_RECORD = "record"
    const val EXTRA_TRACK = "track"
    /** Action (the notification's): 停止共享 or share again ([EXTRA_SHARING]). */
    const val ACTION_SHARE = "share"
    const val EXTRA_SHARING = "sharing"
    /** With "stop": the recording got no point, so it isn't kept (C3-28); the app said so already. */
    const val EXTRA_DISCARD = "discard"
    private val _activeTrack = MutableStateFlow<Long?>(null)
    /** Id of the track being recorded, or null. */
    val activeTrack: StateFlow<Long?> = _activeTrack
    private val _offTrack = MutableStateFlow(false)
    /** The 偏离提醒 is on (§2.7): the 顶部数据 says 「偏离 150 m」 as long as it is. */
    val offTrack: StateFlow<Boolean> = _offTrack
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _since = MutableStateFlow<Long?>(null)
    /** When recording last started or went on after a pause: 用时 runs from here until the first point (ux-v3 §8.3). */
    val since: StateFlow<Long?> = _since
    private val _pausedAt = MutableStateFlow<Long?>(null)
    /** When it was paused, for 「已暂停 0:05:32」; kept here so it outlives the screen. */
    val pausedAt: StateFlow<Long?> = _pausedAt
    private val _track = MutableStateFlow(listOf<List<TrackPoint>>())
    /** The recording's points by segment, as stored (continued ones included): 顶部数据 and 记录中的线 (ux-v2 §3.8). */
    val track: StateFlow<List<List<TrackPoint>>> = _track
    private val _risk = MutableStateFlow<Pair<Long, TripAlert>?>(null)
    /** The last new 出行提醒 and when it came, for the app's 提示条 if it's up then (§8.3 第 20 条). */
    val risk: StateFlow<Pair<Long, TripAlert>?> = _risk
    /** Latest GPS fix (before the 5 s / 10 m filter), for "标注当前位置" and teammates' distance. Main thread only. */
    var lastFix: Location? = null
      private set

    /** 由位置共享生成轨迹 (§2.11): my reports during trip [id], kept until I leave it. */
    private fun tripFile(context: Context, id: Long) = File(context.filesDir, "trip-$id.csv")

    // ponytail: one small append per report (30 s apart at most) on the main thread; queue them if it ever shows.
    fun keepTrip(context: Context, id: Long, line: String) = tripFile(context, id).appendText(line + "\n")

    /** A recording ran during trip [id]: its own track stands, none is made from the reports. */
    private fun markTripRecorded(context: Context, id: Long) =
      context.getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_TRIP_RECORDED, id).apply()

    /**
     * Leaving trip [id] (结束行程, 退出队伍, removed), here or found out by the app after the service was gone: the
     * reports kept become a track in 我的轨迹, unless I recorded ([recording] now, or earlier in the trip).
     */
    fun endTrip(context: Context, id: Long, recording: Boolean) {
      val file = tripFile(context, id)
      val prefs = context.getSharedPreferences("prefs", MODE_PRIVATE)
      val segments = if (!recording && prefs.getLong(PREF_TRIP_RECORDED, 0L) != id && file.exists()) tripSegments(file.readLines()) else emptyList()
      if (segments.isNotEmpty()) {
        TrackDb.get(context).importTrack(ParsedTrack("", false, segments, TRIP_SOURCE), name = null, emptyList(), System.currentTimeMillis())
      }
      file.delete()
      prefs.edit().remove(PREF_TRIP_RECORDED).apply()
    }
  }

  private val db by lazy { TrackDb.get(this) }
  private lateinit var wakeLock: PowerManager.WakeLock
  private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
  private var trackId = 0L
  private var segment = 0
  /** [segment] of [track]'s last list; a point in another starts a new one. */
  private var shownSegment = -1
  private var last: Location? = null
  /** Milliseconds between fixes asked of the GPS; 0 = off. */
  private var gpsMs = 0L
  /** 偏离提醒 against the 参考轨迹 [monitorTrack] (0 = none). */
  private var monitor: OffTrackMonitor? = null
  /** Its threshold, as 设置 had it when this recording started (§8.6 第 3 条: 下次记录生效). */
  private var offTrackM = OFF_TRACK_M
  private var monitorTrack = 0L
  /** The 参考轨迹 as walked from its 起算点 and its length, for 剩余 in the notification. */
  private var refWalked: List<List<TrackPoint>>? = null
  private var refLengthM = 0.0
  /** 剩余 at the last fix; null without a 参考 or off it. */
  private var leftM: Double? = null
  /** [trackStats] of [_track]'s value it was worked out for: the notification ticks each second, points come slower. */
  private var statsOf: List<List<TrackPoint>>? = null
  private var stats = trackStats(emptyList())
  /** §8.3 第 19 条: the notification's 用时 / 已暂停 goes on each second while recording. */
  private val clockTick = object : Runnable {
    override fun run() {
      if (trackId == 0L) return
      updateNotification()
      handler.postDelayed(this, 1_000L)
    }
  }
  private val handler = Handler(Looper.getMainLooper())
  /** 出行提醒 already known this recording (keys), so each new risk is notified once. Background thread only. */
  @Volatile private var knownRisks: Set<String>? = null
  /** §2.9: while recording, the weather where I am is checked every 2 h (when there's network). */
  private val weatherTick = object : Runnable {
    override fun run() {
      // No fix yet at the start: look again in 5 min.
      handler.postDelayed(this, if (refreshWeather()) 2 * 3_600_000L else 5 * 60_000L)
    }
  }

  // 队伍 as the session last said: the trip this phone shares in (0: none), sharing, its code.
  private val session by lazy { TeamSession.get(this) }
  private val teamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var teamId = 0L
  private var sharing = false
  private var teamCode = ""
  /** A start command came: before it, an idle state is no reason to stop (it may be the start of a recording). */
  private var commanded = false

  override fun onBind(intent: Intent?) = null

  override fun onCreate() {
    super.onCreate()
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
    teamScope.launch {
      session.state.map { s -> Triple(s.id.takeIf { s.active } ?: 0L, s.sharing, s.team?.code.orEmpty()) }.distinctUntilChanged().collect { (id, on, code) ->
        if (id != 0L && id != teamId && trackId != 0L) markTripRecorded(this@RecordingService, id)
        teamId = id
        sharing = on
        teamCode = code
        if (commanded) idleOrUpdate()
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    commanded = true
    when (intent?.action) {
      "stop" -> stopRecording(intent.getBooleanExtra(EXTRA_DISCARD, false))
      "mark" -> mark()
      "pause" -> if (trackId != 0L && !_paused.value) {
        // No fixes recorded while paused: drop a stale 偏离提醒 and start fresh on resume.
        monitorTrack = 0L
        monitor = null
        _offTrack.value = false
        getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
        _paused.value = true
        _pausedAt.value = System.currentTimeMillis()
        updateGps()
        updateNotification()
      }
      "resume" -> if (trackId != 0L && _paused.value) {
        segment++
        last = null
        _paused.value = false
        _pausedAt.value = null
        _since.value = System.currentTimeMillis()
        updateGps()
        updateNotification()
      }
      ACTION_SHARE -> session.setSharing(intent.getBooleanExtra(EXTRA_SHARING, true))
      ACTION_RECORD -> if (trackId == 0L) {
        val resumed = intent.getLongExtra(EXTRA_TRACK, 0L)
        if (resumed != 0L) {
          trackId = resumed
          segment = db.lastSegment(resumed) + 1
          _track.value = db.segments(resumed)
        } else {
          trackId = db.startTrack(System.currentTimeMillis())
          _track.value = emptyList()
        }
        shownSegment = -1
        last = null
        offTrackM = prefs.getInt(PREF_OFF_TRACK, OFF_TRACK_M)
        _since.value = System.currentTimeMillis()
        _activeTrack.value = trackId
        if (teamId != 0L) markTripRecorded(this, teamId)
        updateGps()
        updateNotification()
        handler.post(weatherTick)
        handler.post(clockTick)
      }
      // Started for the team ([teamSession]): it carries on while the session says so.
      else -> idleOrUpdate()
    }
    // A killed recording is not restarted (a sticky restart would carry no track id); the app offers
    // 「⚠ 记录中断了」［继续］［结束］ on next launch instead (§8.3 第 18 条), and rejoins its team.
    return START_NOT_STICKY
  }

  /** 结束 (the recording): the track ends, or with [discard] goes; the service stays on while in a team. */
  private fun stopRecording(discard: Boolean) {
    if (trackId != 0L) {
      handler.removeCallbacks(weatherTick)
      handler.removeCallbacks(clockTick)
      getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
      if (discard) db.discardTrack(trackId) else db.endTrack(trackId, System.currentTimeMillis())
      trackId = 0L
      monitorTrack = 0L
      monitor = null
      refWalked = null
      leftM = null
      _offTrack.value = false
      knownRisks = null
      _activeTrack.value = null
      _paused.value = false
      _pausedAt.value = null
      _since.value = null
      _track.value = emptyList()
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

  /**
   * One notification for both (§8.3 第 19 条, C7-25…37): the recording's line 「记录中 · 3.2 km · 0:58:12」 (with a 参考
   * 「剩余 15.4 km · …」, paused 「已暂停 · 0:05:32」) over the team's 「队伍 4827 共享中」, or the team's alone. No 结束 here.
   */
  private fun notification(): Notification {
    val team = if (teamId == 0L) null else getString(if (sharing) R.string.notify_sharing else R.string.notify_not_sharing, teamCode)
    val recording = if (trackId == 0L) null else {
      if (statsOf !== _track.value) { statsOf = _track.value; stats = trackStats(_track.value) }
      val (live, pausedMs) = liveStats(stats, _track.value.lastOrNull()?.lastOrNull()?.timeMs, _since.value, _pausedAt.value, System.currentTimeMillis())
      val left = leftM
      when {
        pausedMs != null -> getString(R.string.notify_paused, clock(pausedMs))
        left != null -> getString(R.string.notify_left, distanceValue(left), clock(live.durationMs))
        else -> getString(R.string.notify_recording, distanceValue(live.distanceM), clock(live.durationMs))
      }
    }
    return Notification.Builder(this, "recording")
      .setSmallIcon(if (trackId == 0L) R.drawable.group_fill1_24px else if (_paused.value) R.drawable.pause_fill1_24px else R.drawable.radio_button_checked_fill1_24px)
      .setContentTitle(recording ?: team ?: getString(R.string.app_name))
      .apply { if (recording != null && team != null) setContentText(team) }
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .apply {
        if (trackId != 0L && _paused.value) addAction(action(getString(R.string.resume), "resume"))
        else if (trackId != 0L) {
          addAction(action(getString(R.string.mark), "mark"))
          addAction(action(getString(R.string.pause), "pause"))
        }
        if (teamId != 0L) addAction(if (sharing) action(getString(R.string.stop_sharing), ACTION_SHARE, false) else action(getString(R.string.share_again), ACTION_SHARE, true))
      }
      .build()
  }

  /** 标注 from the notification (C7-27): where I am now, under its default name (R13), with a buzz. */
  private fun mark() {
    val at = lastFix ?: return
    val track = trackId.takeIf { it != 0L }
    buzz()
    thread {
      val name = defaultWaypointName(nearestPlace(placesNear(placeFiles(), at.latitude, at.longitude), at.latitude, at.longitude), at.time, System.currentTimeMillis())
      db.addWaypoint(track, at.time, at.latitude, at.longitude, if (at.hasAltitude()) at.altitude else null).also { db.updateWaypoint(it, name, "", null) }
    }
  }

  private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(1, notification())

  override fun onLocationChanged(location: Location) {
    lastFix = location
    if (teamId != 0L) session.fix(TeamPosition(location.time / 1000, location.latitude, location.longitude, battery()))
    if (trackId == 0L || _paused.value) return
    checkOffTrack(location)
    val prev = last
    if (prev != null && location.time - prev.time < 5000 && location.distanceTo(prev) < 10f) return
    last = location
    val p = TrackPoint(location.time, location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null)
    db.addPoint(trackId, segment, p)
    // ponytail: copies the segment on each point (5 s apart at most); an append-only structure if hours-long segments lag.
    _track.value = _track.value.let { s -> if (segment == shownSegment) s.dropLast(1) + listOf(s.last() + p) else s + listOf(listOf(p)) }
    shownSegment = segment
  }

  /** §2.7: every fix (about 1 s) is checked, well inside the 10 s the alert must take. */
  private fun checkOffTrack(location: Location) {
    val ref = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L)
    val notifications = getSystemService(NotificationManager::class.java)
    if (ref != monitorTrack) {
      monitorTrack = ref
      // ponytail: loaded once per reference; a 纠偏 change on it mid-recording applies from the next recording.
      val segments = if (ref == 0L) null else db.segments(ref).takeIf { it.any { s -> s.isNotEmpty() } }
      monitor = segments?.let { OffTrackMonitor(it, offTrackM.toDouble()) }
      refWalked = segments?.let { oriented(it, TrackStart(prefs.getBoolean(PREF_TRACK_REVERSED + ref, false), prefs.getFloat(PREF_TRACK_START + ref, 0f).toDouble())) }
      refLengthM = refWalked?.let { trackStats(it).distanceM } ?: 0.0
      _offTrack.value = false
      notifications.cancel(OFF_TRACK_NOTIFICATION)
    }
    leftM = refWalked?.let { w -> alongTrack(location.latitude, location.longitude, w).atM.singleOrNull()?.let { refLengthM - it } }
    // A fix that could be 50 m off on its own would raise false alerts: the 偏离提醒 pauses (§3.3 「定位不准 · 偏离提醒暂停」).
    if (location.hasAccuracy() && location.accuracy > OFF_TRACK_M) return run { _offTrack.value = false }
    val m = monitor ?: return
    val was = m.off
    m.update(location.latitude, location.longitude)
    _offTrack.value = m.off
    if (m.off == was) return
    if (!m.off) return notifications.cancel(OFF_TRACK_NOTIFICATION)
    @Suppress("DEPRECATION") // VibratorManager needs API 31; this works on all.
    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    notifications.notify(OFF_TRACK_NOTIFICATION, Notification.Builder(this, "offtrack")
      .setSmallIcon(R.drawable.wrong_location_fill1_24px)
      .setContentTitle("已偏离参考轨迹")
      .setContentText("离参考轨迹超过 $offTrackM m")
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .setAutoCancel(true)
      .build())
  }

  /**
   * Fetches the forecast where I am and notifies 出行提醒 of the next 3 h not known yet this recording. Offline, or
   * no fix yet (false): nothing (§2.9).
   */
  private fun refreshWeather(): Boolean {
    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
    val here = lastFix ?: return false
    thread {
      val known = knownRisks.orEmpty()
      val now = System.currentTimeMillis()
      val w = runCatching { runBlocking { fetchWeather(trailClient(prefs, quiet = true), here.latitude, here.longitude, here.altitude.takeIf { here.hasAltitude() }, hours = 4, now = now) } }.getOrNull() ?: return@thread
      val alerts = alerts(w, now, now + 3 * 3_600_000L)
      knownRisks = known + alerts.map { it.key }
      val fresh = alerts.filter { it.key !in known }
      if (fresh.isEmpty()) return@thread
      _risk.value = now to fresh.first()
      val text = fresh.joinToString("\n") { it.text }
      getSystemService(NotificationManager::class.java).notify(WEATHER_NOTIFICATION, Notification.Builder(this, "weather")
        .setSmallIcon(R.drawable.warning_fill1_24px)
        .setContentTitle("出行提醒")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true)
        .build())
    }
    return true
  }

  override fun onDestroy() {
    teamScope.cancel()
    handler.removeCallbacksAndMessages(null)
    getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    stopUpdates()
    // ponytail: ended here (location taken away) it keeps its start time as its name; name it too if that turns up.
    if (trackId != 0L) db.endTrack(trackId, System.currentTimeMillis())
    _activeTrack.value = null
    _paused.value = false
    _offTrack.value = false
    _track.value = emptyList()
    lastFix = null
    super.onDestroy()
  }
}

private const val TRIP_ENDED = "发起人已结束行程，位置共享已停止"
private const val TEAM_NOTIFICATION = 4
private const val LOW_BATTERY_NOTIFICATION = 5

/** [TeamEffects] on the phone: the 行程轨迹 file and the team's notices, with or without the service running. */
class AndroidTeamEffects(private val ctx: Context) : TeamEffects {
  override fun reported(team: Long, p: TeamPosition) = RecordingService.keepTrip(ctx, team, tripLine(p))

  override fun stoppedSharing(team: Long) = RecordingService.keepTrip(ctx, team, TRIP_BREAK)

  override fun tripOver(team: Long, byInitiator: Boolean) {
    RecordingService.endTrip(ctx, team, recording = RecordingService.activeTrack.value != null)
    if (byInitiator) notice(TEAM_NOTIFICATION, TRIP_ENDED)
  }

  override fun lowBattery() = notice(LOW_BATTERY_NOTIFICATION, "电量低，已降低共享频率")

  private fun notice(id: Int, text: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("team", "队伍", NotificationManager.IMPORTANCE_DEFAULT))
    nm.notify(id, Notification.Builder(ctx, "team")
      .setSmallIcon(R.drawable.group_fill1_24px)
      .setContentTitle(text)
      .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
      .setAutoCancel(true)
      .build())
  }
}

/** Battery %, or null if the phone won't say. */
fun Context.battery() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
