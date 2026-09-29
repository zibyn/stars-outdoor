package dev.stars.outdoor

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestInterceptor
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
  private val packagesDir by lazy { File(dir, "packages").apply { mkdirs() } }
  private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
  private val deviceId by lazy { deviceId(prefs) }
  private val api by lazy { api(prefs) }
  private val accounts by lazy { AccountStore(prefs) }
  /** Logged in (§2.12); null: everything but 队伍 and 同步 works, data stays on the phone. */
  private var account by mutableStateOf<Account?>(null)
  private var accountPage by mutableStateOf(false)
  /** 同步 on (§2.12), photos over mobile data too, and 开启同步 waiting for the login it asked for. */
  private var syncOn by mutableStateOf(false)
  private var mobilePhotos by mutableStateOf(false)
  private var syncAfterLogin = false
  /** §2.2 layer drawer choices, kept in prefs. */
  private var basemap by mutableStateOf(Basemap.Terrain)
  private var contours by mutableStateOf(true)
  private var hillshade by mutableStateOf(true)
  /** 周边路网 (§2.8) shown; a tap on the map then lists 经过这里的轨迹, with where its 公开轨迹 came from. */
  private var nearby by mutableStateOf(false)
  /** A network that reaches the internet: picks tiles or snapshots for the 公开轨迹 layer. */
  private var online by mutableStateOf(true)
  private val network = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
      val up = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      runOnUiThread { online = up }
    }
    override fun onLost(n: Network) = runOnUiThread { online = false }
  }
  private var nearbyTracks by mutableStateOf(listOf<NearbyTrack>())
  private var nearbyNote by mutableStateOf<String?>(null)
  /** Taps looked up; a newer tap's answer replaces an older one still in flight. */
  private var nearbySeq = 0
  /** OpenFreeMap's style JSON for overseas 地形 / 标准, once fetched. */
  private var openFreeMap by mutableStateOf<String?>(null)
  private var downloading by mutableStateOf(false)
  /** The server's offline data version, once asked; packages from another version show 可更新. */
  private var dataVersion by mutableStateOf<String?>(null)
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
  /** 沿途天气 (§2.9) by track, once fetched or read from the cache, and the tracks being fetched. */
  private var weather by mutableStateOf(mapOf<Long, TrackWeather>())
  private var weatherLoading by mutableStateOf(setOf<Long>())
  /** Latest fetch per track: a forced reload overtakes one in flight, whose answer is then dropped. */
  private val weatherSeq = mutableMapOf<Long, Int>()
  private var pace by mutableStateOf(Pace.Medium)
  /** 搜索 (§2.10): what's typed, what it found, and where the results came from. */
  private var searchQuery by mutableStateOf("")
  private var searchResults by mutableStateOf(listOf<Place>())
  private var searchNote by mutableStateOf<String?>(null)
  private val aliases by lazy { aliasPlaces(assets.open("peak-aliases.tsv").bufferedReader().readText()) }
  /** 出行提醒 banner closed; it comes back with the next forecast. */
  private var bannerClosed by mutableStateOf(false)
  /** 队伍 (§2.11): its page, opened again once a login it asked for is done; 尾迹 shown; 省电模式. */
  private var teamPage by mutableStateOf(false)
  private var teamAfterLogin = false
  private var trails by mutableStateOf(true)
  private var teamSaver by mutableStateOf(false)
  private var teamName by mutableStateOf("")
  /** Team to share with once location is granted (0 = none; else it's recording that asked). */
  private var teamAfterGrant = 0L
  /** 队伍对话 (§2.11): the drawer open, the last message read, how the last 求助 is getting on. */
  private var chat by mutableStateOf(false)
  private var readSeq by mutableLongStateOf(0L)
  private var sosNote by mutableStateOf<String?>(null)
  private var sosRetry: Runnable? = null
  /** Counts onResume, so an ended team's 对话 is caught up each time the app comes back (it has no socket). */
  private var resumes by mutableIntStateOf(0)
  private val handler = Handler(Looper.getMainLooper())
  // ponytail: photos in memory only, the last 40; a disk cache if people scroll long chats offline.
  private val images = LruCache<String, ImageBitmap>(40)
  private val pickChatPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }
  private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachPhoto) }
  private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
      // Still in the team on the server: the next launch with location allowed picks it up.
      if (teamAfterGrant != 0L) toast("需要定位权限才能和队伍共享位置").also { RecordingService.showTeam(null) }
    } else if (teamAfterGrant != 0L) startTeam(teamAfterGrant)
    else startRecording(resumeAfterGrant)
    teamAfterGrant = 0L
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Map tiles from our API (天地图) carry the same headers as its other calls: rate limit and version gate.
    // Throws once the runtime exists (activity recreated): the interceptor from the first time is still in place.
    runCatching {
      DefaultMapRuntime.configure(MapRuntimeOptions(requestInterceptor = MapRequestInterceptor(headers = { request ->
        if (request.url.startsWith(BuildConfig.API_URL)) apiHeaders(deviceId, BuildConfig.VERSION_CODE.toLong()) else emptyMap()
      })))
    }
    basemap = Basemap.entries.firstOrNull { it.name == prefs.getString(PREF_BASEMAP, null) } ?: Basemap.Terrain
    contours = prefs.getBoolean(PREF_CONTOURS, true)
    hillshade = prefs.getBoolean(PREF_HILLSHADE, true)
    nearby = prefs.getBoolean(PREF_NEARBY, false)
    getSystemService(ConnectivityManager::class.java).run {
      online = getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
      registerDefaultNetworkCallback(network)
    }
    // Debug builds may bundle sample PMTiles (app/src/debug/assets/data/, gitignored) for phones adb can't reach.
    for (f in assets.list("data").orEmpty()) File(dir, f).takeIf { !it.exists() }?.let { out ->
      assets.open("data/$f").use { input -> File(dir, "$f.tmp").outputStream().use { input.copyTo(it) } }
      File(dir, "$f.tmp").renameTo(out)
    }
    if (RecordingService.activeTrack.value == null) unfinishedTrack = TrackDb(this).use { it.openTrack() }
    referenceTrack = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L).takeIf { it != 0L }
    pace = pace(prefs)
    account = accounts.get()
    syncOn = prefs.getBoolean(PREF_SYNC, false)
    mobilePhotos = prefs.getBoolean(PREF_SYNC_MOBILE_PHOTOS, false)
    // §2.12: syncs on opening the app.
    if (savedInstanceState == null) CloudSync.request(this)
    trails = prefs.getBoolean(PREF_TRAILS, true)
    teamSaver = prefs.getBoolean(PREF_TEAM_SAVER, false)
    teamName = prefs.getString(PREF_TEAM_NAME, "").orEmpty()
    readSeq = prefs.getLong(PREF_TEAM_READ, 0L)
    // Back in the team after the app (and its service) was killed: catch up, 未读 included.
    prefs.getLong(PREF_TEAM, 0L).takeIf { it != 0L && RecordingService.team.value == null }?.let { id ->
      if (account == null) prefs.edit().remove(PREF_TEAM).apply() else resumeTeam(id)
    }
    // §2.9: computed on opening the app, for the 参考轨迹.
    if (savedInstanceState == null) referenceTrack?.let { loadWeather(it) }
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
    openedChat(intent)

    setContent {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      // ponytail: reads each import's header on the main thread; move off-thread if people import dozens.
      val terrain = remember(filesVersion) { style() }
      var menu by remember { mutableStateOf(false) }
      var layers by remember { mutableStateOf(false) }
      var datumVersion by remember { mutableIntStateOf(0) }
      // Whatever a pull brought in shows at once.
      val synced by CloudSync.changes.collectAsState()
      LaunchedEffect(synced) { if (synced > 0) { datumVersion++; tracksVersion++; waypointsVersion++ } }
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
      val team by RecordingService.team.collectAsState()
      // Ticks "x 分钟前", 半透明 and 失联 along.
      var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
      LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
      // Teammates to show: sharing, with a position. 停止共享 hides someone from the map altogether.
      val mates = team?.let { t -> t.members.filter { it.id != t.me && it.sharing && it.trail.isNotEmpty() } }.orEmpty()
      val referenceSegments = referenceTrack?.let { id -> remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.segments(id) } } }
      // Long-pressed point (card open), and 测距 from/to.
      var pressed by remember { mutableStateOf<Position?>(null) }
      var measureFrom by remember { mutableStateOf<Position?>(null) }
      var measureTo by remember { mutableStateOf<Position?>(null) }
      // Where a 对话 location or 求助 points, while the drawer is open.
      var chatPin by remember { mutableStateOf<Position?>(null) }
      val waypoints = remember(waypointsVersion) { TrackDb(this@MainActivity).use { it.waypoints() } }
      val waypointDot = remember { DotPainter(Color(0xFFF2A900)) }
      // Whether the camera is outside China (§2.2: overseas 标准 and 地形 are OpenFreeMap); set from the camera below.
      var overseas by remember { mutableStateOf(false) }
      val style = remember(terrain, basemap, overseas, openFreeMap, contours, hillshade, nearby, online) {
        basemapStyle(terrain, basemap, overseas, openFreeMap, BuildConfig.API_URL, contours, hillshade, nearby, online)
      }
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
        if (trails) for (m in mates) key(m.id) {
          val source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(m.trail) { displayLine(listOf(m.trail.map { TrackPoint(it.timeS * 1000, it.lat, it.lon, null) })) }))
          LineLayer(id = "trail-${m.id}", source = source, color = const(Color(memberColor(m.id))), width = const(2.dp))
        }
        val from = measureFrom
        val to = measureTo
        if (from != null && to != null) {
          val line = "{\"type\":\"LineString\",\"coordinates\":[[${from.longitude},${from.latitude}],[${to.longitude},${to.latitude}]]}"
          LineLayer(id = "measure", source = rememberGeoJsonSource(GeoJsonData.JsonString(line)), color = const(Color.Black), width = const(2.dp))
        }
        // 标注 as a symbol layer: MapLibre's collision placement thins them out as you zoom out, and they
        // don't swallow map gestures the way per-标注 composables did.
        SymbolLayer(
          id = "waypoints",
          source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(waypoints) { waypointFeatures(waypoints) })),
          iconImage = image(waypointDot, DpSize(16.dp, 16.dp)),
          textField = format(span(feature["name"].asString())),
          textFont = const(listOf("Noto Sans Regular")),
          textSize = const(12.sp),
          textAnchor = const(SymbolAnchor.Top),
          textOffset = textOffset(0.dp, 10.dp),
          textHaloColor = const(Color.White),
          textHaloWidth = const(1.dp),
          textOptional = const(true),
          onClick = { features ->
            val id = features.firstOrNull()?.properties?.get("id")?.jsonPrimitive?.long
            waypoints.firstOrNull { it.id == id }?.let(::openWaypoint)
            ClickResult.Consume
          },
        )
      }
      LaunchedEffect(state) {
        // ponytail: China's bbox, as for 坐标纠偏 and the server's offline area; a China outline if border areas look wrong.
        snapshotFlow { state.cameraPosition.target.let { outOfChina(it.latitude, it.longitude) } }.collect { overseas = it }
      }
      LaunchedEffect(overseas) {
        if (overseas && openFreeMap == null) thread {
          openFreeMapStyle(File(filesDir, "openfreemap-liberty.json"))?.let { runOnUiThread { openFreeMap = it } }
        }
      }
      var offlinePage by remember { mutableStateOf(false) }
      var aboutPage by remember { mutableStateOf(false) }
      var searching by remember { mutableStateOf(false) }
      LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        searchNote = null
        parseCoordinate(q)?.let { (lat, lon) ->
          searchResults = listOf(Place(coordinateText(lat, lon), "coordinate", lat, lon, "坐标（WGS-84）"))
          return@LaunchedEffect
        }
        if (q.isEmpty()) {
          searchResults = emptyList()
          return@LaunchedEffect
        }
        delay(300) // typing: only the last query runs
        val center = state.cameraPosition.target
        // 山名别名表 and the offline 地名索引 (pushed for development, and each package's) first, then online.
        val files = listOf(File(dir, "places.sqlite")) + packages().map { File(it.dir, "places.sqlite") }
        val local = aliases.filter { it.name.contains(q, ignoreCase = true) } + withContext(Dispatchers.IO) { searchPlaces(files, q) }
        searchResults = rankPlaces(local, q, center.latitude, center.longitude)
        withContext(Dispatchers.IO) { runCatching { api.search(q, center.latitude, center.longitude) } }
          .onSuccess { searchResults = rankPlaces(local + it, q, center.latitude, center.longitude); searchNote = if (searchResults.isEmpty()) "没有找到" else "在线结果来自 OpenStreetMap（Photon）与天地图" }
          .onFailure { e ->
            val why = when ((e as? OfflineError)?.code) { "offline" -> "网络不可用"; "client_outdated" -> "请更新 App 后使用在线搜索"; else -> "在线搜索暂不可用" }
            searchNote = if (local.isEmpty()) "没有找到（$why）" else "仅离线结果（$why）"
          }
      }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && it.extension.lowercase() in importableExtensions }
      }
      val packages = remember(filesVersion) { packages() }
      LaunchedEffect(offlinePage) {
        // No tag when offline: 可更新 is a hint, never an error (§2.3).
        if (offlinePage) thread { runCatching { api.dataVersion() }.onSuccess { runOnUiThread { dataVersion = it } } }
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
      LaunchedEffect(detailTrack) { detailTrack?.let { loadWeather(it) } }
      val recording by RecordingService.activeTrack.collectAsState()
      val paused by RecordingService.paused.collectAsState()
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
                  nearbyTracks = emptyList()
                  if (measureFrom == null) {
                    if (nearby) e.position?.let { findNearby(it, state.cameraPosition.zoom) }
                    return@onEvent ClickResult.Pass
                  }
                  measureTo = e.position ?: return@onEvent ClickResult.Pass
                  ClickResult.Consume
                }
              }
              longClick {
                onEvent { e ->
                  nearbyTracks = emptyList()
                  pressed = e.position ?: return@onEvent ClickResult.Pass
                  ClickResult.Consume
                }
              }
            }
          },
        ) {
          for (at in listOfNotNull(pressed, measureFrom, measureTo, chatPin)) Box(Modifier.placedAt(at).size(10.dp).background(Color.Black, CircleShape))
          for (m in mates) key(m.id) {
            val last = m.trail.last()
            val at = Position(longitude = last.lon, latitude = last.lat)
            TeammateDot(m, presence(last.timeS, now), Modifier.placedAt(at))
            // Padding above centres the label below the dot.
            BasicText(
              if (presence(last.timeS, now) == Presence.Lost) "失联 · 最后位置 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(last.timeS * 1000)) else agoText(last.timeS, now),
              Modifier.placedAt(at).padding(top = 44.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
              style = TextStyle(fontSize = 11.sp),
            )
          }
        }
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.End) {
          MapButton("菜单") { menu = !menu }
          MapButton("图层") { layers = !layers }
          // §2.1: in the menu until joined, then always on the map with how many are online.
          team?.let { t ->
            val online = t.members.count { m -> m.sharing && m.trail.lastOrNull()?.let { presence(it.timeS, now) != Presence.Lost } == true }
            MapButton(if (t.ended) "队伍 · 行程已结束" else "队伍 · $online 人在线") { teamPage = true }
            val n = unread(t, readSeq).size
            MapButton(if (n > 0) "对话 · $n 条未读" else "对话") { chat = true }
          }
          if (menu) {
            if (team == null) MapButton("队伍") { menu = false; openTeam() }
            MapButton("搜索") { menu = false; searching = true }
            MapButton("我的轨迹") { menu = false; trackPage = true }
            MapButton("离线地图") { menu = false; offlinePage = true }
            MapButton("关于") { menu = false; aboutPage = true }
            // §2.12: login is asked for by 队伍 and 开启同步 only.
            MapButton(if (account == null) "开启同步" else "账号与同步") { menu = false; syncAfterLogin = account == null; accountPage = true }
            MapButton("下载当前视野") {
              menu = false
              val (sw, ne) = state.getVisibleBounds() ?: return@MapButton
              downloadPackage("视野 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date()), bboxRequest(sw.longitude, sw.latitude, ne.longitude, ne.latitude))
            }
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
        // The track open in 轨迹详情, else the 参考轨迹.
        val bannerTrack = detailTrack ?: referenceTrack
        val bannerAlerts = bannerTrack?.let { weather[it] }?.let { w -> remember(w) { w.alerts() } }.orEmpty()
        if (bannerTrack != null && bannerAlerts.isNotEmpty() && !bannerClosed && measureFrom == null) {
          TripAlertBanner(
            name = remember(bannerTrack) { TrackDb(this@MainActivity).use { it.trackName(bannerTrack) } },
            alerts = bannerAlerts,
            onOpen = { detailTrack = bannerTrack },
            onClose = { bannerClosed = true },
            // Left of the map buttons.
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 12.dp, end = 96.dp),
          )
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
              // Not stopService: the service carries on for the team.
              if (recording != null) startService(Intent(this@MainActivity, RecordingService::class.java).setAction("stop")) else record(null)
            },
            contentAlignment = Alignment.Center,
          ) {
            BasicText(if (recording == null) "开始" else "停止", style = TextStyle(color = Color.White, fontSize = 18.sp))
          }
        }
        val chatTeam = team
        if (chat && chatTeam != null) {
          BackHandler { chat = false }
          val drawerDp = LocalConfiguration.current.screenHeightDp.dp / 2
          ChatDrawer(
            chatTeam, sosNote,
            loadImage = { id, thumb -> loadImage(chatTeam.id, id, thumb) },
            onSend = { sendMessage(messageJson("text", text = it)) },
            onLocation = {
              val fix = currentFix() ?: return@ChatDrawer toast("还没有定位，请稍候")
              sendMessage(messageJson("location", lat = fix.latitude, lon = fix.longitude))
            },
            onPhoto = { pickChatPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onSos = ::sendSos,
            onFocus = { lat, lon ->
              val at = Position(longitude = lon, latitude = lat)
              chatPin = at
              // Padded, so it lands in the map's half above the drawer.
              state.setCameraPosition(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 14.0), padding = DpPadding(0.dp, 0.dp, 0.dp, drawerDp)))
            },
            onClose = { chat = false },
          )
        }
        LaunchedEffect(chat) {
          ChatAlerts.open = chat
          if (!chat && chatPin != null) {
            chatPin = null
            state.setCameraPosition(state.cameraPosition.copy(padding = DpPadding(0.dp, 0.dp, 0.dp, 0.dp)))
          }
        }
        // Open: everything in it is read. After 结束行程 there's no socket, so the open drawer asks every 10 s.
        LaunchedEffect(chat, team?.messages?.lastOrNull()?.seq) {
          val last = team?.messages?.lastOrNull()?.seq ?: return@LaunchedEffect
          if (!chat) return@LaunchedEffect
          ChatAlerts.seen(this@MainActivity)
          if (last > readSeq) {
            readSeq = last
            prefs.edit().putLong(PREF_TEAM_READ, last).apply()
          }
        }
        LaunchedEffect(chat, team?.id, team?.ended, resumes) {
          val t = team ?: return@LaunchedEffect
          if (!t.ended) return@LaunchedEffect
          catchUp(t.id)
          while (chat) {
            delay(10_000)
            catchUp(t.id)
          }
        }
        if (layers) {
          BackHandler { layers = false }
          val camera = state.cameraPosition
          LayerSheet(
            basemap, overseas, contours, hillshade, tilted = camera.tilt != 0.0, nearby = nearby, trails = trails.takeIf { team != null },
            onBasemap = { basemap = it; prefs.edit().putString(PREF_BASEMAP, it.name).apply() },
            onContours = { contours = !contours; prefs.edit().putBoolean(PREF_CONTOURS, contours).apply() },
            onHillshade = { hillshade = !hillshade; prefs.edit().putBoolean(PREF_HILLSHADE, hillshade).apply() },
            onTrails = { trails = !trails; prefs.edit().putBoolean(PREF_TRAILS, trails).apply() },
            // §2.2 3D 地形 is 2.5D: tilt + hillshade.
            onTilt = { state.setCameraPosition(camera.copy(tilt = if (camera.tilt != 0.0) 0.0 else 60.0)) },
            onNearby = { nearby = !nearby; prefs.edit().putBoolean(PREF_NEARBY, nearby).apply(); if (!nearby) nearbyTracks = emptyList() },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
        if (nearbyTracks.isNotEmpty()) {
          BackHandler { nearbyTracks = emptyList() }
          NearbySheet(
            nearbyTracks, nearbyNote,
            onReference = { saveNearby(it)?.let(::setReference); nearbyTracks = emptyList() },
            onSave = { t -> saveNearby(t)?.let { toast("已保存到我的轨迹") } },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
        if (aboutPage) {
          BackHandler { aboutPage = false }
          AboutScreen(onOsmExtract = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.API_URL + "/v1/data/osm-extract"))) })
        }
        if (offlinePage) {
          BackHandler { offlinePage = false }
          OfflineMapScreen(
            packages = packages,
            dataVersion = dataVersion,
            downloading = downloading,
            onUpdate = { downloadPackage(it.name, it.request, old = it) },
            onDeletePackage = { it.dir.deleteRecursively(); filesVersion++ },
            files = files,
            importing = importing,
            // MBTiles/PMTiles have no registered MIME type; filter by extension after picking.
            onImport = { pickFile.launch(arrayOf("*/*")) },
            onDelete = { it.delete(); filesVersion++ },
          )
        }
        if (accountPage) {
          BackHandler { accountPage = false }
          AccountScreen(
            account,
            sendCode = api::sendCode,
            login = api::login,
            onLogin = {
              accounts.set(it)
              account = it
              accountPage = false
              toast("已登录")
              if (teamAfterLogin) teamPage = true
              teamAfterLogin = false
              if (syncAfterLogin) setSync(true)
              syncAfterLogin = false
            },
            onLogout = { logout() },
            sync = syncOn,
            onSync = ::setSync,
            mobilePhotos = mobilePhotos,
            onMobilePhotos = { mobilePhotos = it; prefs.edit().putBoolean(PREF_SYNC_MOBILE_PHOTOS, it).apply() },
            deleteAccount = { account?.let(api::deleteAccount) },
            onDeleted = {
              CloudSync.forget(this@MainActivity)
              syncOn = false
              quitTeam()
              accounts.set(null)
              account = null
              accountPage = false
              toast("账号已注销，本机数据仍保留")
            },
          )
        }
        if (teamPage) {
          BackHandler { teamPage = false }
          // Only reached logged in (openTeam); a logout meanwhile reads as an expired login.
          fun acct() = account ?: throw OfflineError("unauthorized")
          TeamScreen(
            team, now,
            unread = team?.let { unread(it, readSeq).size } ?: 0,
            onChat = { teamPage = false; chat = true },
            here = RecordingService.lastFix?.let { TeamPosition(it.time / 1000, it.latitude, it.longitude, null) }
              ?: team?.let { t -> t.members.firstOrNull { it.id == t.me }?.trail?.lastOrNull() },
            name = teamName,
            saver = teamSaver,
            onName = { teamName = it; prefs.edit().putString(PREF_TEAM_NAME, it).apply() },
            create = { api.createTeam(acct(), teamName.trim()) },
            join = { code -> api.joinTeam(acct(), code, teamName.trim()) },
            onJoined = { t ->
              prefs.edit().putLong(PREF_TEAM, t.id).apply()
              RecordingService.showTeam(t)
              shareWithTeam(t.id)
            },
            onSharing = { on -> startService(Intent(this@MainActivity, RecordingService::class.java).setAction(RecordingService.ACTION_SHARE).putExtra(RecordingService.EXTRA_SHARING, on)) },
            onSaver = { teamSaver = !teamSaver; prefs.edit().putBoolean(PREF_TEAM_SAVER, teamSaver).apply() },
            leave = { team?.let { api.leaveTeam(acct(), it.id) } },
            end = { team?.let { api.endTeam(acct(), it.id) } },
            onLeft = { quitTeam(); teamPage = false },
            onFocus = { p ->
              teamPage = false
              state.setCameraPosition(state.cameraPosition.copy(target = Position(longitude = p.lon, latitude = p.lat), zoom = maxOf(state.cameraPosition.zoom, 14.0)))
            },
          )
        }
        if (searching) {
          BackHandler { searching = false }
          SearchScreen(searchQuery, searchResults, searchNote, onQuery = { searchQuery = it }, onPick = { p ->
            searching = false
            val at = Position(longitude = p.lon, latitude = p.lat)
            state.setCameraPosition(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 13.0)))
            pressed = at
          })
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
            public = remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.isPublic(id) } },
            weather = weather[id],
            weatherLoading = id in weatherLoading,
            pace = pace,
            onReference = { setReference(if (id == referenceTrack) null else id) },
            onPublic = { togglePublic(id); datumVersion++ },
            onDatum = { d -> TrackDb(this@MainActivity).use { it.setDatum(id, d) }; datumVersion++; waypointsVersion++; loadWeather(id, force = true) },
            onRename = { n -> TrackDb(this@MainActivity).use { it.setName(id, n) }; datumVersion++; tracksVersion++ },
            onPace = { p ->
              pace = p
              prefs.edit().putString(PREF_PACE, p.name).apply()
              loadWeather(id, force = true)
            },
            onDepart = { pickDeparture(id) },
            onExport = { kml -> exportTrack(id, kml) },
            // §2.9: the weather is cached with the offline package.
            onDownload = { downloadPackage("沿轨迹 $name", trackRequest(segments)); loadWeather(id, force = true) },
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
    val packages = packages().map { it.dir.absolutePath }
    val base = withPackages(withRemote(assets.open("style.json").bufferedReader().readText()), packages).replace("__DIR__", dir.absolutePath)
      .replace("__API__", BuildConfig.API_URL)
    return withImports(base, importsDir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull(::importOf))
  }

  private fun packages(): List<OfflinePackage> =
    packagesDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }.mapNotNull(::readPackage)

  /** Downloads an offline package (§2.3) into packages/; an update replaces [old] once the new one is complete. */
  private fun downloadPackage(name: String, request: String, old: OfflinePackage? = null) {
    if (downloading) return toast("正在下载另一个离线包，请稍候")
    downloading = true
    toast("正在下载离线包…")
    thread {
      // Downloaded into a hidden staging dir, then moved under a new name: a half-finished package never
      // reaches the style, and an updated one gets a new path so MapLibre reopens its files.
      val staging = File(packagesDir, ".staging").apply { deleteRecursively() }
      val result = runCatching {
        val pkg = api.download(name, request, staging)
        val dest = File(packagesDir, System.currentTimeMillis().toString())
        check(staging.renameTo(dest))
        old?.dir?.deleteRecursively()
        pkg.copy(dir = dest)
      }
      if (result.isFailure) staging.deleteRecursively()
      runOnUiThread {
        downloading = false
        result.onSuccess {
          dataVersion = it.version
          filesVersion++
          toast("已下载 ${it.name}（${Formatter.formatShortFileSize(this, it.bytes)}）")
        }.onFailure { toast(offlineMessage((it as? OfflineError)?.code)) }
      }
    }
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
    openedChat(intent)
  }

  /** A 对话 or 求助 notification was tapped. */
  private fun openedChat(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_CHAT, false) != true) return
    intent.removeExtra(EXTRA_CHAT)
    if (RecordingService.team.value != null) chat = true
    ChatAlerts.seen(this)
  }

  override fun onResume() {
    super.onResume()
    ChatAlerts.open = chat
    resumes++
  }

  override fun onDestroy() {
    // A 求助 still retrying dies with this activity (see sendSos).
    sosRetry?.let(handler::removeCallbacks)
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
    super.onDestroy()
  }

  override fun onPause() {
    super.onPause()
    ChatAlerts.open = false
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

  /**
   * 经过这里的轨迹 (§2.8) for a tap at [at]: the 徒步线路 and 平台轨迹 of the pushed data and every package, and the
   * 公开轨迹 online, else from the packages' snapshots. Shown once found; nothing near, nothing shown.
   */
  private fun findNearby(at: Position, zoom: Double) {
    val seq = ++nearbySeq
    val radius = tapRadiusM(at.latitude, zoom)
    val dirs = listOf(dir) + packages().map { it.dir }
    // ponytail: re-reads the files on every tap; small per package, but the pushed full-China routes.geojson
    // takes seconds. Keep them parsed (by filesVersion) if that bites outside development.
    fun read(name: String) = dirs.mapNotNull { File(it, name).takeIf(File::exists)?.readText() }
    thread {
      val local = read("routes.geojson").map { NearbyKind.Route to it } + read("platform.geojson").map { NearbyKind.Platform to it }
      val online = runCatching { api.publicTracks(at.latitude, at.longitude, radius) }.getOrNull()
      val public = online?.let(::listOf) ?: read("public-tracks.geojson")
      val found = nearbyTracks(local + public.map { NearbyKind.Public to it }, at.latitude, at.longitude, radius)
      runOnUiThread {
        if (seq != nearbySeq || !nearby) return@runOnUiThread
        nearbyTracks = found
        nearbyNote = if (online == null && found.any { it.kind == NearbyKind.Public }) "离线中：公开轨迹来自离线包快照" else null
      }
    }
  }

  /** Saves a 周边路网 line to 我的轨迹 as a 计划轨迹 (it has no times); its id, or null if that failed. */
  private fun saveNearby(t: NearbyTrack): Long? = runCatching {
    TrackDb(this).use { it.importTrack(ParsedTrack(t.name, true, t.segments), t.name.ifEmpty { t.kind.label }, emptyList(), System.currentTimeMillis()) }
  }.onSuccess { tracksVersion++ }.onFailure { toast("保存失败") }.getOrNull()

  private fun setReference(id: Long?) {
    getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_REFERENCE, id ?: 0L).apply()
    referenceTrack = id
    // The service only notices the change on its next fix; don't leave an alert for the old one up until then.
    getSystemService(android.app.NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    // ponytail: alerts ride on the recording service's GPS; a separate follow-only service if people follow without recording.
    if (id != null && RecordingService.activeTrack.value == null) toast("记录轨迹时，偏离超过 ${OFF_TRACK_M.toInt()} m 会提醒")
    // §2.9: computed (and cached for offline) on becoming the 参考轨迹.
    id?.let { loadWeather(it, force = true) }
  }

  /**
   * Fetches [id]'s 沿途天气 from [departMs] (null: as before, or now), showing the cached one meanwhile.
   * Unless [force]d, a forecast fetched within the hour is kept (the server's cache is hourly too).
   */
  private fun loadWeather(id: Long, departMs: Long? = null, force: Boolean = false) {
    val have = weather[id]
    if (!force && (id in weatherLoading || (have != null && !have.offline && System.currentTimeMillis() - have.fetchedMs < 3_600_000))) return
    weatherLoading += id
    val seq = (weatherSeq[id] ?: 0) + 1
    weatherSeq[id] = seq
    val pace = pace
    thread {
      if (have == null) cachedTrackWeather(this, id)?.let { cached -> runOnUiThread { if (id !in weather) weather += id to cached } }
      val w = runCatching { fetchTrackWeather(this, api, id, departMs, pace) }.getOrNull()
      runOnUiThread {
        if (weatherSeq[id] != seq) return@runOnUiThread
        weatherLoading -= id
        if (w != null) {
          weather += id to w
          if (!w.offline) bannerClosed = false
        }
      }
    }
  }

  /** 出发时间 (§2.9, default now): a date, then a time. */
  private fun pickDeparture(id: Long) {
    val c = Calendar.getInstance().apply { timeInMillis = weather[id]?.departMs ?: System.currentTimeMillis() }
    DatePickerDialog(this, { _, y, m, d ->
      TimePickerDialog(this, { _, h, min ->
        c.set(y, m, d, h, min, 0)
        loadWeather(id, c.timeInMillis, force = true)
      }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
  }

  /** 公开轨迹 (§2.8) or 撤回: the server gets it with 同步, so that comes first. */
  private fun togglePublic(id: Long) {
    if (account == null || !syncOn) {
      syncAfterLogin = account == null
      accountPage = true
      return toast("公开轨迹需要登录并开启同步")
    }
    val public = TrackDb(this).use { db -> (!db.isPublic(id)).also { db.setPublic(id, it) } }
    toast(if (public) "已公开到周边路网" else "已撤回公开")
  }

  /** §2.11: 队伍 needs an account; its page asks for a login first. */
  private fun openTeam() {
    if (account != null) return run { teamPage = true }
    teamAfterLogin = true
    accountPage = true
    toast("使用队伍需要先用手机号登录")
  }

  /**
   * Back in team [id] on launch: catches up (announcing 未读) and, unless the trip has ended, shares again.
   * Offline, the service catches up once it connects.
   */
  private fun resumeTeam(id: Long) {
    val acct = account ?: return
    thread {
      val t = runCatching { api.team(acct, id, 0L) }
      runOnUiThread {
        if (prefs.getLong(PREF_TEAM, 0L) != id) return@runOnUiThread
        val code = (t.exceptionOrNull() as? OfflineError)?.code
        if (code == "team_not_found" || code == "unauthorized") return@runOnUiThread quitTeam()
        t.getOrNull()?.let { RecordingService.showTeam(it); ChatAlerts.announce(this, it) }
        if (t.getOrNull()?.ended != true && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startTeam(id)
      }
    }
  }

  /** What team [id] has stored since we last heard, without a socket (the trip has ended). */
  private suspend fun catchUp(id: Long) {
    val acct = account ?: return
    val after = RecordingService.team.value?.takeIf { it.id == id }?.cursor ?: return
    val t = withContext(Dispatchers.IO) { runCatching { api.team(acct, id, after) }.getOrNull() } ?: return
    if (RecordingService.team.value?.id == id) {
      RecordingService.showTeam(t)
      ChatAlerts.announce(this, RecordingService.team.value ?: return)
    }
  }

  /**
   * Sends a [messageJson] to the 队伍对话 off the main thread and shows it; [onFail] gets what the server
   * (or no signal) said, a toast by default.
   */
  private fun sendMessage(json: String, onSent: () -> Unit = {}, onFail: (String?) -> Unit = { toast(teamMessage(it)) }) {
    val t = RecordingService.team.value ?: return
    val acct = account ?: return onFail("unauthorized")
    thread {
      val sent = runCatching { api.postMessage(acct, t.id, json) }
      runOnUiThread {
        sent.onSuccess { m ->
          RecordingService.team.value?.takeIf { it.id == t.id }?.let { RecordingService.showTeam(it.copy(messages = listOf(m))) }
          onSent()
        }.onFailure { onFail((it as? OfflineError)?.code) }
      }
    }
  }

  /** 一键求助 (§2.11) with where we are and the battery; with no signal it keeps trying every 15 s. */
  // ponytail: retried by the activity; a 求助 still unsent when the app is closed or rotated is lost. Move to the service if that bites.
  private fun sendSos() {
    val fix = currentFix()
    val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    val json = messageJson("sos", lat = fix?.latitude, lon = fix?.longitude, battery = battery)
    fun attempt() {
      sosNote = "正在发出求助…"
      sendMessage(json, onSent = { sosNote = "求助已发给队友（不会联系救援）" }, onFail = { code ->
        if (code != "offline") return@sendMessage run { sosNote = "求助发送失败：" + teamMessage(code) }
        sosNote = "没有信号，求助会每 15 秒重试一次"
        sosRetry = Runnable { attempt() }.also { handler.postDelayed(it, 15_000L) }
      })
    }
    sosRetry?.let(handler::removeCallbacks)
    attempt()
  }

  /** Shrinks the picked photo (§3.2), uploads it and sends it as an image message. */
  private fun sendPhoto(uri: Uri) {
    val t = RecordingService.team.value ?: return
    val acct = account ?: return
    toast("正在发送图片…")
    thread {
      val image = runCatching { api.uploadImage(acct, t.id, shrinkPhoto(this, uri)) }
      runOnUiThread {
        image.onSuccess { sendMessage(messageJson("image", image = it)) }
          .onFailure { toast(if (it is OfflineError) teamMessage(it.code) else "无法读取图片") }
      }
    }
  }

  /** A 对话 photo (or its thumbnail), fetched once. */
  private suspend fun loadImage(team: Long, id: String, thumb: Boolean): ImageBitmap? {
    val key = "$id/$thumb"
    images.get(key)?.let { return it }
    val acct = account ?: return null
    return withContext(Dispatchers.IO) {
      runCatching { api.image(acct, team, id, thumb).let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }.getOrNull()
    }?.also { images.put(key, it) }
  }

  /**
   * Where the phone is: the service's latest fix, else the last one the system knows; none older than 30 min,
   * since a message shows it as where we are now.
   */
  private fun currentFix(): Location? = (RecordingService.lastFix ?: try {
    getSystemService(LocationManager::class.java).let { it.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: it.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
  } catch (e: SecurityException) {
    null
  })?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }

  /** Starts sharing with team [id] (§2.11), asking for location first if needed. */
  private fun shareWithTeam(id: Long) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return startTeam(id)
    teamAfterGrant = id
    askPermissions.launch(
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
  }

  private fun startTeam(id: Long) {
    startForegroundService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_TEAM).putExtra(RecordingService.EXTRA_TEAM, id))
  }

  /** Forgets the team here (退出队伍 done, or logged out). */
  private fun quitTeam() {
    // Only a running service has a team to forget; starting one just for that would need location.
    val running = RecordingService.activeTrack.value != null || RecordingService.team.value?.ended == false
    prefs.edit().remove(PREF_TEAM).apply()
    RecordingService.showTeam(null)
    if (running && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_TEAM_QUIT))
    }
  }

  /** §2.12 退出登录: local data stays; the server forgets the token when it can be reached. */
  private fun logout() {
    val old = account ?: return
    quitTeam()
    setSync(false)
    accounts.set(null)
    account = null
    thread { runCatching { api.logout(old) } }
  }

  /** 开启同步 / 关闭同步 (§2.12); turning it on uploads what's only on this phone. */
  private fun setSync(on: Boolean) {
    syncOn = on
    val acct = account
    if (on && acct != null) CloudSync.enable(this, acct) else prefs.edit().putBoolean(PREF_SYNC, false).apply()
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

/** 标注 as a GeoJSON FeatureCollection with `id` and `name` properties. */
private fun waypointFeatures(waypoints: List<Waypoint>): String = buildJsonObject {
  put("type", "FeatureCollection")
  put("features", buildJsonArray {
    for (w in waypoints) add(buildJsonObject {
      put("type", "Feature")
      put("geometry", buildJsonObject { put("type", "Point"); put("coordinates", buildJsonArray { add(w.lon); add(w.lat) }) })
      put("properties", buildJsonObject { put("id", w.id); put("name", w.name) })
    })
  })
}.toString()

/** A teammate on the map: a dot in their colour with their initial; faded after 5 min, grey in a dashed ring once 失联. */
@Composable
private fun TeammateDot(m: TeamMember, presence: Presence, modifier: Modifier) {
  val color = if (presence == Presence.Lost) Color.Gray else Color(memberColor(m.id))
  Box(
    modifier.size(28.dp).alpha(if (presence == Presence.Stale) 0.5f else 1f).drawBehind {
      if (presence == Presence.Lost) drawCircle(Color.Gray, radius = size.minDimension / 2 + 5.dp.toPx(),
        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }.background(color, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(m.name.take(1), style = TextStyle(color = Color.White, fontSize = 14.sp))
  }
}

/** A filled circle, for symbol-layer icons. */
private class DotPainter(private val color: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() = drawCircle(color)
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
