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
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ponytail: platform GPS only; HMS/GMS fused location (spec §3.1) when battery or indoor fixes matter.
class RecordingService : Service(), LocationListener {
  companion object {
    /** Extra: id of an unfinished track to continue in a new segment instead of starting a new track. */
    const val EXTRA_TRACK = "track"
    private val _activeTrack = MutableStateFlow<Long?>(null)
    /** Id of the track being recorded, or null. */
    val activeTrack: StateFlow<Long?> = _activeTrack
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    /** Latest GPS fix while recording (before the 5 s / 10 m filter), for "标注当前位置". Main thread only. */
    var lastFix: Location? = null
      private set
  }

  private lateinit var db: TrackDb
  private lateinit var wakeLock: PowerManager.WakeLock
  private var trackId = 0L
  private var segment = 0
  private var last: Location? = null
  /** 偏离提醒 against the 参考轨迹 [monitorTrack] (0 = none). */
  private var monitor: OffTrackMonitor? = null
  private var monitorTrack = 0L

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
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(1, notification())
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      "stop" -> stopSelf()
      "pause" -> if (!_paused.value) {
        stopUpdates()
        // No fixes while paused: drop a stale 偏离提醒 and start fresh on resume.
        monitorTrack = 0L
        monitor = null
        getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
        _paused.value = true
        updateNotification()
      }
      "resume" -> if (_paused.value) {
        segment++
        _paused.value = false
        startUpdates()
        updateNotification()
      }
      else -> if (trackId == 0L) {
        val resumed = intent?.getLongExtra(EXTRA_TRACK, 0L) ?: 0L
        if (resumed != 0L) {
          trackId = resumed
          segment = db.lastSegment(resumed) + 1
        } else {
          trackId = db.startTrack(System.currentTimeMillis())
        }
        _activeTrack.value = trackId
        startUpdates()
      }
    }
    // A killed recording is not restarted (a sticky restart would carry no track id); the app asks
    // "继续记录 / 结束并保存" on next launch instead (§2.5).
    return START_NOT_STICKY
  }

  private fun startUpdates() {
    last = null
    try {
      // Ask for every fix and apply the "5 s or 10 m, whichever first" rule ourselves (§2.5);
      // LocationManager's minTime/minDistance would require both.
      getSystemService(LocationManager::class.java)
        .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
      if (!wakeLock.isHeld) wakeLock.acquire()
    } catch (e: SecurityException) {
      stopSelf()
    }
  }

  private fun stopUpdates() {
    getSystemService(LocationManager::class.java).removeUpdates(this)
    if (wakeLock.isHeld) wakeLock.release()
  }

  private fun action(label: String, action: String) = Notification.Action.Builder(null, label,
    PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)).build()

  private fun notification() = Notification.Builder(this, "recording")
    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
    .setContentTitle(if (_paused.value) "轨迹记录已暂停" else "正在记录轨迹")
    .setOngoing(true)
    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    .addAction(if (_paused.value) action("继续记录", "resume") else action("暂停记录", "pause"))
    .addAction(action("停止记录", "stop"))
    .build()

  private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(1, notification())

  override fun onLocationChanged(location: Location) {
    lastFix = location
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

  override fun onDestroy() {
    getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    stopUpdates()
    if (trackId != 0L) db.endTrack(trackId, System.currentTimeMillis())
    db.close()
    _activeTrack.value = null
    _paused.value = false
    lastFix = null
    super.onDestroy()
  }
}
