package com.starsdom.trail.team

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.starsdom.trail.SharedPrefs
import com.starsdom.trail.account.AccountStore
import com.starsdom.trail.offline.trailClient
import com.starsdom.trail.recording.AndroidTeamEffects
import com.starsdom.trail.recording.RecordingService
import com.starsdom.trail.recording.battery
import com.starsdom.trail.track.TrackLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// 队伍会话 on Android: the one session of the process, and what it's wired to.

@Volatile private var instance: TeamSession? = null

/** The process's one, on the main thread, wired to Android. */
fun TeamSession.Companion.get(context: Context): TeamSession =
  instance ?: synchronized(TeamSession) { instance ?: teamSession(context.applicationContext).also { instance = it } }

/** The session on Android: the HTTP transport, the 行程轨迹 and notices, the network, 对话 notifications, the service. */
private fun teamSession(ctx: Context): TeamSession {
  val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
  val accounts = AccountStore(prefs)
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val session = TeamSession(
    HttpTeamTransport(trailClient(prefs, quiet = true)), SharedPrefs(prefs), ctx.filesDir.path, TrackLibrary.get(ctx), AndroidTeamEffects(ctx),
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
