package dev.stars.outdoor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

class MainActivity : ComponentActivity() {
  // Offline PMTiles live in the app's external files dir (scripts/push-data.sh puts them there); user imports in imports/.
  private val dir by lazy { getExternalFilesDir(null)!! }
  private val importsDir by lazy { File(dir, "imports").apply { mkdirs() } }
  private var filesVersion by mutableIntStateOf(0)
  private var importing by mutableStateOf(false)
  /** Unfinished track left by a killed recording, awaiting "继续记录 / 结束并保存". */
  private var unfinishedTrack by mutableStateOf<Long?>(null)
  private var batteryGuide by mutableStateOf(false)
  private var detailTrack by mutableStateOf<Long?>(null)
  private var resumeAfterGrant: Long? = null
  private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording(resumeAfterGrant)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (RecordingService.activeTrack.value == null) unfinishedTrack = TrackDb(this).use { it.openTrack() }
    savedInstanceState?.let {
      batteryGuide = it.getBoolean("batteryGuide")
      resumeAfterGrant = it.getLong("resumeAfterGrant").takeIf { id -> id != 0L }
    }

    setContent {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      // ponytail: reads each import's header on the main thread; move off-thread if people import dozens.
      val style = remember(filesVersion) { style() }
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      )
      var offlinePage by remember { mutableStateOf(false) }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && it.extension.lowercase() in importableExtensions }
      }
      val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
      val recording by RecordingService.activeTrack.collectAsState()
      val paused by RecordingService.paused.collectAsState()
      Box(Modifier.fillMaxSize()) {
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = state)
        // ponytail: straight to 离线地图; turn into a real 菜单 once it has a second entry.
        BasicText(
          "离线地图",
          Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable { offlinePage = true }.padding(12.dp),
        )
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          BasicText(
            if (recording == null) "上一条轨迹" else if (paused) "继续记录" else "暂停记录",
            Modifier.padding(bottom = 12.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable {
              if (recording == null) detailTrack = TrackDb(this@MainActivity).use { it.lastEndedTrack() } ?: return@clickable toast("还没有记录过轨迹")
              else startService(Intent(this@MainActivity, RecordingService::class.java).setAction(if (paused) "resume" else "pause"))
            }.padding(12.dp),
          )
          Box(
            Modifier.size(72.dp).background(if (recording == null) Color(0xFF2F9E6E) else Color(0xFFE4572E), CircleShape).clickable {
              if (recording != null) stopService(Intent(this@MainActivity, RecordingService::class.java)) else record(null)
            },
            contentAlignment = Alignment.Center,
          ) {
            BasicText(if (recording == null) "开始" else "停止", style = TextStyle(color = Color.White, fontSize = 18.sp))
          }
        }
        if (offlinePage) {
          BackHandler { offlinePage = false }
          OfflineMapScreen(
            files = files,
            importing = importing,
            // MBTiles/PMTiles have no registered MIME type; filter by extension after picking.
            onImport = { pickFile.launch(arrayOf("*/*")) },
            onDelete = { it.delete(); filesVersion++ },
          )
        }
        detailTrack?.let { id ->
          BackHandler { detailTrack = null }
          // ponytail: loads and crunches the whole track on the main thread; go async when long tracks jank.
          val (name, stats) = remember(id) { TrackDb(this@MainActivity).use { trackName(it, id) to trackStats(it.segments(id)) } }
          TrackDetailScreen(name, stats, onExport = { exportTrack(id) })
        }
        unfinishedTrack?.let { id ->
          RecoveryPrompt(
            onContinue = { unfinishedTrack = null; record(id) },
            onFinish = { unfinishedTrack = null; TrackDb(this@MainActivity).use { it.endAtLastPoint(id) } },
          )
        }
        if (batteryGuide) {
          BatteryGuide(
            Build.MANUFACTURER,
            onIgnoreOptimizations = {
              val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
              runCatching { startActivity(request) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            onDismiss = { batteryGuide = false },
          )
        }
      }
    }
  }

  private fun style(): String {
    val base = assets.open("style.json").bufferedReader().readText().replace("__DIR__", dir.absolutePath)
    return withImports(base, importsDir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull(::importOf))
  }

  private fun importOf(file: File): Import? = runCatching {
    when (file.extension) {
      "pmtiles" -> pmtilesKind(RandomAccessFile(file, "r").use { ByteArray(127).also(it::readFully) })?.let { Import("pmtiles://file://${file.absolutePath}", it) }
      "mbtiles" -> SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT value FROM metadata WHERE name = 'format'", null).use { c ->
          val vector = c.moveToFirst() && c.getString(0) == "pbf"
          Import("mbtiles://${file.absolutePath}", if (vector) Tiles.Vector else Tiles.Raster)
        }
      }
      else -> null
    }
  }.getOrNull()

  private fun importFile(uri: Uri) {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    }?.let { File(it).name } ?: return
    val ext = File(name).extension.lowercase()
    if (ext !in importableExtensions) return toast("只支持 MBTiles / PMTiles 文件")
    importing = true
    thread {
      // Copy and validate in a staging dir (not listed, not in the style), then move into place, so a
      // half-copied or invalid file never replaces an existing import. Leftovers from a killed copy go here too.
      val staging = File(importsDir, ".staging").apply { deleteRecursively(); mkdirs() }
      val tmp = File(staging, File(name).nameWithoutExtension + ".$ext")
      val ok = runCatching {
        contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        checkNotNull(importOf(tmp))
        check(tmp.renameTo(File(importsDir, tmp.name)))
      }.isSuccess
      if (!ok) tmp.delete()
      runOnUiThread {
        importing = false
        filesVersion++
        toast(if (ok) "已导入 $name" else "无法读取 $name")
      }
    }
  }

  private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean("batteryGuide", batteryGuide)
    outState.putLong("resumeAfterGrant", resumeAfterGrant ?: 0L)
  }

  /** Starts recording, or continues unfinished track [resume] in a new segment, asking for location first if needed. */
  private fun record(resume: Long?) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return startRecording(resume)
    resumeAfterGrant = resume
    askPermissions.launch(
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
  }

  private fun startRecording(resume: Long?) {
    startForegroundService(Intent(this, RecordingService::class.java).apply { if (resume != null) putExtra(RecordingService.EXTRA_TRACK, resume) })
    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
    if (!prefs.getBoolean("battery_guide_shown", false)) {
      prefs.edit().putBoolean("battery_guide_shown", true).apply()
      batteryGuide = true
    }
  }

  private fun trackName(db: TrackDb, id: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(db.startedAt(id)))

  // ponytail: export runs on the main thread; move to a coroutine once tracks get long enough to jank.
  private fun exportTrack(id: Long) {
    val file = File(cacheDir, "exports/track-$id.gpx").apply { parentFile!!.mkdirs() }
    TrackDb(this).use { file.writeText(toGpx(trackName(it, id), it.segments(id))) }
    val uri = FileProvider.getUriForFile(this, "dev.stars.outdoor.files", file)
    val send = Intent(Intent.ACTION_SEND)
      .setType("application/gpx+xml")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "分享 GPX"))
  }
}
