package dev.stars.outdoor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
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
  private var waypointsVersion by mutableIntStateOf(0)
  /** 标注 being edited, with its unsaved name and description. */
  private var editing by mutableStateOf<Long?>(null)
  private var editName by mutableStateOf("")
  private var editDescription by mutableStateOf("")
  private var trackPage by mutableStateOf(false)
  private var tracksVersion by mutableIntStateOf(0)
  private var importingTrack by mutableStateOf(false)
  // ponytail: a parsed file waiting for track selection is lost if the activity is recreated; the user opens it again.
  private var pendingImport by mutableStateOf<Pair<String, TrackFile>?>(null)
  private var pickChecked by mutableStateOf(setOf<Int>())
  /** 参考轨迹 (§2.7); the recording service reads it from prefs to raise 偏离提醒. */
  private var referenceTrack by mutableStateOf<Long?>(null)
  private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachPhoto) }
  private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording(resumeAfterGrant)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Debug builds may bundle sample PMTiles (app/src/debug/assets/data/, gitignored) for phones adb can't reach.
    for (f in assets.list("data").orEmpty()) File(dir, f).takeIf { !it.exists() }?.let { out ->
      assets.open("data/$f").use { input -> File(dir, "$f.tmp").outputStream().use { input.copyTo(it) } }
      File(dir, "$f.tmp").renameTo(out)
    }
    if (RecordingService.activeTrack.value == null) unfinishedTrack = TrackDb(this).use { it.openTrack() }
    referenceTrack = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L).takeIf { it != 0L }
    savedInstanceState?.let {
      batteryGuide = it.getBoolean("batteryGuide")
      resumeAfterGrant = it.getLong("resumeAfterGrant").takeIf { id -> id != 0L }
      // Kept so a photo picked after the activity was recreated still lands on its 标注.
      editing = it.getLong("editing").takeIf { id -> id != 0L }
      editName = it.getString("editName").orEmpty()
      editDescription = it.getString("editDescription").orEmpty()
      trackPage = it.getBoolean("trackPage")
      detailTrack = it.getLong("detailTrack").takeIf { id -> id != 0L }
    } ?: openedFile(intent)

    setContent {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      // ponytail: reads each import's header on the main thread; move off-thread if people import dozens.
      val style = remember(filesVersion) { style() }
      var menu by remember { mutableStateOf(false) }
      var datumVersion by remember { mutableIntStateOf(0) }
      // ponytail: loads and crunches the whole track on the main thread; go async when long tracks jank.
      val detail = detailTrack?.let { id ->
        remember(id, datumVersion) {
          TrackDb(this@MainActivity).use { db ->
            val segments = db.segments(id)
            Triple(db.trackName(id) + if (db.planned(id)) "（计划）" else "", db.datum(id), segments)
          }
        }
      }
      val detailSegments = detail?.third
      val referenceSegments = referenceTrack?.let { id -> remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.segments(id) } } }
      // Long-pressed point (card open), and 测距 from/to.
      var pressed by remember { mutableStateOf<Position?>(null) }
      var measureFrom by remember { mutableStateOf<Position?>(null) }
      var measureTo by remember { mutableStateOf<Position?>(null) }
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      ) {
        // Style content (layers), unlike MaplibreMap's trailing lambda, which only holds overlays.
        referenceSegments?.let { segments ->
          val source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(segments) { displayLine(segments) }))
          LineLayer(id = "reference-track", source = source, color = const(Color(0xFF3B7DD8)), width = const(6.dp))
        }
        detailSegments?.let { segments ->
          val source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(segments) { displayLine(segments) }))
          LineLayer(id = "detail-track", source = source, color = const(Color(0xFFE4572E)), width = const(4.dp))
        }
        val from = measureFrom
        val to = measureTo
        if (from != null && to != null) {
          val line = "{\"type\":\"LineString\",\"coordinates\":[[${from.longitude},${from.latitude}],[${to.longitude},${to.latitude}]]}"
          LineLayer(id = "measure", source = rememberGeoJsonSource(GeoJsonData.JsonString(line)), color = const(Color.Black), width = const(2.dp))
        }
      }
      var offlinePage by remember { mutableStateOf(false) }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && it.extension.lowercase() in importableExtensions }
      }
      val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
      val pickTrackFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importTrackFile) }
      LaunchedEffect(detailTrack) {
        val points = detailSegments?.flatten().orEmpty()
        if (points.isEmpty()) return@LaunchedEffect
        val box = BoundingBox(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
        // Bottom padding keeps the track above the detail panel.
        state.fitCameraToBounds(box, 0.0, 0.0, DpPadding(40.dp, 80.dp, 40.dp, 480.dp))
      }
      val recording by RecordingService.activeTrack.collectAsState()
      val paused by RecordingService.paused.collectAsState()
      val waypoints = remember(waypointsVersion) { TrackDb(this@MainActivity).use { it.waypoints() } }
      Box(Modifier.fillMaxSize()) {
        MaplibreMap(
          modifier = Modifier.fillMaxSize(),
          state = state,
          // §2.2 2.5D: two-finger drag tilts, up to 60°.
          cameraConstraints = CameraConstraints(maxPitch = 60.0),
          interactions = MapInteractions(MapInteractions.Standard) {
            callbacks {
              click {
                onEvent { e ->
                  pressed = null
                  if (measureFrom == null) return@onEvent ClickResult.Pass
                  measureTo = e.position ?: return@onEvent ClickResult.Pass
                  ClickResult.Consume
                }
              }
              longClick {
                onEvent { e ->
                  pressed = e.position ?: return@onEvent ClickResult.Pass
                  ClickResult.Consume
                }
              }
            }
          },
        ) {
          // ponytail: one composable per 标注; switch to a GeoJSON symbol layer if people keep thousands.
          for (w in waypoints) {
            val at = Position(latitude = w.lat, longitude = w.lon)
            Box(Modifier.placedAt(at).size(16.dp).background(Color(0xFFF2A900), CircleShape).clickable { openWaypoint(w) })
            if (w.name.isNotEmpty()) BasicText(w.name, Modifier.placedAt(at).padding(top = 40.dp), style = TextStyle(fontSize = 12.sp))
          }
          for (at in listOfNotNull(pressed, measureFrom, measureTo)) Box(Modifier.placedAt(at).size(10.dp).background(Color.Black, CircleShape))
        }
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.End) {
          MapButton("菜单") { menu = !menu }
          if (menu) {
            MapButton("我的轨迹") { menu = false; trackPage = true }
            MapButton("离线地图") { menu = false; offlinePage = true }
          }
          // §2.1 compass stand-in: back to north-up and out of 2.5D.
          val camera = state.cameraPosition
          if (camera.tilt != 0.0 || camera.bearing != 0.0) MapButton("回正") { state.setCameraPosition(camera.copy(bearing = 0.0, tilt = 0.0)) }
        }
        measureFrom?.let { from ->
          val to = measureTo
          val distance = to?.let { FloatArray(1).also { r -> Location.distanceBetween(from.latitude, from.longitude, it.latitude, it.longitude, r) }[0].toDouble() }
          MeasureBanner(distance, onClose = { measureFrom = null; measureTo = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          // §2.4: one tap stores the coordinate; recording carries on, name and photo can be added later.
          if (recording != null && !paused) BasicText(
            "标注当前位置",
            Modifier.padding(bottom = 12.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable {
              // A fix older than 30 s (GPS lost) would store the wrong place; ask to wait instead.
              val fix = RecordingService.lastFix?.takeIf { System.currentTimeMillis() - it.time < 30_000 } ?: return@clickable toast("还没有定位，请稍候")
              addWaypoint(fix.time, fix.latitude, fix.longitude, if (fix.hasAltitude()) fix.altitude else null)
              toast("已标注，名称和照片可以稍后补")
            }.padding(12.dp),
          )
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
        if (trackPage) {
          BackHandler { trackPage = false }
          TrackListScreen(
            tracks = remember(tracksVersion) { TrackDb(this@MainActivity).use { it.tracks() } },
            importing = importingTrack,
            onOpen = { detailTrack = it; trackPage = false },
            // Track files often arrive with no or a generic MIME type; the content decides the format.
            onImport = { pickTrackFile.launch(arrayOf("*/*")) },
          )
        }
        val id = detailTrack
        if (id != null && detail != null) {
          BackHandler { detailTrack = null }
          val (name, datum, segments) = detail
          TrackDetailScreen(
            name, remember(segments) { trackStats(segments) }, datum,
            reference = id == referenceTrack,
            onReference = { setReference(if (id == referenceTrack) null else id) },
            onDatum = { d -> TrackDb(this@MainActivity).use { it.setDatum(id, d) }; datumVersion++; waypointsVersion++ },
            onExport = { kml -> exportTrack(id, kml) },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
        pressed?.let { at ->
          BackHandler { pressed = null }
          PointCard(
            at.latitude, at.longitude,
            onWaypoint = { pressed = null; openWaypoint(addWaypoint(System.currentTimeMillis(), at.latitude, at.longitude, null)) },
            onMeasure = { pressed = null; measureFrom = at; measureTo = null },
            onCopy = {
              getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("坐标", coordinateText(at.latitude, at.longitude)))
              // Android 13+ confirms copies itself.
              if (Build.VERSION.SDK_INT < 33) toast("已复制坐标")
            },
            onShare = {
              val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "坐标（WGS-84）：" + coordinateText(at.latitude, at.longitude))
              startActivity(Intent.createChooser(send, "分享坐标"))
            },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
        pendingImport?.let { (fileName, file) ->
          BackHandler { pendingImport = null }
          ImportPickScreen(
            fileName, file.tracks, pickChecked,
            onToggle = { i -> pickChecked = if (i in pickChecked) pickChecked - i else pickChecked + i },
            onImport = { pendingImport = null; saveImport(fileName, file, pickChecked.sorted()) },
          )
        }
        editing?.let { id ->
          val w = waypoints.firstOrNull { it.id == id } ?: return@let
          BackHandler { saveWaypoint(w); editing = null }
          WaypointScreen(
            w, editName, editDescription,
            onName = { editName = it },
            onDescription = { editDescription = it },
            onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDelete = {
              TrackDb(this@MainActivity).use { it.deleteWaypoint(id) }
              w.photo?.let { File(it).delete() }
              editing = null
              waypointsVersion++
            },
            onDone = { saveWaypoint(w); editing = null },
          )
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

  /** A track file opened from another app ("用其他应用打开", or shared to us), e.g. received in WeChat. */
  private fun openedFile(intent: Intent?) {
    // Reopened from Recents, the original intent comes back: it was imported already.
    if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
    val uri = when (intent.action) {
      Intent.ACTION_VIEW -> intent.data
      Intent.ACTION_SEND ->
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
      else -> null
    } ?: return
    importTrackFile(uri)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    openedFile(intent)
  }

  private fun importTrackFile(uri: Uri) {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "轨迹"
    importingTrack = true
    thread {
      val result = runCatching { contentResolver.openInputStream(uri)!!.use { it.readAtMost(MAX_TRACK_FILE_BYTES + 1) }.takeIf { it.size <= MAX_TRACK_FILE_BYTES }?.let(::parseTrackFile) }
      runOnUiThread {
        val file = result.getOrNull()
        when {
          result.isFailure -> toast("无法读取 $name")
          file == null -> toast("文件超过 50 MB")
          file.tracks.isEmpty() && file.waypoints.isEmpty() -> toast("$name 中没有轨迹或标注")
          file.tracks.size > 1 -> {
            pickChecked = file.tracks.indices.toSet()
            pendingImport = name to file
          }
          else -> return@runOnUiThread saveImport(name, file, file.tracks.indices.toList())
        }
        importingTrack = false
      }
    }
  }

  /** Imports tracks [selected] of [file]; its 标注 go with the first one (or stand alone if the file has no track). */
  private fun saveImport(fileName: String, file: TrackFile, selected: List<Int>) {
    importingTrack = true
    thread {
      val ids = runCatching {
        // Photos from our own zip export: the 标注's <link> names its file in the zip.
        val waypoints = file.waypoints.map { w ->
          val bytes = w.photo?.let(file.photos::get)
          val photo = bytes?.let { File(filesDir, "photos/import-${System.nanoTime()}-${File(w.photo).name}").apply { parentFile!!.mkdirs(); writeBytes(it) }.path }
          w.copy(photo = photo)
        }
        TrackDb(this).use { db ->
          if (file.tracks.isEmpty()) {
            for (w in waypoints) db.updateWaypoint(db.addWaypoint(null, w.timeMs, w.lat, w.lon, w.ele), w.name, w.description, w.photo)
            emptyList()
          } else selected.mapIndexed { n, i ->
            val t = file.tracks[i]
            db.importTrack(t, importName(t, fileName, i, file.tracks.size), if (n == 0) waypoints else emptyList(), System.currentTimeMillis())
          }
        }
      }
      runOnUiThread {
        importingTrack = false
        tracksVersion++
        waypointsVersion++
        ids.onSuccess {
          toast("已导入 $fileName")
          it.firstOrNull()?.let { id -> trackPage = false; detailTrack = id }
        }.onFailure { toast("无法导入 $fileName") }
      }
    }
  }

  /** Adds a 标注, on the track being recorded if any. */
  private fun addWaypoint(timeMs: Long, lat: Double, lon: Double, ele: Double?): Waypoint {
    val track = RecordingService.activeTrack.value
    val id = TrackDb(this).use { it.addWaypoint(track, timeMs, lat, lon, ele) }
    waypointsVersion++
    return Waypoint(id, track, timeMs, lat, lon, ele, "", "", null)
  }

  private fun openWaypoint(w: Waypoint) {
    editName = w.name
    editDescription = w.description
    editing = w.id
  }

  private fun saveWaypoint(w: Waypoint, photo: String? = w.photo) {
    TrackDb(this).use { it.updateWaypoint(w.id, editName.trim(), editDescription.trim(), photo) }
    waypointsVersion++
  }

  // ponytail: copies on the main thread; fine for phone photos, move off-thread if it janks.
  private fun attachPhoto(uri: Uri) {
    val id = editing ?: return
    val w = TrackDb(this).use { db -> db.waypoints().firstOrNull { it.id == id } } ?: return
    val file = File(filesDir, "photos/$id-${System.currentTimeMillis()}.jpg").apply { parentFile!!.mkdirs() }
    val ok = runCatching { contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }.isSuccess
    if (!ok) return toast("无法读取照片").also { file.delete() }
    saveWaypoint(w, file.path)
    w.photo?.let { File(it).delete() }
  }

  private fun setReference(id: Long?) {
    getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_REFERENCE, id ?: 0L).apply()
    referenceTrack = id
    // The service only notices the change on its next fix; don't leave an alert for the old one up until then.
    getSystemService(android.app.NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    // ponytail: alerts ride on the recording service's GPS; a separate follow-only service if people follow without recording.
    if (id != null && RecordingService.activeTrack.value == null) toast("记录轨迹时，偏离超过 ${OFF_TRACK_M.toInt()} m 会提醒")
  }

  private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean("batteryGuide", batteryGuide)
    outState.putLong("resumeAfterGrant", resumeAfterGrant ?: 0L)
    outState.putLong("editing", editing ?: 0L)
    outState.putString("editName", editName)
    outState.putString("editDescription", editDescription)
    outState.putBoolean("trackPage", trackPage)
    outState.putLong("detailTrack", detailTrack ?: 0L)
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

  /**
   * GPX by default (§2.6), or KML. 标注 photos only travel with GPX: then it's a zip of the GPX and a photos/ folder,
   * each photo linked from its <wpt>.
   */
  // ponytail: export runs on the main thread; move to a coroutine once tracks get long enough to jank.
  private fun exportTrack(id: Long, kml: Boolean) {
    val dir = File(cacheDir, "exports").apply { mkdirs() }
    val (file, type) = TrackDb(this).use { db ->
      val name = db.trackName(id)
      val all = db.waypoints(id)
      val photos = if (kml) emptyList() else all.filter { w -> w.photo?.let { File(it).isFile } == true }
      val waypoints = all.map { w -> w.copy(photo = if (w in photos) "photos/" + File(w.photo!!).name else null) }
      when {
        kml -> File(dir, "track-$id.kml").apply { writeText(toKml(name, db.segments(id), waypoints)) } to "application/vnd.google-earth.kml+xml"
        photos.isEmpty() -> File(dir, "track-$id.gpx").apply { writeText(toGpx(name, db.segments(id), waypoints, db.planned(id))) } to "application/gpx+xml"
        else -> File(dir, "track-$id.zip").apply {
          ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("track-$id.gpx"))
            zip.write(toGpx(name, db.segments(id), waypoints, db.planned(id)).toByteArray())
            for (w in photos) {
              zip.putNextEntry(ZipEntry("photos/" + File(w.photo!!).name))
              File(w.photo).inputStream().use { it.copyTo(zip) }
            }
          }
        } to "application/zip"
      }
    }
    val uri = FileProvider.getUriForFile(this, "dev.stars.outdoor.files", file)
    val send = Intent(Intent.ACTION_SEND)
      .setType(type)
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "分享轨迹"))
  }
}

@Composable
private fun MapButton(text: String, onClick: () -> Unit) {
  BasicText(text, Modifier.padding(bottom = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp))
}

// ponytail: keeps every n-th point for display (MapLibre simplifies further per zoom); Douglas–Peucker if sharp turns get lost.
/** The track as a GeoJSON MultiLineString, thinned to about [max] points (§2.6: raw points kept, thinned for display). */
private fun displayLine(segments: List<List<TrackPoint>>, max: Int = 5000): String {
  val step = maxOf(1, segments.sumOf { it.size } / max)
  return segments.joinToString(",", "{\"type\":\"MultiLineString\",\"coordinates\":[", "]}") { seg ->
    seg.filterIndexed { i, _ -> i % step == 0 || i == seg.lastIndex }.joinToString(",", "[", "]") { "[${it.lon},${it.lat}]" }
  }
}
