package dev.stars.outdoor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      val style = remember(filesVersion) { style() }
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      )
      var offlinePage by remember { mutableStateOf(false) }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && (it.extension == "pmtiles" || it.extension == "mbtiles") }
      }
      val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
      val recording by RecordingService.activeTrack.collectAsState()
      val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording()
      }
      Box(Modifier.fillMaxSize()) {
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = state)
        // ponytail: straight to 离线地图; turn into a real 菜单 once it has a second entry.
        BasicText(
          "离线地图",
          Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable { offlinePage = true }.padding(12.dp),
        )
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
    if (ext != "pmtiles" && ext != "mbtiles") return toast("只支持 MBTiles / PMTiles 文件")
    importing = true
    thread {
      // Copy to a temp name first so a half-copied file is never picked up by the style.
      val tmp = File(importsDir, "$name.part")
      val target = File(importsDir, File(name).nameWithoutExtension + ".$ext")
      val ok = runCatching {
        contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(target))
        checkNotNull(importOf(target))
      }.isSuccess
      if (!ok) { tmp.delete(); target.delete() }
      runOnUiThread {
        importing = false
        filesVersion++
        toast(if (ok) "已导入 $name" else "无法读取 $name")
      }
    }
  }

  private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
