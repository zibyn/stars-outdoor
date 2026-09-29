package dev.stars.outdoor

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationPermission
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
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.inMeters

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
      runOnUiThread {
        // The 状态条 promises a failed sync is tried again once online.
        if (up && !online && CloudSync.failed.value) CloudSync.request(this@MainActivity)
        online = up
      }
    }
    override fun onLost(n: Network) = runOnUiThread { online = false }
  }
  /** System location on (状态条, ux-v2 §3.6). */
  private var locationOn by mutableStateOf(true)
  private val locationSwitch = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = readLocationOn()
  }
  private fun readLocationOn() { locationOn = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java)) }
  private var nearbyTracks by mutableStateOf(listOf<NearbyTrack>())
  private var nearbyNote by mutableStateOf<String?>(null)
  /** 经过这里的轨迹 saved to 我的轨迹 from the list showing, with their ids. */
  private var nearbySaved by mutableStateOf(mapOf<NearbyTrack, Long>())
  /** Taps looked up; a newer tap's answer replaces an older one still in flight. */
  private var nearbySeq = 0
  /** OpenFreeMap's style JSON for overseas 地形 / 标准, once fetched. */
  private var openFreeMap by mutableStateOf<String?>(null)
  /** The package downloading (its request) and how far it got, for 轨迹详情's 沿线离线地图 row. */
  private var downloadRequest by mutableStateOf<String?>(null)
  private val downloading get() = downloadRequest != null
  private var downloadPercent by mutableIntStateOf(0)
  /** The server's offline data version, once asked; packages from another version show 可更新. */
  private var dataVersion by mutableStateOf<String?>(null)
  private var filesVersion by mutableIntStateOf(0)
  private var importing by mutableStateOf(false)
  /** Unfinished track left by a killed recording, awaiting "继续记录 / 结束并保存". */
  private var unfinishedTrack by mutableStateOf<Long?>(null)
  private var batteryGuide by mutableStateOf(false)
  /**
   * 出发前 battery row (ux-v2 §4.2): due once 设为参考 or 沿线下载 was tapped, gone once battery optimisation is off or
   * 知道了 was tapped in [BatteryGuide].
   */
  private var batteryDue by mutableStateOf(false)
  private var batterySet by mutableStateOf(false)
  private var detailTrack by mutableStateOf<Long?>(null)
  /** 导出 in 轨迹详情 was tapped: the 小抽屉 picking GPX or KML. */
  private var exportSheet by mutableStateOf(false)
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
  /** 叠加 (ux-v2 §9.2): track id → [overlayColors] index. */
  private var overlays by mutableStateOf(mapOf<Long, Int>())
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
  /** 惯用手 (ux-v2 §2.2): left mirrors 定位 and 标注 to the left. */
  private var leftHanded by mutableStateOf(false)
  private val aliases by lazy { aliasPlaces(assets.open("peak-aliases.tsv").bufferedReader().readText()) }
  /** 出行提醒 banner closed; it comes back with the next forecast. */
  private var bannerClosed by mutableStateOf(false)
  /** 队伍 (§2.11): the 队伍抽屉 (ux-v2 §4.4) and its 管理 page; 尾迹 shown; 省电模式. */
  private var teamDrawer by mutableStateOf(false)
  private var teamManage by mutableStateOf(false)
  /** A join (its code; "" for 创建队伍) waiting for the login it asked for, then carried out (ux-v2 §8 路径 5). */
  private var teamAfterLogin: String? = null
  /** Creating or joining in flight, and what went wrong last. */
  private var teamBusy by mutableStateOf(false)
  private var teamNote by mutableStateOf<String?>(null)
  private var trails by mutableStateOf(true)
  private var teamSaver by mutableStateOf(false)
  private var teamName by mutableStateOf("")
  /** Team to share with once location is granted (0 = none; else it's recording that asked). */
  private var teamAfterGrant = 0L
  /** 队伍对话 (§2.11): the drawer open, the last message read, how the last 求助 is getting on. */
  private var chat by mutableStateOf(false)
  private var readSeq by mutableLongStateOf(0L)
  private var sosNote by mutableStateOf<String?>(null)
  /** The last 求助 failed for a reason other than no signal: offer 重试 (ux-v2 §6.5). */
  private var sosFailed by mutableStateOf(false)
  private var sosRetry: Runnable? = null
  /** Counts onResume, so an ended team's 对话 is caught up each time the app comes back (it has no socket). */
  private var resumes by mutableIntStateOf(0)
  private val handler = Handler(Looper.getMainLooper())
  // ponytail: photos in memory only, the last 40; a disk cache if people scroll long chats offline.
  private val images = LruCache<String, ImageBitmap>(40)
  private val pickChatPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }
  private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachPhoto) }
  /** 一键标注 (§9.1): when it started waiting for a good enough fix (ms); null when not waiting. */
  private var waypointWait by mutableStateOf<Long?>(null)
  /** The 提示条 showing (§5), or none. */
  private var hint by mutableStateOf<Hint?>(null)
  // 标注 asks for location only when tapped.
  private val askMarkPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) waypointWait = System.currentTimeMillis()
    else hint = Hint("需要定位才能标注当前位置，也可以长按地图选点")
  }
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
      // The 地图缓存 limit must be set before the cache is first used (#59); 设置 changes it on the running one.
      DefaultMapRuntime.configure(MapRuntimeOptions(maximumCacheSizeBytes = mapCacheLimit(this), requestInterceptor = MapRequestInterceptor(headers = { request ->
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
    readLocationOn()
    ContextCompat.registerReceiver(this, locationSwitch, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    // Debug builds may bundle sample PMTiles (app/src/debug/assets/data/, gitignored) for phones adb can't reach.
    for (f in assets.list("data").orEmpty()) File(dir, f).takeIf { !it.exists() }?.let { out ->
      assets.open("data/$f").use { input -> File(dir, "$f.tmp").outputStream().use { input.copyTo(it) } }
      File(dir, "$f.tmp").renameTo(out)
    }
    if (RecordingService.activeTrack.value == null) unfinishedTrack = TrackDb(this).use { it.openTrack() }
    referenceTrack = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L).takeIf { it != 0L }
    batteryDue = prefs.getBoolean(PREF_BATTERY_DUE, false)
    overlays = readOverlays(prefs.getString(PREF_OVERLAYS, null), TrackDb(this).use { db -> db.tracks().map { it.id }.toSet() })
    pace = pace(prefs)
    leftHanded = prefs.getBoolean(PREF_LEFT_HANDED, false)
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
      var layers by remember { mutableStateOf(false) }
      // 活动状态's 分享位置 and 更多 小抽屉 (§3.3).
      var shareSheet by remember { mutableStateOf(false) }
      var moreSheet by remember { mutableStateOf(false) }
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
      // Teammates with a position; one who stopped sharing stays as a hollow dot where they were (ux-v2 §4.5).
      val mates = team?.let { t -> t.members.filter { it.id != t.me && it.trail.isNotEmpty() } }.orEmpty()
      // Where this phone is, for teammates' distance and direction.
      val here = RecordingService.lastFix?.let { TeamPosition(it.time / 1000, it.latitude, it.longitude, null) }
        ?: team?.let { t -> t.members.firstOrNull { it.id == t.me }?.trail?.lastOrNull() }
      // 队友小抽屉 (ux-v2 §4.5): whose.
      var mateSheet by remember { mutableStateOf<Long?>(null) }
      val referenceSegments = referenceTrack?.let { id -> remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.segments(id) } } }
      // 叠加 lines by track id, loaded off the main thread as they're overlaid; 坐标纠偏 or a sync reloads them.
      // Each kept with the datumVersion it was read at: the old line stays up while it reloads.
      val overlayLines = remember { mutableStateMapOf<Long, Pair<Int, String>>() }
      LaunchedEffect(overlays.keys, datumVersion) {
        for (id in overlays.keys) if (overlayLines[id]?.first != datumVersion) {
          overlayLines[id] = datumVersion to withContext(Dispatchers.IO) { displayLine(TrackDb(this@MainActivity).use { it.segments(id) }) }
        }
      }
      val recordingLine by RecordingService.track.collectAsState()
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
      val scope = rememberCoroutineScope()
      var follow by remember { mutableStateOf(Follow.Off) }
      // The compass was tapped while following: level the map on the way back onto me.
      var level by remember { mutableStateOf(false) }
      val me = rememberMyLocation(follow == Follow.Heading)
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      ) {
        // Style content (layers), unlike MaplibreMap's trailing lambda, which only holds overlays.
        // ux-v2 §3.8, declared bottom to top (周边路网 sits in the base style, under all of these).
        if (trails) for (m in mates.filter { it.sharing }) key(m.id) {
          val source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(m.trail) { displayLine(listOf(m.trail.map { TrackPoint(it.timeS * 1000, it.lat, it.lon, null) })) }))
          LineLayer(id = "trail-${m.id}", source = source, color = const(Color(memberColor(m.id))), width = const(2.dp))
        }
        // The 参考轨迹 is drawn once, as itself; the one open in 轨迹详情 comes bold on top of the rest.
        for ((id, color) in overlays) if (id != referenceTrack && id != detailTrack) key(id) {
          overlayLines[id]?.let { CasedLine("overlay-$id", it.second, Color(overlayColors[color]), 4.dp) }
        }
        detailSegments?.let { segments ->
          if (detailTrack != referenceTrack) CasedLine("detail-track", remember(segments) { displayLine(segments) },
            Color(overlays[detailTrack]?.let(overlayColors::get) ?: 0xFF424242), 6.dp)
        }
        referenceSegments?.let { segments -> CasedLine("reference-track", remember(segments) { displayLine(segments) }, Color(0xFF3B7DD8), 6.dp) }
        if (recordingLine.isNotEmpty()) CasedLine("recording-track", remember(recordingLine) { displayLine(recordingLine) }, Color(0xFFD32F2F), 6.dp)
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
        LocationIndicatorLayer(id = "me", locationState = me)
      }
      // Any other camera move takes the map off me.
      fun moveTo(to: CameraPosition, ms: Int) {
        follow = Follow.Off
        scope.launch { state.moveCamera(this@MainActivity, to, ms) }
      }
      // Tapped 定位 before a fix (or the permission): follow once one comes.
      var locatePending by remember { mutableStateOf(false) }
      LaunchedEffect(locatePending) {
        if (!locatePending) return@LaunchedEffect
        snapshotFlow { me.lastLocation }.filterNotNull().first()
        follow = Follow.On
        locatePending = false
      }
      FollowCamera(state, me, follow, level, onLevelled = { level = false }, onDragged = { follow = Follow.Off })
      // Location switched back on: the provider gave up while it was off.
      LaunchedEffect(Unit) { snapshotFlow { locationOn }.drop(1).collect { if (it) me.retry() } }
      LaunchedEffect(state) {
        // §3.5: open where I am, unless the camera has moved meanwhile (a drag, a search, 轨迹详情).
        state.awaitViewport() // a move before the map is attached is lost
        val start = state.cameraPosition.target
        val at = snapshotFlow { me.lastLocation?.position }.filterNotNull().first()
        if (state.cameraPosition.target == start) state.setCameraPosition(state.cameraPosition.copy(target = at))
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
      var settingsPage by remember { mutableStateOf(false) }
      var searching by remember { mutableStateOf(false) }
      LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        searchNote = null
        parseCoordinate(q)?.let { (lat, lon) ->
          searchResults = listOf(Place(coordinateText(lat, lon), "coordinate", lat, lon, "坐标"))
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
          .onSuccess { searchResults = rankPlaces(local + it, q, center.latitude, center.longitude); searchNote = if (searchResults.isEmpty()) "没有找到" else null }
          .onFailure { e ->
            val why = when ((e as? OfflineError)?.code) { "offline" -> "没有网络"; "client_outdated" -> "要先更新 App 才能在线搜索"; else -> "在线搜索没成功，再搜一次" }
            searchNote = if (local.isEmpty()) "没有找到（$why）" else "仅离线结果（$why）"
          }
      }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && it.extension.lowercase() in importableExtensions }
      }
      val packages = remember(filesVersion) { packages() }
      LaunchedEffect(offlinePage, detailTrack != null) {
        // No tag when offline: 可更新 is a hint, never an error (§2.3).
        if (offlinePage || detailTrack != null) thread { runCatching { api.dataVersion() }.onSuccess { runOnUiThread { dataVersion = it } } }
      }
      val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
      val pickTrackFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importTrackFile) }
      // The whole window, as the map and the drawer have it (screenHeightDp leaves out the system bars).
      val window = LocalWindowInfo.current.containerSize.let { with(LocalDensity.current) { it.width.toDp().value.toDouble() to it.height.toDp().value.toDouble() } }
      // ux-v2 §5: [points] in view over 400 ms, above a 半屏抽屉 and its 56 dp handle (+ a margin).
      suspend fun fitAboveDrawer(points: List<Position>) {
        if (points.isEmpty()) return
        follow = Follow.Off
        val (at, zoom) = fitCamera(
          points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude },
          window.first, window.second, 40.0, 80.0, 40.0, window.second / 2 + 64,
        )
        state.moveCamera(this@MainActivity, CameraPosition(target = at, zoom = zoom), Motion.FOCUS)
      }
      LaunchedEffect(detailTrack) { fitAboveDrawer(detailSegments?.flatten().orEmpty().map { Position(longitude = it.lon, latitude = it.lat) }) }
      LaunchedEffect(detailTrack) { detailTrack?.let { loadWeather(it) } }
      val recording by RecordingService.activeTrack.collectAsState()
      val paused by RecordingService.paused.collectAsState()
      // ponytail: recomputes the whole track's stats on each point (5 s at most); keep running stats in the service if long tracks lag.
      val recorded = remember(recording, recordingLine) { recording?.let { trackStats(recordingLine) to recordingLine.lastOrNull()?.lastOrNull()?.timeMs } }
      // 用时 runs on between points (and without a fix), refreshed with them and [now]; paused, it stands still.
      val live = recorded?.let { (stats, last) ->
        if (paused || last == null) stats else stats.copy(durationMs = stats.durationMs + (System.currentTimeMillis() - last).coerceAtLeast(0))
      }
      // §3.3: recording starts with the map following me.
      LaunchedEffect(recording) {
        if (recording != null && follow == Follow.Off) locatePending = true
        // Its 小抽屉 belong to 活动状态.
        if (recording == null) { shareSheet = false; moreSheet = false }
      }
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
                  mateSheet = null
                  shareSheet = false
                  moreSheet = false
                  nearbyTracks = emptyList()
                  if (measureFrom == null) {
                    if (nearby) e.position?.let { at ->
                      val zoom = state.cameraPosition.zoom
                      scope.launch {
                        // Offline, a line drawn from the 地图缓存 can't be listed: say so rather than show nothing (#59).
                        // 24 dp either way, as tapRadiusM.
                        val x = e.screenOffset.x
                        val y = e.screenOffset.y
                        val cached = !online && state.queryRenderedFeatures(DpRect(x - 24.dp, y - 24.dp, x + 24.dp, y + 24.dp), setOf("nearby-public", "nearby-platform")).isNotEmpty()
                        findNearby(at, zoom, cached)
                      }
                    }
                    return@onEvent ClickResult.Pass
                  }
                  measureTo = e.position ?: return@onEvent ClickResult.Pass
                  ClickResult.Consume
                }
              }
              longClick {
                onEvent { e ->
                  nearbyTracks = emptyList()
                  // §4.1: one drawer at a time.
                  layers = false
                  teamDrawer = false
                  mateSheet = null
                  shareSheet = false
                  moreSheet = false
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
            val mate = mateState(m, now)
            fun open() { mateSheet = m.id; teamDrawer = false; pressed = null; shareSheet = false; moreSheet = false; layers = false; chat = false; nearbyTracks = emptyList() }
            TeammateDot(m, mate, last.battery, Modifier.placedAt(at).clickable(onClick = ::open))
            // ux-v2 §4.5: 失联 stands out; tapped, the camera goes to where they were last and their 小抽屉 opens.
            // Padding above centres the label below the dot; the tap area around it makes 56 dp.
            if (mate == MateState.Lost) BasicText(
              m.name + " " + lostText(last.timeS, now),
              Modifier.placedAt(at).padding(top = 44.dp)
                .clickable { open(); moveTo(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 14.0)), Motion.FOCUS) }
                .padding(vertical = 18.dp).background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
              style = TextStyle(color = AlertRed, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
          }
        }
        // §2.2: the 惯用手 side; the top bar and 底栏 don't mirror.
        val handed = if (leftHanded) Alignment.Start else Alignment.End
        // §2.1: recording (paused too) is 活动状态; only the controls over the map change, with a fade.
        val active = recording != null
        val liveTeam = team?.takeIf { !it.ended }
        val sharing = liveTeam?.let { t -> t.members.firstOrNull { it.id == t.me }?.sharing } == true
        val teamLabel = teamButton(team, now)
        val teamUnread = team?.let { unread(it, readSeq).isNotEmpty() } == true
        // Re-read every 30 s ([now]), so a fix going stale shows as none.
        val fix = remember(now, me.lastLocation) { me.freshFix() }
        val batteryNow = remember(now) { battery() }
        fun openLayers() { layers = !layers; pressed = null; nearbyTracks = emptyList(); shareSheet = false; moreSheet = false; teamDrawer = false; mateSheet = null }
        fun locate() {
          if (me.lastLocation != null) follow = follow.next
          else {
            // No fix yet: the button stays; 「正在定位」 comes with the 状态条.
            if (me.permission !is LocationPermission.Granted) me.requestPermission()
            locatePending = true
          }
        }
        // §9.1: one tap stores where I am; name and photo can be added from the 提示条. Tapped while waiting, it cancels.
        fun mark() {
          if (waypointWait != null) { waypointWait = null; return }
          if (!locationOn) return startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
          if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return askMarkPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
          }
          waypointWait = System.currentTimeMillis()
        }
        // 在地图上选: the cross at the centre, saved on 确认. The map stops following so it stays put.
        fun pickOnMap() {
          follow = Follow.Off
          hint = Hint("移动地图对准位置", listOf(
            "确认" to { state.cameraPosition.target.let { saveWaypointHere(System.currentTimeMillis(), it.latitude, it.longitude, null, null) } },
            "取消" to {},
          ), sticky = true, pick = true)
        }
        LaunchedEffect(waypointWait) {
          val since = waypointWait ?: return@LaunchedEffect
          while (true) {
            val at = me.freshFix()
            // A fix that doesn't say how good it is doesn't pass.
            when (waypointStep(at?.let { it.horizontalAccuracy?.inMeters ?: Double.POSITIVE_INFINITY }, System.currentTimeMillis() - since)) {
              WaypointStep.Save -> at?.let(::saveWaypointHere)
              WaypointStep.Ask -> hint = Hint(if (at == null) "还没有定位" else "定位一直不准" + accuracyText(at.horizontalAccuracy?.inMeters), listOfNotNull(
                at?.let { "就用这里" + accuracyText(it.horizontalAccuracy?.inMeters) to { saveWaypointHere(it) } },
                "在地图上选" to ::pickOnMap,
                "取消" to {},
              ), sticky = true)
              WaypointStep.Wait -> { delay(1_000); continue }
            }
            break
          }
          waypointWait = null
        }
        // §6.5 标注等定位: 「定位中 ±80 m」, tap to cancel.
        val markLabel = if (waypointWait != null) "定位中" + accuracyText(me.lastLocation?.horizontalAccuracy?.inMeters) else "标注"
        fun zoom(by: Double) = scope.launch { state.moveCamera(this@MainActivity, state.cameraPosition.let { it.copy(zoom = it.zoom + by) }, Motion.CAMERA) }
        // §3.5: back to north-up and out of 2.5D; 朝向 drops back to 跟随. Planning under the 顶部堆叠, recording above 图层.
        val compass: @Composable (Modifier) -> Unit = { modifier ->
          val camera = state.cameraPosition
          if (camera.tilt != 0.0 || camera.bearing != 0.0) Compass(
            onClick = { if (follow == Follow.Off) moveTo(camera.copy(bearing = 0.0, tilt = 0.0), Motion.CAMERA) else { follow = Follow.On; level = true } },
            modifier = modifier,
          )
        }
        // §3.1 / §3.3 顶部堆叠, top to bottom; what isn't showing leaves no gap.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
          StateFade(active) { ActiveTopData(activePages(live, fix?.position?.altitude, batteryNow)) }
          Column(Modifier.fillMaxWidth().then(if (active) Modifier else Modifier.statusBarsPadding()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StateFade(!active) { TopBar(onSearch = { searching = true }, onLayers = ::openLayers) }
            // §3.6: under the top bar planning, under the 顶部数据 recording.
            val unsent by RecordingService.unsent.collectAsState()
            val syncFailed by CloudSync.failed.collectAsState()
            val permission = me.permission
            StatusBar(
              statusLines(active, StatusInput(
                locationOn = locationOn,
                permitted = permission !is LocationPermission.NotGranted,
                fixAccuracyM = fix?.let { it.horizontalAccuracy?.inMeters ?: 0.0 },
                reference = referenceTrack != null,
                basemap = basemap,
                online = online,
                unsent = unsent && liveTeam != null && !online,
                battery = batteryNow,
                sharing = sharing,
                syncFailed = syncFailed && syncOn,
                lastSync = remember(syncFailed) { prefs.getLong(PREF_SYNC_LAST, 0L).takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it)) } },
              )),
              onAction = { action ->
                when (action) {
                  StatusAction.OpenLocation -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                  // Denied for good: only the app's settings page can still grant it.
                  StatusAction.Permission -> if ((permission as? LocationPermission.NotGranted)?.canRequest == false) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                  } else me.requestPermission()
                  StatusAction.Terrain -> pickBasemap(Basemap.Terrain)
                }
              },
            )
            // The track open in 轨迹详情, else the 参考轨迹.
            val bannerTrack = detailTrack ?: referenceTrack
            val bannerAlerts = bannerTrack?.let { weather[it] }?.let { w -> remember(w) { w.alerts() } }.orEmpty()
            if (bannerTrack != null && bannerAlerts.isNotEmpty() && !bannerClosed) {
              TripAlertBanner(
                name = remember(bannerTrack) { TrackDb(this@MainActivity).use { it.trackName(bannerTrack) } },
                alerts = bannerAlerts,
                onOpen = { detailTrack = bannerTrack },
                onClose = { bannerClosed = true },
                modifier = Modifier,
              )
            }
            measureFrom?.let { from ->
              val to = measureTo
              val distance = to?.let { FloatArray(1).also { r -> Location.distanceBetween(from.latitude, from.longitude, it.latitude, it.longitude, r) }[0].toDouble() }
              MeasureBanner(distance, onClose = { measureFrom = null; measureTo = null }, Modifier.fillMaxWidth())
            }
            if (!active) compass(Modifier.align(handed))
          }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
          StateFade(!active, Modifier.align(handed)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = handed) {
              LocateButton(follow, onClick = ::locate)
              MarkButton(markLabel, ::mark)
            }
          }
          StateFade(!active) {
            BottomBar(
              team = teamLabel,
              unread = teamUnread,
              onTracks = { trackPage = true },
              onTeam = ::openTeam,
              onStart = { record(null) },
              onOffline = { offlinePage = true },
              onSettings = { settingsPage = true },
            )
          }
          StateFade(active) {
            Column {
              // §3.3: 图层 / + / − / 定位 on the 惯用手 side, the text buttons across from them.
              Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                val textButtons: @Composable () -> Unit = {
                  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(R.drawable.group_wght500_24px, teamLabel.first, { shareSheet = false; moreSheet = false; openTeam() }, red = teamLabel.second, dot = teamUnread)
                    PillButton(R.drawable.share_location_wght500_24px, "分享位置", { shareSheet = !shareSheet; moreSheet = false; layers = false; pressed = null })
                    PillButton(R.drawable.menu_wght500_24px, "更多", { moreSheet = !moreSheet; shareSheet = false; layers = false; pressed = null })
                  }
                }
                val mapButtons: @Composable () -> Unit = {
                  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    compass(Modifier)
                    MapIconButton(R.drawable.layers_wght500_24px, "图层", ::openLayers)
                    MapIconButton(R.drawable.add_wght500_24px, "放大", { zoom(1.0) })
                    MapIconButton(R.drawable.remove_wght500_24px, "缩小", { zoom(-1.0) })
                    LocateButton(follow, onClick = ::locate)
                  }
                }
                if (leftHanded) { mapButtons(); textButtons() } else { textButtons(); mapButtons() }
              }
              ActiveKeys(
                paused = paused,
                sos = liveTeam != null,
                leftHanded = leftHanded,
                // The 对话 drawer shows how the 求助 is getting on, and the answers.
                onSos = { sendSos(); chat = true },
                onPause = { recordingAction("pause"); buzz() },
                onResume = { recordingAction("resume") },
                onEnd = {
                  // Not stopService: the service carries on for the team. The hold already buzzed.
                  recordingAction("stop")
                  detailTrack = recording
                  hint = Hint("已保存 · " + distanceText(live?.distanceM ?: 0.0))
                },
                markLabel = markLabel,
                onMark = ::mark,
              )
            }
          }
        }
        if (shareSheet) {
          BackHandler { shareSheet = false }
          SmallSheet(
            listOfNotNull(
              liveTeam?.let { Triple("发到队伍对话", null, { shareSheet = false; sendLocation() }) },
              Triple("分享坐标", null, { shareSheet = false; currentFix()?.let { shareCoordinate(it.latitude, it.longitude) } ?: toast("正在定位 · 到开阔处更快") }),
              liveTeam?.let { Triple("共享我的位置", sharing, { setSharing(!sharing) }) },
            ),
            Modifier.align(Alignment.BottomCenter),
          )
        }
        if (moreSheet) {
          BackHandler { moreSheet = false }
          SmallSheet(
            listOf(
              Triple("我的轨迹", null, { moreSheet = false; trackPage = true }),
              Triple("离线地图", null, { moreSheet = false; offlinePage = true }),
              Triple("设置", null, { moreSheet = false; settingsPage = true }),
            ),
            Modifier.align(Alignment.BottomCenter),
          )
        }
        val chatTeam = team
        if (chat && chatTeam != null) {
          BackHandler { chat = false }
          val drawerDp = LocalConfiguration.current.screenHeightDp.dp / 2
          ChatDrawer(
            chatTeam, sosNote, here,
            loadImage = { id, thumb -> loadImage(chatTeam.id, id, thumb) },
            onSend = { sendMessage(messageJson("text", text = it)) },
            onLocation = ::sendLocation,
            onPhoto = { pickChatPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onSos = ::sendSos,
            onSosRetry = if (sosFailed) ::sendSos else null,
            onFocus = { lat, lon ->
              val at = Position(longitude = lon, latitude = lat)
              chatPin = at
              // Padded, so it lands in the map's half above the drawer.
              moveTo(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 14.0), padding = DpPadding(0.dp, 0.dp, 0.dp, drawerDp)), Motion.FOCUS)
            },
            onClose = { chat = false },
          )
        }
        // ux-v2 §4.1: one drawer at a time. 轨迹详情 replaces the one open, and the next one opened replaces it.
        fun otherDrawer() = pressed != null || layers || shareSheet || moreSheet || chat || nearbyTracks.isNotEmpty() || teamDrawer || mateSheet != null
        LaunchedEffect(detailTrack) { exportSheet = false; if (detailTrack != null) { pressed = null; layers = false; shareSheet = false; moreSheet = false; chat = false; nearbyTracks = emptyList(); teamDrawer = false; mateSheet = null } }
        // Opened, the 队伍抽屉 replaces the 小抽屉 and 对话 too.
        LaunchedEffect(teamDrawer) { if (teamDrawer) { pressed = null; layers = false; shareSheet = false; moreSheet = false; chat = false; nearbyTracks = emptyList(); mateSheet = null } }
        // Read again inside: when both open at once, 轨迹详情 (just closed the other above) stays.
        LaunchedEffect(otherDrawer()) { if (otherDrawer()) detailTrack = null }
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
            basemap, overseas, contours, hillshade, tilted = camera.tilt != 0.0, nearby = nearby, trails = trails.takeIf { team != null }, overlaid = overlays.size,
            onTracks = { layers = false; trackPage = true },
            onBasemap = ::pickBasemap,
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
            nearbyTracks, nearbyNote, nearbySaved,
            onReference = { saveNearby(it)?.let(::setReference); nearbyTracks = emptyList() },
            onSave = { t -> saveNearby(t)?.let { nearbySaved += t to it } },
            onOpen = { detailTrack = it },
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
        if (settingsPage) {
          BackHandler { settingsPage = false }
          SettingsScreen(
            leftHanded,
            onLeftHanded = { leftHanded = it; prefs.edit().putBoolean(PREF_LEFT_HANDED, it).apply() },
            // §2.12: login is asked for by 队伍 and 同步 only.
            onAccount = { syncAfterLogin = account == null; accountPage = true },
            onAbout = { aboutPage = true },
            onBattery = { batteryGuide = true },
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
            downloading = downloadPercent.takeIf { downloading },
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
          // Backed out: a join it was asked for is dropped, not carried out by a later login.
          BackHandler { accountPage = false; teamAfterLogin = null }
          AccountScreen(
            account,
            sendCode = api::sendCode,
            login = api::login,
            onLogin = {
              accounts.set(it)
              account = it
              accountPage = false
              toast("已登录")
              teamAfterLogin?.let { joinTeam(it.ifEmpty { null }) }
              teamAfterLogin = null
              if (syncAfterLogin) setSync(true)
              syncAfterLogin = false
            },
            onLogout = { logout() },
            sync = syncOn,
            lastSync = remember(accountPage, synced) { prefs.getLong(PREF_SYNC_LAST, 0L).takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it)) } },
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
        if (teamDrawer) {
          BackHandler { teamDrawer = false }
          var full by rememberSaveable { mutableStateOf(false) }
          TeamDrawer(
            team, now,
            unread = team?.let { unread(it, readSeq).size } ?: 0,
            here = here,
            full = full, onFull = { full = it },
            name = teamName,
            onName = { teamName = it; prefs.edit().putString(PREF_TEAM_NAME, it).apply() },
            busy = teamBusy, note = teamNote,
            onCreate = { joinTeam(null) },
            onJoin = ::joinTeam,
            onSeeAll = { full = false; scope.launch { fitAboveDrawer(mates.map { m -> m.trail.last().let { Position(longitude = it.lon, latitude = it.lat) } }) } },
            // ux-v2 §4.4: 规划状态's only 求助; 活动状态 has the big key.
            onSos = { sendSos(); teamDrawer = false; chat = true }.takeIf { !active },
            onFocus = { m ->
              full = false
              val p = m.trail.last()
              // In the middle of the map's half above the drawer.
              val zoom = maxOf(state.cameraPosition.zoom, 14.0)
              moveTo(state.cameraPosition.copy(target = centreAbove(Position(longitude = p.lon, latitude = p.lat), zoom, window.second / 2), zoom = zoom), Motion.FOCUS)
            },
            onChat = { teamDrawer = false; chat = true },
            onShare = { teamDrawer = false; shareSheet = true },
            onManage = { teamManage = true },
            onClose = { teamDrawer = false },
          )
        }
        mateSheet?.let { id ->
          val m = mates.firstOrNull { it.id == id } ?: return@let
          BackHandler { mateSheet = null }
          MateSheet(m, now, here, Modifier.align(Alignment.BottomCenter))
        }
        val manageTeam = team
        if (teamManage && manageTeam != null) {
          BackHandler { teamManage = false }
          // A logout meanwhile reads as an expired login.
          fun acct() = account ?: throw OfflineError("unauthorized")
          TeamManageScreen(
            manageTeam, teamSaver,
            onSharing = ::setSharing,
            onSaver = { teamSaver = !teamSaver; prefs.edit().putBoolean(PREF_TEAM_SAVER, teamSaver).apply() },
            leave = { api.leaveTeam(acct(), manageTeam.id) },
            end = { api.endTeam(acct(), manageTeam.id) },
            onLeft = { quitTeam(); teamManage = false; teamDrawer = false },
          )
        }
        if (searching) {
          BackHandler { searching = false }
          SearchScreen(searchQuery, searchResults, searchNote, onQuery = { searchQuery = it }, onPick = { p ->
            searching = false
            follow = Follow.Off
            val at = Position(longitude = p.lon, latitude = p.lat)
            state.setCameraPosition(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 13.0)))
            pressed = at
          })
        }
        if (trackPage) {
          BackHandler { trackPage = false }
          TrackListScreen(
            tracks = remember(tracksVersion) { TrackDb(this@MainActivity).use { it.tracks() } },
            waypoints = waypoints,
            importing = importingTrack,
            onOpen = { detailTrack = it; trackPage = false },
            overlays = overlays,
            onOverlay = ::toggleOverlay,
            onClearOverlays = { saveOverlays(emptyMap()) },
            onWaypoint = ::openWaypoint,
            // Track files often arrive with no or a generic MIME type; the content decides the format.
            onImport = { pickTrackFile.launch(arrayOf("*/*")) },
          )
        }
        val id = detailTrack
        if (id != null && detail != null) {
          BackHandler { detailTrack = null }
          val (name, datum, segments) = detail
          val request = remember(segments) { trackRequest(segments) }
          val pkg = packages.firstOrNull { it.request == request }
          TrackDetailScreen(
            name,
            planned = remember(id) { TrackDb(this@MainActivity).use { it.planned(id) } },
            stats = remember(segments) { trackStats(segments) },
            dateMs = segments.firstOrNull { it.isNotEmpty() }?.first()?.timeMs?.takeIf { it > 0 },
            datum = datum,
            reference = id == referenceTrack,
            overlaid = id in overlays,
            public = remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.isPublic(id) } },
            weather = weather[id],
            weatherLoading = id in weatherLoading,
            pace = pace,
            corridor = corridorText(pkg, dataVersion, downloadPercent.takeIf { downloadRequest == request }),
            onDownload = {
              dueBattery()
              downloadPackage("沿轨迹 $name", request, old = pkg)
              // §2.9: the weather is cached with the offline package.
              loadWeather(id, force = true)
            }.takeIf { !downloading && (pkg == null || dataVersion != null && pkg.version != dataVersion) },
            batteryRow = batteryDue && !batterySet,
            onBattery = { batteryGuide = true },
            onReference = { setReference(if (id == referenceTrack) null else id) },
            onOverlay = { toggleOverlay(id) },
            onPublic = { togglePublic(id); datumVersion++ },
            onDatum = { d -> TrackDb(this@MainActivity).use { it.setDatum(id, d) }; datumVersion++; waypointsVersion++; loadWeather(id, force = true) },
            onRename = { n -> TrackDb(this@MainActivity).use { it.setName(id, n) }; datumVersion++; tracksVersion++ },
            onPace = { p ->
              pace = p
              prefs.edit().putString(PREF_PACE, p.name).apply()
              loadWeather(id, force = true)
            },
            onDepart = { pickDeparture(id) },
            onExport = { exportSheet = true },
            onClose = { detailTrack = null },
          )
          if (exportSheet) {
            BackHandler { exportSheet = false }
            SmallSheet(
              listOf(
                Triple("GPX（大多数 App 和手表都能打开）", null, { exportSheet = false; exportTrack(id, false) }),
                Triple("KML（奥维、Google 地球）", null, { exportSheet = false; exportTrack(id, true) }),
              ),
              Modifier.align(Alignment.BottomCenter),
            )
          }
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
            onShare = { shareCoordinate(at.latitude, at.longitude) },
            onDownload = { pressed = null; downloadNearby(at.latitude, at.longitude, null) },
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
            onDelete = { deleteWaypoint(w); editing = null },
            onDownload = { saveWaypoint(w); editing = null; downloadNearby(w.lat, w.lon, editName.trim().ifEmpty { null }) },
            onDone = { saveWaypoint(w); editing = null },
          )
        }
        if (hint?.pick == true) {
          BackHandler { hint = null }
          Crosshair(Modifier.align(Alignment.Center))
        }
        // Above the 底栏 or the big keys.
        val hintPlace = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)
        hint?.let { h ->
          LaunchedEffect(h) {
            if (h.sticky) return@LaunchedEffect
            delay(hintMs(active, h.actions.isNotEmpty()))
            hint = null
          }
          HintBar(h, big = active, onClose = { hint = null }, hintPlace)
        }
        // 下载这附近's progress where the 提示条 goes, when nothing else is there: no 提示条, 轨迹详情 or 离线地图 (they
        // show their own), no 小抽屉 or 标注 page.
        if (downloading && hint == null && detailTrack == null && !offlinePage && pressed == null && editing == null) {
          HintBar(Hint("离线地图下载中 $downloadPercent%"), big = active, onClose = {}, hintPlace)
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
            onDismiss = { batteryGuide = false; batterySet = true; prefs.edit().putBoolean(PREF_BATTERY_SET, true).apply() },
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

  /** 下载这附近 (§2.3): about 20 × 20 km around the point, once its size is confirmed; [name] is what's there, if known. */
  private fun downloadNearby(lat: Double, lon: Double, name: String?) {
    if (downloading) return run { hint = Hint("正在下载另一个离线包，等它下完再下载") }
    val (w, s, e, n) = nearbyBbox(lat, lon)
    hint = Hint(NEARBY_CONFIRM, listOf(
      "下载" to { downloadPackage((name ?: String.format(Locale.ROOT, "%.3f, %.3f", lat, lon)) + " 附近", bboxRequest(w, s, e, n)) },
      "取消" to {},
    ), sticky = true)
  }

  /** Downloads an offline package (§2.3) into packages/; an update replaces [old] once the new one is complete. */
  private fun downloadPackage(name: String, request: String, old: OfflinePackage? = null) {
    if (downloading) return toast("正在下载另一个离线包，等它下完再下载")
    downloadRequest = request
    downloadPercent = 0
    thread {
      // Downloaded into a hidden staging dir, then moved under a new name: a half-finished package never
      // reaches the style, and an updated one gets a new path so MapLibre reopens its files.
      val staging = File(packagesDir, ".staging").apply { deleteRecursively() }
      val result = runCatching {
        val pkg = api.download(name, request, staging) { p -> runOnUiThread { downloadPercent = p } }
        val dest = File(packagesDir, System.currentTimeMillis().toString())
        check(staging.renameTo(dest))
        old?.dir?.deleteRecursively()
        pkg.copy(dir = dest)
      }
      if (result.isFailure) staging.deleteRecursively()
      runOnUiThread {
        downloadRequest = null
        result.onSuccess {
          dataVersion = it.version
          filesVersion++
          hint = Hint("已下载 ${it.name}（${Formatter.formatShortFileSize(this, it.bytes)}）")
        }.onFailure {
          // ux-v2 §6.5: a download cut short (no signal, or anything unexplained) offers 重试.
          val code = (it as? OfflineError)?.code
          hint = if (code == null || code == "offline") Hint("离线地图没下完", listOf("重试" to { downloadPackage(name, request, old) }))
          else Hint(offlineMessage(code))
        }
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
    if (ext !in importableExtensions) return toast("只能导入 MBTiles 或 PMTiles 文件")
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
        toast(if (ok) "已导入 $name" else "读不了 $name，换一个 MBTiles 或 PMTiles 文件")
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
    // Back from the battery settings the 出发前 row may be done with.
    batterySet = prefs.getBoolean(PREF_BATTERY_SET, false) || getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
  }

  override fun onDestroy() {
    // A 求助 still retrying dies with this activity (see sendSos).
    sosRetry?.let(handler::removeCallbacks)
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
    unregisterReceiver(locationSwitch)
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
          result.isFailure -> toast("读不了 $name，换一个轨迹文件")
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
        }.onFailure { toast("$fileName 没导入成功，再试一次") }
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

  private fun saveWaypointHere(fix: LocationMeasurement) =
    saveWaypointHere(fix.measuredAt.toEpochMilliseconds(), fix.position.latitude, fix.position.longitude, fix.position.altitude, fix.horizontalAccuracy?.inMeters)

  /** Saves a 标注 where I am (or picked) and offers 撤销 / 补充 (§9.1); [accuracyM] for the 提示条. */
  private fun saveWaypointHere(timeMs: Long, lat: Double, lon: Double, ele: Double?, accuracyM: Double?) {
    // Nothing syncs while the 提示条 can still 撤销 it (§9.1: it never reaches the server).
    CloudSync.hold(this, HINT_LONGEST_MS)
    val w = addWaypoint(timeMs, lat, lon, ele)
    buzz()
    hint = Hint("已标注" + accuracyText(accuracyM), listOf("撤销" to { deleteWaypoint(w) }, "补充" to { openWaypoint(w) }))
  }

  private fun deleteWaypoint(w: Waypoint) {
    TrackDb(this).use { it.deleteWaypoint(w.id) }
    w.photo?.let { File(it).delete() }
    waypointsVersion++
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
    if (!ok) return toast("读不了这张照片，换一张").also { file.delete() }
    saveWaypoint(w, file.path)
    w.photo?.let { File(it).delete() }
  }

  /**
   * 经过这里的轨迹 (§2.8) for a tap at [at]: the 徒步线路 of the pushed data and every package, and the 平台轨迹 and
   * 公开轨迹 online, else from the packages' snapshots. Shown once found; nothing near, nothing shown, unless offline
   * the tap [cached] a 地图缓存 line, which can be seen but not listed.
   */
  private fun findNearby(at: Position, zoom: Double, cached: Boolean) {
    val seq = ++nearbySeq
    val radius = tapRadiusM(at.latitude, zoom)
    val dirs = listOf(dir) + packages().map { it.dir }
    // ponytail: re-reads the files on every tap; small per package, but the pushed full-China routes.geojson
    // takes seconds. Keep them parsed (by filesVersion) if that bites outside development.
    fun read(name: String) = dirs.mapNotNull { File(it, name).takeIf(File::exists)?.readText() }
    thread {
      val fetched = runCatching { byKind(api.nearbyTracks(at.latitude, at.longitude, radius)) }.getOrNull()
      val tracks = fetched ?: read("platform.geojson").map { NearbyKind.Platform to it } + read("public-tracks.geojson").map { NearbyKind.Public to it }
      val found = nearbyTracks(read("routes.geojson").map { NearbyKind.Route to it } + tracks, at.latitude, at.longitude, radius)
      runOnUiThread {
        if (seq != nearbySeq || !nearby) return@runOnUiThread
        nearbyTracks = found
        nearbySaved = emptyMap()
        nearbyNote = if (fetched == null) OFFLINE_NEARBY else null
        if (fetched == null && found.isEmpty() && cached) toast(OFFLINE_NEARBY)
      }
    }
  }

  /** Saves a 周边路网 line to 我的轨迹 as a 计划轨迹 (it has no times); its id, or null if that failed. */
  private fun saveNearby(t: NearbyTrack): Long? = runCatching {
    val now = System.currentTimeMillis()
    TrackDb(this).use { it.importTrack(ParsedTrack(t.name, true, t.segments), nearbyName(t.name, now), emptyList(), now) }
  }.onSuccess { tracksVersion++ }.onFailure { toast("没保存上，再试一次") }.getOrNull()

  /** 叠加 or 取消叠加 [id]; a seventh is refused rather than pushing one out (ux-v2 §9.2). */
  private fun toggleOverlay(id: Long) {
    if (id in overlays) return saveOverlays(overlays - id)
    overlays.overlay(id)?.let(::saveOverlays) ?: run { hint = Hint("最多叠加 ${overlayColors.size} 条，先取消一条") }
  }

  private fun saveOverlays(m: Map<Long, Int>) {
    overlays = m
    prefs.edit().putString(PREF_OVERLAYS, overlaysText(m)).apply()
  }

  private fun setReference(id: Long?) {
    getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_REFERENCE, id ?: 0L).apply()
    referenceTrack = id
    // The service only notices the change on its next fix; don't leave an alert for the old one up until then.
    getSystemService(android.app.NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    // ponytail: alerts ride on the recording service's GPS; a separate follow-only service if people follow without recording.
    if (id != null) {
      hint = Hint("已设为参考 · 偏离 ${OFF_TRACK_M.toInt()} m 会提醒")
      dueBattery()
    }
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

  /** The 队伍抽屉 (ux-v2 §4.4); a login is only asked for on 创建 or 加入 (ux-v2 §8 路径 5). */
  private fun openTeam() {
    teamNote = null
    teamDrawer = true
  }

  /** 创建队伍 ([code] null) or joins [code]; logged out, it logs in first and then carries on by itself. */
  private fun joinTeam(code: String?) {
    val acct = account ?: run {
      teamAfterLogin = code.orEmpty()
      accountPage = true
      return toast("使用队伍需要先用手机号登录")
    }
    teamBusy = true
    teamNote = null
    val name = teamName.trim()
    thread {
      val t = runCatching { if (code == null) api.createTeam(acct, name) else api.joinTeam(acct, code, name) }
      runOnUiThread {
        teamBusy = false
        t.onSuccess {
          prefs.edit().putLong(PREF_TEAM, it.id).apply()
          RecordingService.showTeam(it)
          shareWithTeam(it.id)
          teamDrawer = true
        }.onFailure { teamNote = teamMessage((it as? OfflineError)?.code, if (code == null) "创建队伍" else "加入队伍") }
      }
    }
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
   * (or no signal) said, by default a 提示条 with 重试 (ux-v2 §6.5).
   */
  private fun sendMessage(json: String, onSent: () -> Unit = {}, onFail: (String?) -> Unit = {
    hint = Hint("没发出去，" + (teamReason(it) ?: "再试一次"), listOf("重试" to { sendMessage(json) }))
  }) {
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

  private fun sendLocation() {
    val fix = currentFix() ?: return toast("正在定位 · 到开阔处更快")
    sendMessage(messageJson("location", lat = fix.latitude, lon = fix.longitude))
  }

  private fun shareCoordinate(lat: Double, lon: Double) {
    // Shared text still says WGS-84 (§6.5).
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "坐标（WGS-84）：" + coordinateText(lat, lon))
    startActivity(Intent.createChooser(send, "分享坐标"))
  }

  /** 暂停 / 继续 / 结束 the recording. */
  private fun recordingAction(action: String) = startService(Intent(this, RecordingService::class.java).setAction(action))

  /** 共享我的位置 on or off. */
  private fun setSharing(on: Boolean) =
    startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_SHARE).putExtra(RecordingService.EXTRA_SHARING, on))

  /** 一键求助 (§2.11) with where we are and the battery; with no signal it keeps trying every 15 s. */
  // ponytail: retried by the activity; a 求助 still unsent when the app is closed or rotated is lost. Move to the service if that bites.
  private fun sendSos() {
    val fix = currentFix()
    val battery = battery()
    val json = messageJson("sos", lat = fix?.latitude, lon = fix?.longitude, battery = battery)
    fun attempt() {
      sosNote = "正在发出求助…"
      sosFailed = false
      sendMessage(json, onSent = { sosNote = "求助已发出 · 已通知 ${(RecordingService.team.value?.members?.size ?: 1) - 1} 人"; buzz() }, onFail = { code ->
        if (code != "offline") return@sendMessage run { sosNote = "求助没发出去" + (teamReason(code)?.let { "：$it" } ?: ""); sosFailed = true }
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
          .onFailure {
            // ux-v2 §6.5 对话发送失败: what went wrong, and 重试.
            if (it is OfflineError) hint = Hint("图片没发出去，" + (teamReason(it.code) ?: "再试一次"), listOf("重试" to { sendPhoto(uri) }))
            else toast("读不了这张图片，换一张")
          }
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

  private fun pickBasemap(b: Basemap) {
    basemap = b
    prefs.edit().putString(PREF_BASEMAP, b.name).apply()
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
    if (resume == null) {
      buzz()
      hint = Hint("开始记录" + if (RecordingService.team.value?.ended == false) " · 队友能看到你的位置" else "")
    }
  }

  /** 设为参考 or 沿线下载 was tapped: time to offer the 出发前 battery row. */
  private fun dueBattery() {
    batteryDue = true
    prefs.edit().putBoolean(PREF_BATTERY_DUE, true).apply()
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

/**
 * A teammate on the map (ux-v2 §4.5): a dot in their colour with their initial, faded after 5 min, grey in a dashed ring
 * once 失联, a hollow grey ring once they stopped sharing; below 20% battery a small red badge with it.
 */
@Composable
private fun TeammateDot(m: TeamMember, state: MateState?, battery: Int?, modifier: Modifier) {
  // 56 dp to tap, the dot in its middle.
  Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(28.dp).alpha(if (state == MateState.Stale) 0.5f else 1f).drawBehind {
        if (state == MateState.Lost) drawCircle(Color.Gray, radius = size.minDimension / 2 + 5.dp.toPx(),
          style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
      }.then(
        if (state == MateState.Stopped) Modifier.border(3.dp, Color.Gray, CircleShape)
        else Modifier.background(if (state == MateState.Lost) Color.Gray else Color(memberColor(m.id)), CircleShape)
      ),
      contentAlignment = Alignment.Center,
    ) {
      BasicText(m.name.take(1), style = TextStyle(color = if (state == MateState.Stopped) Color.Gray else Color.White, fontSize = 14.sp))
    }
    if (battery != null && battery < 20 && state != MateState.Stopped) BasicText(
      "$battery%",
      Modifier.offset(x = 18.dp, y = (-14).dp).background(AlertRed, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
      style = TextStyle(color = Color.White, fontSize = 9.sp),
    )
  }
}

/** A track line over a white casing (ux-v2 §3.8). */
@Composable
private fun CasedLine(id: String, geoJson: String, color: Color, width: Dp) {
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson))
  LineLayer(id = "$id-casing", source = source, color = const(Color.White), width = const(width + 3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))
  LineLayer(id = id, source = source, color = const(color), width = const(width), cap = const(LineCap.Round), join = const(LineJoin.Round))
}

/** A filled circle, for symbol-layer icons. */
private class DotPainter(private val color: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() = drawCircle(color)
}

// ponytail: keeps every n-th point for display (MapLibre simplifies further per zoom); Douglas–Peucker if sharp turns get lost.
/** The track as a GeoJSON MultiLineString, thinned to about [max] points (§2.6: raw points kept, thinned for display). */
private fun displayLine(segments: List<List<TrackPoint>>, max: Int = 5000): String {
  val step = maxOf(1, segments.sumOf { it.size } / max)
  // A lone point (a segment just started) is no line.
  return segments.filter { it.size > 1 }.joinToString(",", "{\"type\":\"MultiLineString\",\"coordinates\":[", "]}") { seg ->
    seg.filterIndexed { i, _ -> i % step == 0 || i == seg.lastIndex }.joinToString(",", "[", "]") { "[${it.lon},${it.lat}]" }
  }
}
