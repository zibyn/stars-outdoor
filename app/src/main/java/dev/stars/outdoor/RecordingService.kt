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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ponytail: platform GPS only; HMS/GMS fused location (spec §3.1) when battery or indoor fixes matter.
class RecordingService : Service(), LocationListener {
  companion object {
    private val _activeTrack = MutableStateFlow<Long?>(null)
    /** Id of the track being recorded, or null. */
    val activeTrack: StateFlow<Long?> = _activeTrack
  }

  private lateinit var db: TrackDb
  private var trackId = 0L
  private var last: Location? = null

  override fun onBind(intent: Intent?) = null

  override fun onCreate() {
    super.onCreate()
    db = TrackDb(this)
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel("recording", "轨迹记录", NotificationManager.IMPORTANCE_LOW))
    val notification = Notification.Builder(this, "recording")
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle("正在记录轨迹")
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, "停止记录",
        PendingIntent.getService(this, 0, Intent(this, RecordingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)).build())
      .build()
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(1, notification)
    }
    trackId = db.startTrack(System.currentTimeMillis())
    _activeTrack.value = trackId
    try {
      // Ask for every fix and apply the "5 s or 10 m, whichever first" rule ourselves (§2.5);
      // LocationManager's minTime/minDistance would require both.
      getSystemService(LocationManager::class.java)
        .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
    } catch (e: SecurityException) {
      stopSelf()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "stop") stopSelf()
    // ponytail: a killed recording is not restarted (a sticky restart would silently open a new track);
    // the "继续记录 / 结束并保存" recovery prompt of §2.5 replaces this.
    return START_NOT_STICKY
  }

  override fun onLocationChanged(location: Location) {
    val prev = last
    if (prev != null && location.time - prev.time < 5000 && location.distanceTo(prev) < 10f) return
    last = location
    db.addPoint(trackId, TrackPoint(location.time, location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null))
  }

  override fun onDestroy() {
    getSystemService(LocationManager::class.java).removeUpdates(this)
    db.endTrack(trackId, System.currentTimeMillis())
    db.close()
    _activeTrack.value = null
    super.onDestroy()
  }
}
