package dev.stars.outdoor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Offline PMTiles live in the app's external files dir (scripts/push-data.sh puts them there).
    val dir = getExternalFilesDir(null)!!
    val style = assets.open("style.json").bufferedReader().readText().replace("__DIR__", dir.absolutePath)

    setContent {
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      )
      val recording by RecordingService.activeTrack.collectAsState()
      val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording()
      }
      Box(Modifier.fillMaxSize()) {
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = state)
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          if (recording == null) {
            BasicText(
              "导出上一条轨迹 GPX",
              Modifier.padding(bottom = 12.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable { exportLastTrack() }.padding(12.dp),
            )
          }
          Box(
            Modifier.size(72.dp).background(if (recording == null) Color(0xFF2F9E6E) else Color(0xFFE4572E), CircleShape).clickable {
              when {
                recording != null -> stopService(Intent(this@MainActivity, RecordingService::class.java))
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> startRecording()
                else -> askPermissions.launch(
                  arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                    if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
                )
              }
            },
            contentAlignment = Alignment.Center,
          ) {
            BasicText(if (recording == null) "开始" else "停止", style = TextStyle(color = Color.White, fontSize = 18.sp))
          }
        }
      }
    }
  }

  private fun startRecording() {
    startForegroundService(Intent(this, RecordingService::class.java))
  }

  // ponytail: export runs on the main thread; move to a coroutine once tracks get long enough to jank.
  private fun exportLastTrack() {
    val db = TrackDb(this)
    val id = db.lastEndedTrack() ?: return db.close()
    val name = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(db.startedAt(id)))
    val file = File(cacheDir, "exports/track-$id.gpx").apply { parentFile!!.mkdirs() }
    file.writeText(toGpx(name, db.points(id)))
    db.close()
    val uri = FileProvider.getUriForFile(this, "dev.stars.outdoor.files", file)
    val send = Intent(Intent.ACTION_SEND)
      .setType("application/gpx+xml")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "分享 GPX"))
  }
}
