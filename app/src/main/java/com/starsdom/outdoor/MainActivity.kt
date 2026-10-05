package com.starsdom.outdoor

import android.Manifest
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
  /** For what runs on its own (launch, timers, thumbnails): a client_outdated there never re-raises [UpgradePrompt]. */
  private val quietApi by lazy { api(prefs, quiet = true) }
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
  /** OpenFreeMap's style JSON, light and dark (ux-v3 §2.8), once fetched. */
  private var openFreeMap by mutableStateOf(mapOf<Boolean, String>())
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
  /** The 标注组 page open (#121). */
  private var openGroup by mutableStateOf<Long?>(null)
  private var tracksVersion by mutableIntStateOf(0)
  private var importingTrack by mutableStateOf(false)
  // ponytail: a parsed file waiting for track selection is lost if the activity is recreated; the user opens it again.
  private var pendingImport by mutableStateOf<Pair<String, TrackFile>?>(null)
  private var pickChecked by mutableStateOf(setOf<Int>())
  /** 参考轨迹 (§2.7); the recording service reads it from prefs to raise 偏离提醒. */
  private var referenceTrack by mutableStateOf<Long?>(null)
  /** Bumped when a track's 起算点 (§2.7) changes; they're kept per track on this phone. */
  private var startsVersion by mutableIntStateOf(0)
  /** 参考轨迹抽屉 (ux-v2 §4.3) open. */
  private var referenceDrawer by mutableStateOf(false)
  /** Its 在轨迹上选's 提示条: while it's the one showing, a tap on the track is the new 起点. However it closes, that ends. */
  private var startPick: Hint? = null
  /** 叠加 (ux-v2 §9.2): track id → its place in the order overlaid ([Semantic.overlay]). */
  private var overlays by mutableStateOf(mapOf<Long, Int>())
  /** 天气 (§2.9) where I am: the last forecast, kept in [hereWeatherFile] for offline. */
  private var hereWeather by mutableStateOf<PlaceWeather?>(null)
  private val hereWeatherFile by lazy { File(filesDir, "weather-here.json") }
  /** 搜索 (§2.10): what's typed, what it found, and where the results came from. */
  private var searchQuery by mutableStateOf("")
  private var searchResults by mutableStateOf(listOf<Place>())
  private var searchNote by mutableStateOf<String?>(null)
  /** 惯用手 (ux-v2 §2.2): left mirrors 定位 and 标注 to the left. */
  private var leftHanded by mutableStateOf(false)
  private val darkPalette by lazy { darkPalette(assets.open("style-dark.tsv").bufferedReader().readText()) }
  private val aliases by lazy { aliasPlaces(assets.open("peak-aliases.tsv").bufferedReader().readText()) }
  /** 队伍 (§2.11): the 队伍页 (ux-v2 §4.4), on its 队伍信息 or, after 结束行程, on 建队 / 加入; 尾迹 shown; 省电模式. */
  private var teamPage by mutableStateOf(false)
  private var teamInfo by mutableStateOf(false)
  private var teamJoin by mutableStateOf(false)
  /** A join (its code; "" for 创建队伍) waiting for the login it asked for, then carried out (ux-v2 §8 路径 5). */
  private var teamAfterLogin: String? = null
  /** Creating or joining in flight, and what went wrong last. */
  private var teamBusy by mutableStateOf(false)
  private var teamNote by mutableStateOf<String?>(null)
  private var trails by mutableStateOf(true)
  /** The teammate picked in the 成员列表, their 尾迹 bold (ux-v3 §2.4); set from there in V17 (#187). */
  private var highlightedMate by mutableStateOf<Long?>(null)
  private var teamSaver by mutableStateOf(false)
  private var teamName by mutableStateOf("")
  /** Team to share with once location is granted (0 = none; else it's recording that asked). */
  private var teamAfterGrant = 0L
  /** 队伍对话 (§2.11): what's typed, and the last message read. */
  // ponytail: gone on rotation or process death, like the open page itself; save it if that bites.
  private var chatDraft by mutableStateOf("")
  private var readSeq by mutableLongStateOf(0L)
  /** Counts onResume, so an ended team's 对话 is caught up each time the app comes back (it has no socket). */
  private var resumes by mutableIntStateOf(0)
  // ponytail: photos in memory only, the last 40; a disk cache if people scroll long chats offline.
  private val images = LruCache<String, ImageBitmap>(40)
  private val pickChatPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }
  private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachPhoto) }
  /** 一键标注 (§9.1): when it started waiting for a good enough fix (ms); null when not waiting. */
  private var waypointWait by mutableStateOf<Long?>(null)
  /** The 提示条 showing (§5) and those waiting behind a sticky one ([queueHint]). */
  private var hints by mutableStateOf(listOf<Hint>())
  /** The 提示条 showing, or none; setting one queues it, null closes it. */
  private var hint: Hint?
    get() = hints.firstOrNull()
    set(next) { hints = queueHint(hints, next) }
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
    // Bar icons follow the system light / dark, as the theme does.
    enableEdgeToEdge()
    // Map tiles from our API (天地图) carry the same headers as its other calls: device ID and version gate.
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
    // 强制升级 (#118): asked once a launch; offline, nothing is asked and nothing is locked.
    if (savedInstanceState == null) thread { runCatching { if (api.outdated()) ClientOutdated.prompt.value = true } }
    referenceTrack = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L).takeIf { it != 0L }
    batteryDue = prefs.getBoolean(PREF_BATTERY_DUE, false)
    overlays = readOverlays(prefs.getString(PREF_OVERLAYS, null), TrackDb(this).use { db -> db.tracks().map { it.id }.toSet() })
    hereWeather = cachedWeather(hereWeatherFile)
    // Each track's 沿途天气 from before the weather stood on its own (§2.9).
    File(filesDir, "weather").deleteRecursively()
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
      if (account == null) { prefs.edit().remove(PREF_TEAM).apply(); RecordingService.endTrip(this, id, recording = RecordingService.activeTrack.value != null) } else resumeTeam(id)
    }
    savedInstanceState?.let {
      batteryGuide = it.getBoolean("batteryGuide")
      resumeAfterGrant = it.getLong("resumeAfterGrant").takeIf { id -> id != 0L }
      // Kept so a photo picked after the activity was recreated still lands on its 标注.
      editing = it.getLong("editing").takeIf { id -> id != 0L }
      editName = it.getString("editName").orEmpty()
      editDescription = it.getString("editDescription").orEmpty()
      trackPage = it.getBoolean("trackPage")
      openGroup = it.getLong("openGroup").takeIf { id -> id != 0L }
      detailTrack = it.getLong("detailTrack").takeIf { id -> id != 0L }
    } ?: openedFile(intent)
    // A pull may have deleted the 参考轨迹 (or the open one) since last time.
    dropGoneTracks()
    openedChat(intent)

    setContent { AppTheme {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      // ponytail: reads each import's header on the main thread; move off-thread if people import dozens.
      val terrain = remember(filesVersion) { style() }
      var layers by remember { mutableStateOf(false) }
      // 活动状态's 分享位置 and 更多 小抽屉 (§3.3).
      var shareSheet by remember { mutableStateOf(false) }
      var moreSheet by remember { mutableStateOf(false) }
      var datumVersion by remember { mutableIntStateOf(0) }
      // Whatever a pull brought in shows at once.
      val pulled by CloudSync.changes.collectAsState()
      LaunchedEffect(pulled) { if (pulled > 0) { dropGoneTracks(); datumVersion++; tracksVersion++; waypointsVersion++ } }
      // A trip's reports just became a track (§2.11 由位置共享生成轨迹).
      val tripTracks by RecordingService.tripTracks.collectAsState()
      LaunchedEffect(tripTracks) { if (tripTracks > 0) tracksVersion++ }
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
      // §2.11 队伍轨迹, a member's side: given or changed, it's fetched into 我的轨迹 with its 起算点 and, unless I moved
      // on to another, becomes my 参考轨迹 with 撤销. Offline, it's fetched once back.
      LaunchedEffect(team?.id, team?.track?.version, online) {
        val t = team?.takeIf { !it.ended && it.initiator != it.me } ?: return@LaunchedEffect
        val ref = t.track ?: return@LaunchedEffect
        val last = teamTrackHere()?.takeIf { it.team == t.id }
        if (last != null && last.version >= ref.version) return@LaunchedEffect
        val acct = account ?: return@LaunchedEffect
        val fetching = Hint("正在获取队伍轨迹", sticky = true)
        hint = fetching
        // Until it comes: a failed fetch (no signal, the server busy) tries again every 30 s while this version stands.
        // ponytail: fetched while the app is open; the service could fetch in the pocket if 更换 there matters.
        val (r, copy) = try {
          var got: Pair<TeamTrackRef, Long>? = null
          while (got == null) {
            got = withContext(Dispatchers.IO) { runCatching { parseTeamTrack(quietApi.teamTrack(acct, t.id)).let { (r, segments) -> r to copyTeamTrack(r, segments) } }.getOrNull() }
            if (got == null) delay(30_000)
          }
          got
        } finally {
          hints = hints.filter { it !== fetching }
        }
        prefs.edit().putString(PREF_TEAM_TRACK, TeamTrackHere(t.id, copy, r.version).text).apply()
        tracksVersion++
        val before = referenceTrack
        val beforeStart = trackStart(copy)
        saveTrackStart(copy, r.start)
        if (!followTeamTrack(before, last?.track)) return@LaunchedEffect
        setReference(copy)
        hint = Hint("已设为参考 · ${r.name}", listOf("撤销" to { setReference(before); saveTrackStart(copy, beforeStart) }))
      }
      // Ticks "x 分钟前" along.
      var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
      LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
      // Teammates with a position; one who stopped sharing stays as a hollow dot where they were (ux-v2 §4.5).
      val mates = team?.let { t -> t.members.filter { it.id != t.me && it.trail.isNotEmpty() } }.orEmpty()
      // Where this phone is, for teammates' distance and direction.
      val here = RecordingService.lastFix?.let { TeamPosition(it.time / 1000, it.latitude, it.longitude, null) }
        ?: team?.let { t -> t.members.firstOrNull { it.id == t.me }?.trail?.lastOrNull() }
      // §2.11: teammates' 沿轨里程 go by the 队伍轨迹, whatever my own 参考轨迹; each from the positions as they come.
      val teamTrackPoints = team?.track?.let { tr -> remember(team?.id, tr, team?.ended, tracksVersion) { teamWalked(team) } }
      // 队友小抽屉 (ux-v2 §4.5): whose.
      var mateSheet by remember { mutableStateOf<Long?>(null) }
      val recording by RecordingService.activeTrack.collectAsState()
      val referenceSegments = referenceTrack?.let { id -> remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.segments(id) } } }
      // As walked from its 起算点: what the line, its 里程标注 and the 沿轨里程 read off.
      val referenceStart = remember(referenceTrack, startsVersion) { trackStart(referenceTrack) }
      val referenceWalked = referenceSegments?.let { remember(it, referenceStart) { oriented(it, referenceStart) } }
      val detailStart = remember(detailTrack, startsVersion) { trackStart(detailTrack) }
      val detailWalked = detailSegments?.let { remember(it, detailStart) { oriented(it, detailStart) } }
      val referenceStats = referenceWalked?.let { remember(it) { trackStats(it) } }
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
      // Where a 群聊 location points, until the map is tapped.
      var chatPin by remember { mutableStateOf<Position?>(null) }
      val waypoints = remember(waypointsVersion) { TrackDb(this@MainActivity).use { it.waypoints() } }
      val groups = remember(waypointsVersion) { TrackDb(this@MainActivity).use { it.groups() } }
      val stroke = semantic.stroke
      val waypointColor = semantic.warn
      val waypointDot = remember(waypointColor, stroke) { DotPainter(waypointColor, stroke) }
      val shownWaypoints = remember(waypoints, detailTrack, referenceTrack, overlays.keys, recording) {
        shownWaypoints(waypoints, setOfNotNull(detailTrack, referenceTrack, recording) + overlays.keys)
      }
      // Whether the camera is outside China (§2.2: overseas 标准 and 地形 are OpenFreeMap); set from the camera below.
      var overseas by remember { mutableStateOf(false) }
      val dark = isSystemInDarkTheme()
      val style = remember(terrain, basemap, overseas, openFreeMap, contours, hillshade, nearby, online, dark) {
        basemapStyle(terrain, basemap, overseas, openFreeMap[dark], BuildConfig.API_URL, contours, hillshade, nearby, online, darkPalette.takeIf { dark })
      }
      // The camera's zoom to the quarter, for the 里程标注; set from the camera below.
      var markZoom by remember { mutableDoubleStateOf(12.0) }
      val scope = rememberCoroutineScope()
      var follow by remember { mutableStateOf(Follow.Off) }
      // The compass was tapped while following: level the map on the way back onto me.
      var level by remember { mutableStateOf(false) }
      val me = rememberMyLocation()
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      ) {
        // Style content (layers), unlike MaplibreMap's trailing lambda, which only holds overlays.
        // ux-v2 §3.8, declared bottom to top (周边路网 sits in the base style, under all of these).
        // 尾迹 all in 队友紫 (ux-v3 §2.4); the one picked in the 成员列表 bold, on top.
        if (trails) for (m in mates.filter { it.sharing }.sortedBy { it.id == highlightedMate }) key(m.id) {
          CasedLine(
            "trail-${m.id}", remember(m.trail) { displayLine(listOf(m.trail.map { TrackPoint(it.timeS * 1000, it.lat, it.lon, null) })) },
            semantic.teammate, if (m.id == highlightedMate) LINE_WIDTH else OVERLAY_WIDTH,
          )
        }
        // The 参考轨迹 is drawn once, as itself; the one open in 轨迹详情 comes bold on top of the rest.
        for ((id, color) in overlays) if (id != referenceTrack && id != detailTrack) key(id) {
          overlayLines[id]?.let { CasedLine("overlay-$id", it.second, semantic.overlay(color), OVERLAY_WIDTH) }
        }
        val detailLine = detailWalked?.takeIf { detailTrack != referenceTrack }?.let { segments -> remember(segments) { displayLine(segments) } }
        val detailColor = overlays[detailTrack]?.let { semantic.overlay(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
        detailLine?.let { CasedLine("detail-track", it, detailColor, LINE_WIDTH) }
        val referenceLine = referenceWalked?.let { segments -> remember(segments) { displayLine(segments) } }
        referenceLine?.let { CasedLine("reference-track", it, semantic.reference, LINE_WIDTH) }
        if (recordingLine.isNotEmpty()) CasedLine("recording-track", remember(recordingLine) { displayLine(recordingLine) }, semantic.recording, LINE_WIDTH)
        // 里程标注 over the recording line too, so they stay readable.
        if (referenceWalked != null && referenceLine != null) KmMarkLayers("reference", referenceWalked, referenceLine, semantic.reference, markZoom)
        // mvp §2.5: 轨迹详情's preview (the map above it) has them too.
        if (detailWalked != null && detailLine != null) KmMarkLayers("detail", detailWalked, detailLine, detailColor, markZoom)
        val from = measureFrom
        val to = measureTo
        if (from != null && to != null) {
          val line = "{\"type\":\"LineString\",\"coordinates\":[[${from.longitude},${from.latitude}],[${to.longitude},${to.latitude}]]}"
          CasedLine("measure", line, MaterialTheme.colorScheme.onSurface, 2.dp)
        }
        // 标注 as a symbol layer: MapLibre's collision placement thins them out as you zoom out, and they
        // don't swallow map gestures the way per-标注 composables did.
        SymbolLayer(
          id = "waypoints",
          source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(shownWaypoints) { waypointFeatures(shownWaypoints) })),
          iconImage = image(waypointDot, DpSize(10.dp, 10.dp)),
          textField = format(span(feature["name"].asString())),
          textFont = const(listOf("Noto Sans Regular")),
          textSize = const(12.sp),
          textAnchor = const(SymbolAnchor.Top),
          textOffset = textOffset(0.dp, 7.dp),
          textColor = const(MaterialTheme.colorScheme.onSurface),
          textHaloColor = const(stroke),
          textHaloWidth = const(1.dp),
          textOptional = const(true),
          onClick = { features ->
            val id = features.firstOrNull()?.properties?.get("id")?.jsonPrimitive?.long
            waypoints.firstOrNull { it.id == id }?.let(::openWaypoint)
            ClickResult.Consume
          },
        )
        // §3.2: planning with a 参考轨迹, a poor fix greys the dot with the bar's numbers (same fix as the bar).
        val greyDot = recording == null && referenceTrack != null && me.freshFix()?.let { poorFix(it.horizontalAccuracy?.inMeters) } == true
        val meColor = if (greyDot) MaterialTheme.colorScheme.onSurfaceVariant else semantic.me
        LocationIndicatorLayer(
          id = "me", locationState = me,
          topImage = image(remember(meColor, stroke) { MeDotPainter(meColor, stroke) }, DpSize(22.dp, 22.dp)),
          bearingImage = image(if (me.lastHeading == null) NoPainter else remember(meColor) { MeBeamPainter(meColor) }, DpSize(96.dp, 96.dp)),
        )
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
      LaunchedEffect(state) { snapshotFlow { (state.cameraPosition.zoom * 4).roundToInt() / 4.0 }.collect { markZoom = it } }
      LaunchedEffect(state) {
        // ponytail: China's bbox, as for 坐标纠偏 and the server's offline area; a China outline if border areas look wrong.
        snapshotFlow { state.cameraPosition.target.let { outOfChina(it.latitude, it.longitude) } }.collect { overseas = it }
      }
      LaunchedEffect(overseas, dark) {
        if (overseas && dark !in openFreeMap) thread {
          openFreeMapStyle(File(filesDir, if (dark) "openfreemap-dark.json" else "openfreemap-liberty.json"), openFreeMapUrl(dark))
            ?.let { runOnUiThread { openFreeMap += dark to it } }
        }
      }
      var offlinePage by remember { mutableStateOf(false) }
      // 天气 (§2.9): the page and where it's for (null: closed).
      var weatherPlace by remember { mutableStateOf<WeatherPlace?>(null) }
      val weatherPoint = weatherPlace as? WeatherPlace.Point
      var pointWeather by remember { mutableStateOf<PlaceWeather?>(null) }
      var pointLoading by remember { mutableStateOf(false) }
      LaunchedEffect(weatherPoint) {
        pointWeather = null
        val at = weatherPoint ?: return@LaunchedEffect
        pointLoading = true
        pointWeather = withContext(Dispatchers.IO) { runCatching { fetchWeather(api, at.lat, at.lon, null) }.getOrNull() }
        pointLoading = false
      }
      // 沿途天气 (ADR 0010): the track open in 轨迹详情 as walked, its spots and each one's forecast.
      // ponytail: not cached, like a long-pressed point's; keep it in a file if people check before losing signal.
      val weatherWalked = detailWalked.takeIf { (weatherPlace as? WeatherPlace.Track)?.id == detailTrack }
      val weatherStats = weatherWalked?.let { remember(it) { trackStats(it) } }
      val spots = remember(weatherWalked) { weatherWalked?.let { trackSpots(it) }.orEmpty() }
      var spot by remember { mutableIntStateOf(0) }
      var spotWeather by remember { mutableStateOf(listOf<PlaceWeather?>()) }
      var spotsLoading by remember { mutableStateOf(false) }
      LaunchedEffect(spots) {
        spot = 0
        spotWeather = emptyList()
        if (spots.isEmpty()) return@LaunchedEffect
        spotsLoading = true
        spotWeather = withContext(Dispatchers.IO) {
          spots.map { s -> async { runCatching { fetchWeather(api, s.point.lat, s.point.lon, s.point.ele) }.getOrNull() } }.awaitAll()
        }
        spotsLoading = false
      }
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
            val why = when ((e as? OfflineError)?.code) { "offline" -> "没有网络"; else -> "在线搜索没成功，再搜一次" }
            searchNote = if (local.isEmpty()) "没有找到（$why）" else "仅离线结果（$why）"
          }
      }
      val files = remember(filesVersion) {
        listOf(dir, importsDir).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile && it.extension.lowercase() in importableExtensions }
      }
      val packages = remember(filesVersion) { packages() }
      LaunchedEffect(offlinePage, detailTrack != null) {
        // No tag when offline: 可更新 is a hint, never an error (§2.3).
        if (offlinePage || detailTrack != null) thread { runCatching { quietApi.dataVersion() }.onSuccess { runOnUiThread { dataVersion = it } } }
      }
      val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
      val pickTrackFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importTrackFile) }
      // The whole window, as the map and the drawer have it (screenHeightDp leaves out the system bars).
      val window = LocalWindowInfo.current.containerSize.let { with(LocalDensity.current) { it.width.toDp().value.toDouble() to it.height.toDp().value.toDouble() } }
      // ux-v2 §5: [points] in view over 400 ms, between 轨迹详情's top bar and its 窄条 (+ a margin).
      suspend fun fitTrack(points: List<Position>) {
        if (points.isEmpty()) return
        follow = Follow.Off
        val (at, zoom) = fitCamera(
          points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude },
          window.first, window.second, 40.0, 150.0, 40.0, TrackPeekHeight.value + 80.0,
        )
        state.moveCamera(this@MainActivity, CameraPosition(target = at, zoom = zoom), Motion.FOCUS)
      }
      LaunchedEffect(detailTrack) { fitTrack(detailSegments?.flatten().orEmpty().map { Position(longitude = it.lon, latitude = it.lat) }) }
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
        // Its 小抽屉 belong to 活动状态; the 参考轨迹抽屉 and 在轨迹上选 to 规划状态.
        if (recording == null) { shareSheet = false; moreSheet = false } else { referenceDrawer = false; endStartPick() }
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
                  // 在轨迹上选 (ux-v2 §4.3): a tap on the 参考轨迹 is its new 起点; one beside it waits for another.
                  if (startPick != null && hint === startPick) {
                    val at = e.position ?: return@onEvent ClickResult.Consume
                    val on = referenceSegments?.let { alongTrack(at.latitude, at.longitude, it) }
                    if (on != null && on.offM <= tapRadiusM(at.latitude, state.cameraPosition.zoom)) {
                      referenceTrack?.let { saveTrackStart(it, referenceStart.copy(startM = on.nearestM)) }
                      endStartPick()
                      hint = Hint("已换起点")
                    }
                    return@onEvent ClickResult.Consume
                  }
                  pressed = null
                  chatPin = null
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
                        val cached = !online && state.queryRenderedFeatures(DpRect(x - 24.dp, y - 24.dp, x + 24.dp, y + 24.dp), setOf("nearby-public")).isNotEmpty()
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
          for (at in listOfNotNull(pressed, measureFrom, measureTo, chatPin)) Box(Modifier.placedAt(at).size(10.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
          for (m in mates) key(m.id) {
            val last = m.trail.last()
            val at = Position(longitude = last.lon, latitude = last.lat)
            TeammateDot(m, last.battery, Modifier.placedAt(at).clickable {
              mateSheet = m.id; pressed = null; shareSheet = false; moreSheet = false; layers = false; nearbyTracks = emptyList()
            })
          }
        }
        // §2.2: the 惯用手 side; the top bar and 底栏 don't mirror.
        val handed = if (leftHanded) Alignment.Start else Alignment.End
        // §2.1: recording (paused too) is 活动状态; only the controls over the map change, with a fade.
        val active = recording != null
        val liveTeam = team?.takeIf { !it.ended }
        val sharing = liveTeam?.let { t -> t.members.firstOrNull { it.id == t.me }?.sharing } == true
        val teamLabel = teamButton(team)
        val teamUnread = team?.let { unread(it, readSeq).isNotEmpty() } == true
        // Re-read every 30 s ([now]), so a fix going stale shows as none.
        val fix = remember(now, me.lastLocation) { me.freshFix() }
        // A teammate's place on the 队伍轨迹, against mine from a fresh fix only (none: no 领先 / 落后); once per position.
        val mineOnTeamTrack = teamTrackPoints?.let { w -> fix?.position?.let { p -> remember(p, w) { alongTrack(p.latitude, p.longitude, w).atM } } }
        val mateAlong: ((TeamPosition) -> String)? = teamTrackPoints?.let { w ->
          remember(w, mineOnTeamTrack) {
            val seen = HashMap<TeamPosition, String>()
            ({ p: TeamPosition -> seen.getOrPut(p) { mateAlongText(alongTrack(p.lat, p.lon, w).atM, mineOnTeamTrack) } })
          }
        }
        val referenceAt = referenceWalked?.let { w -> fix?.let { f -> remember(f, w) { alongTrack(f.position.latitude, f.position.longitude, w) } } }
        // 轨迹详情's 我的位置, on whichever track is open, 参考 or not.
        val detailAt = detailWalked?.let { w -> fix?.let { f -> remember(f, w) { alongTrack(f.position.latitude, f.position.longitude, w) } } }
        // §2.9: the weather where I am, again once the hour turns or I've moved some 5 km (0.05°), or back online.
        val hereCell = fix?.position?.let { (it.latitude * 20).roundToInt() to (it.longitude * 20).roundToInt() }
        LaunchedEffect(hereCell, now / 3_600_000, online) {
          val at = fix?.position ?: return@LaunchedEffect
          withContext(Dispatchers.IO) { runCatching { fetchWeather(quietApi, at.latitude, at.longitude, at.altitude, hereWeatherFile) }.getOrNull() }?.let { hereWeather = it }
        }
        val batteryNow = remember(now) { battery() }
        fun openLayers() { layers = !layers; pressed = null; nearbyTracks = emptyList(); shareSheet = false; moreSheet = false; mateSheet = null }
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
            "确认" to { state.cameraPosition.target.let { saveWaypointHere(System.currentTimeMillis(), it.latitude, it.longitude, null) } },
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
              WaypointStep.Ask -> hint = Hint("定位信号弱", listOfNotNull(
                at?.let { "就用这里" to { saveWaypointHere(it) } },
                "在地图上选" to ::pickOnMap,
                "取消" to {},
              ), sticky = true)
              WaypointStep.Wait -> { delay(1_000); continue }
            }
            break
          }
          waypointWait = null
        }
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
          // §3.4: 沿轨 on the first page, 剩余 on the second, with a 参考轨迹.
          val offTrack by RecordingService.offTrack.collectAsState()
          val along = referenceStats?.let { stats ->
            AlongNow(referenceAt?.atM.orEmpty(), stats.distanceM, offM = referenceAt?.offM, accuracyM = fix?.horizontalAccuracy?.inMeters, alert = offTrack)
          }
          StateFade(active) { ActiveTopData(activePages(live, fix?.position?.altitude, batteryNow, along)) }
          Column(Modifier.fillMaxWidth().then(if (active) Modifier else Modifier.statusBarsPadding()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StateFade(!active) {
              val shown = detailTrack?.let { id -> detail?.let { id to it.first } }
              if (shown != null) TrackTopBar(
                shown.second, remember(shown.first) { TrackDb(this@MainActivity).use { it.source(shown.first) } },
                onClose = { detailTrack = null }, onWeather = { weatherPlace = WeatherPlace.Track(shown.first) },
              )
              else TopBar(onSearch = { searching = true }, onLayers = ::openLayers) {
                val warn = hereWeather?.let { w -> remember(w, now) { alerts(w, now, now + 12 * 3_600_000L).isNotEmpty() } } == true
                WeatherChip(hereWeather, warn, now) { weatherPlace = WeatherPlace.Here }
              }
            }
            // §3.2: under the top bar while planning, over the 状态条.
            if (!active) referenceStats?.let { stats ->
              ReferenceBar(referenceBarText(referenceAt, fix?.horizontalAccuracy?.inMeters, stats.distanceM, referenceStart.reversed), onClick = { referenceDrawer = true })
            }
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
              MarkButton(waypointWait != null, ::mark)
            }
          }
          // 轨迹详情's 窄条 takes the 底栏's place; 定位 and 标注 stay above it.
          if (detailTrack != null && !active) Spacer(Modifier.navigationBarsPadding().height(TrackPeekHeight))
          StateFade(!active && detailTrack == null) {
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
              // §3.3: 图层 / + / − / 定位 on the 惯用手 side, 队伍 / 分享位置 / 更多 across from them.
              Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                val sideButtons: @Composable () -> Unit = {
                  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SideButton(R.drawable.group_wght500_24px, teamLabel, { shareSheet = false; moreSheet = false; openTeam() }, dot = teamUnread, badge = teamLabel.substringAfter(' ', "").ifEmpty { null })
                    SideButton(R.drawable.share_location_wght500_24px, "分享位置", { shareSheet = !shareSheet; moreSheet = false; layers = false; pressed = null })
                    SideButton(R.drawable.menu_wght500_24px, "更多", { moreSheet = !moreSheet; shareSheet = false; layers = false; pressed = null })
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
                if (leftHanded) { mapButtons(); sideButtons() } else { sideButtons(); mapButtons() }
              }
              ActiveKeys(
                paused = paused,
                leftHanded = leftHanded,
                onPause = { recordingAction("pause"); buzz() },
                onResume = { recordingAction("resume") },
                onEndTooShort = { hint = Hint("按住 1 秒结束记录") },
                onEnd = {
                  // Not stopService: the service carries on for the team. The hold already buzzed.
                  recordingAction("stop")
                  detailTrack = recording
                  hint = Hint("已保存 · " + distanceText(live?.distanceM ?: 0.0))
                },
                markWaiting = waypointWait != null,
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
        // ux-v2 §4.1: one drawer at a time. 轨迹详情 replaces the one open, and the next one opened replaces it, but for
        // the long-press card: it opens over the track's 窄条 and the track stays.
        fun otherDrawer() = layers || shareSheet || moreSheet || nearbyTracks.isNotEmpty() || mateSheet != null || referenceDrawer || teamPage
        LaunchedEffect(detailTrack) { exportSheet = false; if (detailTrack != null) { pressed = null; layers = false; shareSheet = false; moreSheet = false; nearbyTracks = emptyList(); mateSheet = null; referenceDrawer = false } }
        // The 参考轨迹抽屉 replaces the one open, and any opened after it (from search, a notification…) replaces it.
        // Opened, the 队伍页 closes every drawer and the 群聊's pin under it.
        LaunchedEffect(teamPage) { if (teamPage) { pressed = null; layers = false; shareSheet = false; moreSheet = false; nearbyTracks = emptyList(); mateSheet = null; chatPin = null } }
        fun notReference() = pressed != null || layers || shareSheet || moreSheet || nearbyTracks.isNotEmpty() || mateSheet != null || teamPage
        LaunchedEffect(referenceDrawer) { if (referenceDrawer) { pressed = null; layers = false; shareSheet = false; moreSheet = false; nearbyTracks = emptyList(); mateSheet = null } }
        LaunchedEffect(notReference()) { if (notReference()) referenceDrawer = false }
        // Read again inside: when both open at once, 轨迹详情 (just closed the other above) stays.
        LaunchedEffect(otherDrawer()) { if (otherDrawer()) detailTrack = null }
        val chatShown = chatShown(team)
        LaunchedEffect(chatShown) { ChatAlerts.open = chatShown }
        // On screen: everything in it is read. After 结束行程 there's no socket, so the open 队伍页 asks every 10 s.
        LaunchedEffect(chatShown, team?.messages?.lastOrNull()?.seq) {
          val last = team?.messages?.lastOrNull()?.seq ?: return@LaunchedEffect
          if (!chatShown) return@LaunchedEffect
          ChatAlerts.seen(this@MainActivity)
          if (last > readSeq) {
            readSeq = last
            prefs.edit().putLong(PREF_TEAM_READ, last).apply()
          }
        }
        LaunchedEffect(teamPage, team?.id, team?.ended, resumes) {
          val t = team ?: return@LaunchedEffect
          if (!t.ended) return@LaunchedEffect
          catchUp(t.id)
          while (teamPage) {
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
            lastSync = remember(accountPage, pulled) { prefs.getLong(PREF_SYNC_LAST, 0L).takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it)) } },
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
        if (referenceDrawer && !active && referenceSegments != null && referenceStats != null) {
          BackHandler { referenceDrawer = false }
          ReferenceDrawer(
            name = remember(referenceTrack) { TrackDb(this@MainActivity).use { db -> referenceTrack?.let(db::trackName).orEmpty() } },
            stats = referenceStats,
            atM = referenceAt?.atM.orEmpty(),
            start = referenceStart,
            loop = remember(referenceSegments) { isLoop(referenceSegments) },
            onStart = { start -> referenceTrack?.let { saveTrackStart(it, start) } },
            onPickStart = {
              referenceDrawer = false
              hint = Hint("点一下轨迹上的位置作为新起点", listOf("取消" to {}), sticky = true).also { startPick = it }
            },
            onStop = { setReference(null) },
            onClose = { referenceDrawer = false },
          )
        }
        mateSheet?.let { id ->
          val m = mates.firstOrNull { it.id == id } ?: return@let
          BackHandler { mateSheet = null }
          MateSheet(m, now, here, mateAlong, Modifier.align(Alignment.BottomCenter))
        }
        if (teamPage) {
          val t = team
          // A logout meanwhile reads as an expired login.
          fun acct() = account ?: throw OfflineError("unauthorized")
          when {
            t == null || teamJoin -> {
              BackHandler { if (t != null) teamJoin = false else teamPage = false }
              TeamJoinScreen(
                teamName,
                onName = { teamName = it; prefs.edit().putString(PREF_TEAM_NAME, it).apply() },
                busy = teamBusy, note = teamNote,
                onCreate = { joinTeam(null) },
                onJoin = ::joinTeam,
              )
            }
            teamInfo -> {
              BackHandler { teamInfo = false }
              TeamInfoScreen(
                t, now, here, mateAlong, teamSaver,
                onSharing = ::setSharing,
                onSaver = { teamSaver = !teamSaver; prefs.edit().putBoolean(PREF_TEAM_SAVER, teamSaver).apply() },
                leave = { api.leaveTeam(acct(), t.id) },
                end = { api.endTeam(acct(), t.id) },
                onLeft = { quitTeam(); teamInfo = false },
                onNewTeam = { teamInfo = false; teamJoin = true },
                tracks = remember(tracksVersion) { TrackDb(this@MainActivity).use { it.tracks() } },
                giveTrack = { giveTeamTrack(t.id, it) },
                dropTrack = { api.deleteTeamTrack(acct(), t.id) },
                onBack = { teamInfo = false },
              )
            }
            else -> {
              BackHandler { teamPage = false }
              ChatScreen(
                t, here,
                loadImage = { id, thumb -> loadImage(t.id, id, thumb) },
                draft = chatDraft,
                onDraft = { chatDraft = it },
                onSend = { text ->
                  val json = messageJson("text", text = text)
                  // Back in the box unless something new was typed meanwhile (#70).
                  sendMessage(json, onFail = { if (chatDraft.isEmpty()) chatDraft = text; messageFailed(json, it) })
                },
                onLocation = ::sendLocation,
                onPhoto = { pickChatPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                // Back on the map, centred on it.
                onFocus = { lat, lon ->
                  teamPage = false
                  val at = Position(longitude = lon, latitude = lat)
                  chatPin = at
                  moveTo(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 14.0)), Motion.FOCUS)
                },
                onInfo = { teamInfo = true },
                onClose = { teamPage = false },
              )
            }
          }
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
            groups = groups,
            // A track's 标注 are with the track (on the map when it's drawn), not in this list; a group's in the group.
            waypoints = remember(waypoints) { waypoints.filter { it.trackId == null && it.groupId == null } },
            importing = importingTrack,
            onOpen = { detailTrack = it; trackPage = false },
            overlays = overlays,
            onOverlay = ::toggleOverlay,
            onClearOverlays = { saveOverlays(emptyMap()) },
            onWaypoint = ::openWaypoint,
            onGroup = { openGroup = it },
            onGroupShown = { g -> TrackDb(this@MainActivity).use { it.setGroupShown(g.id, !g.shown) }; waypointsVersion++ },
            onWaypointShown = { w -> TrackDb(this@MainActivity).use { it.setWaypointShown(w.id, !w.shown) }; waypointsVersion++ },
            onNewGroup = { addGroup(it) != null },
            // Track files often arrive with no or a generic MIME type; the content decides the format.
            onImport = { pickTrackFile.launch(arrayOf("*/*")) },
          )
        }
        openGroup?.let { gid ->
          // Gone once deleted, here or on another phone.
          val g = groups.firstOrNull { it.id == gid } ?: return@let
          BackHandler { openGroup = null }
          WaypointGroupScreen(
            g, remember(waypoints) { waypoints.filter { it.groupId == gid } },
            onWaypoint = ::openWaypoint,
            onRename = { n -> TrackDb(this@MainActivity).use { it.renameGroup(gid, n) }.also { ok -> if (ok) waypointsVersion++ else hint = Hint(GROUP_NAME_TAKEN) } },
            onDelete = { TrackDb(this@MainActivity).use { it.deleteGroup(gid) }; openGroup = null; waypointsVersion++ },
          )
        }
        val id = detailTrack
        if (id != null && detail != null) {
          BackHandler { detailTrack = null }
          val (name, datum, segments) = detail
          val request = remember(segments) { trackRequest(segments) }
          // Under any 坐标纠偏 it's this track's package: the 2 km corridor dwarfs the shift (#112).
          val requests = remember(id) { TrackDb(this@MainActivity).use { db -> Datum.entries.map { trackRequest(db.segments(id, it)) } } }
          val pkg = packages.firstOrNull { it.request in requests }
          val downloadingThis = downloadRequest in requests
          TrackDetailScreen(
            name,
            planned = remember(id) { TrackDb(this@MainActivity).use { it.planned(id) } },
            stats = remember(segments) { trackStats(segments) },
            // The profile as walked from its 起算点; the numbers are the track's own.
            profile = remember(detailWalked) { detailWalked?.let { trackStats(it).profile }.orEmpty() },
            reversed = detailStart.reversed,
            onReversed = { r -> saveTrackStart(id, detailStart.copy(reversed = r)) },
            color = if (id == referenceTrack) semantic.reference else overlays[id]?.let { semantic.overlay(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            dateMs = segments.firstOrNull { it.isNotEmpty() }?.first()?.timeMs?.takeIf { it > 0 },
            here = detailAt,
            onHere = { if (me.lastLocation != null) follow = Follow.On },
            datum = datum,
            reference = id == referenceTrack,
            overlaid = id in overlays,
            public = remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.isPublic(id) } },
            synced = remember(id, pulled) { TrackDb(this@MainActivity).use { it.synced(id) } },
            recording = id == recording,
            teamTrack = team?.let { isTeamTrack(it, id) } == true,
            corridor = corridorText(pkg, dataVersion, downloadPercent.takeIf { downloadingThis }, busy = downloading && !downloadingThis),
            onDownload = {
              dueBattery()
              downloadPackage("沿轨迹 $name", request, old = pkg)
            }.takeIf { !downloading && (pkg == null || dataVersion != null && pkg.version != dataVersion) },
            batteryRow = batteryDue && !batterySet,
            onBattery = { batteryGuide = true },
            onReference = { setReference(if (id == referenceTrack) null else id) },
            onOverlay = { toggleOverlay(id) },
            onPublic = { togglePublic(id); datumVersion++ },
            onDatum = { d -> TrackDb(this@MainActivity).use { it.setDatum(id, d) }; datumVersion++; waypointsVersion++ },
            onRename = { n -> TrackDb(this@MainActivity).use { it.setName(id, n) }; datumVersion++; tracksVersion++ },
            onExport = { exportSheet = true },
            onDelete = { deleteTrack(id) },
            onDeleteRefused = { hint = Hint("这是队伍轨迹，先换一条或结束行程") },
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
        weatherPlace?.let { place ->
          val close = { weatherPlace = null }
          BackHandler(onBack = close)
          when (place) {
            WeatherPlace.Here -> WeatherScreen("我的位置", hereWeather, loading = fix != null && online, now, close)
            is WeatherPlace.Point -> WeatherScreen(coordinateText(place.lat, place.lon), pointWeather, pointLoading, now, close)
            is WeatherPlace.Track -> {
              // Each spot's days, so the pins and choices follow the day picked.
              val days = remember(spotWeather, now / 3_600_000) { spotWeather.map { w -> w?.let { weatherDays(it, now, TimeZone.getDefault()) } } }
              WeatherScreen(
                detail?.first.orEmpty(), spotWeather.getOrNull(spot), spotsLoading, now, close,
                subtitle = spots.getOrNull(spot)?.let { "${it.label}，${spotText(it)}" },
                above = { day ->
                  val picked = days.map { it?.getOrNull(day) }
                  TrackSpots(
                    weatherStats?.profile.orEmpty(), weatherStats?.distanceM ?: 0.0, spots,
                    picked.map { d -> d?.let { "${it.high}°/${it.low}°" } }, picked.map { it?.stormy == true }, spot,
                  ) { spot = it }
                },
              )
            }
          }
        }
        pressed?.let { at ->
          BackHandler { pressed = null }
          PointCard(
            at.latitude, at.longitude,
            onWaypoint = { pressed = null; openWaypoint(addWaypoint(System.currentTimeMillis(), at.latitude, at.longitude, null)) },
            onMeasure = { pressed = null; measureFrom = at; measureTo = null },
            onWeather = { pressed = null; weatherPlace = WeatherPlace.Point(at.latitude, at.longitude) },
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
            groups = groups.takeIf { w.trackId == null },
            onGroup = { g -> moveWaypoint(w, g) },
            onNewGroup = { n -> addGroup(n)?.let { moveWaypoint(w, it) } != null },
            onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDelete = { deleteWaypoint(w); editing = null },
            onDownload = { saveWaypoint(w); editing = null; downloadNearby(w.lat, w.lon, editName.trim().ifEmpty { null }) },
            onDone = { saveWaypoint(w); editing = null },
          )
        }
        // Back is 取消 on a 提示条 waiting for an answer.
        if (hint?.sticky == true) BackHandler { hint = null }
        if (hint?.pick == true) Crosshair(Modifier.align(Alignment.Center))
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
        if (ClientOutdated.prompt.collectAsState().value) {
          UpgradePrompt(onUpgrade = { ClientOutdated.prompt.value = false; aboutPage = true }, onDismiss = { ClientOutdated.prompt.value = false })
        }
      }
    } }
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

  /** A 对话 notification was tapped. */
  private fun openedChat(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_CHAT, false) != true) return
    intent.removeExtra(EXTRA_CHAT)
    if (RecordingService.team.value != null) openTeam()
    ChatAlerts.seen(this)
  }

  /** The 群聊 of [t] is on screen. */
  private fun chatShown(t: Team?) = teamPage && !teamInfo && !teamJoin && t != null

  override fun onResume() {
    super.onResume()
    ChatAlerts.open = chatShown(RecordingService.team.value)
    resumes++
    // Back from the battery settings the 出发前 row may be done with.
    batterySet = prefs.getBoolean(PREF_BATTERY_SET, false) || getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
  }

  override fun onDestroy() {
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

  /** Imports tracks [selected] of [file]; its 标注 go with the first one (or into a new 标注组 if the file has no track). */
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
            // 标注组 named after the file, not what the file calls itself (#121).
            db.importGroup(fileName.substringBeforeLast('.'), waypoints)
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
    saveWaypointHere(fix.measuredAt.toEpochMilliseconds(), fix.position.latitude, fix.position.longitude, fix.position.altitude)

  /** Saves a 标注 where I am (or picked) and offers 撤销 / 补充 (§9.1). */
  private fun saveWaypointHere(timeMs: Long, lat: Double, lon: Double, ele: Double?) {
    // Nothing syncs while the 提示条 can still 撤销 it (§9.1: it never reaches the server).
    CloudSync.hold(this, HINT_LONGEST_MS)
    val w = addWaypoint(timeMs, lat, lon, ele)
    buzz()
    hint = Hint("已标注", listOf("撤销" to { deleteWaypoint(w) }, "补充" to { openWaypoint(w) }))
  }

  /** 新建标注组 (#121); null, with a 提示条, if the name is taken. */
  private fun addGroup(name: String): Long? =
    TrackDb(this).use { it.addGroup(name) }.also { if (it == null) hint = Hint(GROUP_NAME_TAKEN) else waypointsVersion++ }

  private fun moveWaypoint(w: Waypoint, groupId: Long?) {
    TrackDb(this).use { it.setWaypointGroup(w.id, groupId) }
    waypointsVersion++
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
   * 经过这里的轨迹 (§2.8) for a tap at [at]: the 徒步线路 of the pushed data and every package, and the 公开轨迹
   * online, else from the packages' snapshots. Shown once found; nothing near, nothing shown, unless offline
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
      val fetched = runCatching { listOf(api.nearbyTracks(at.latitude, at.longitude, radius)) }.getOrNull()
      val tracks = (fetched ?: read("public-tracks.geojson")).map { NearbyKind.Public to it }
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

  /** 叠加 or 取消叠加 [id], as many as you like (ux-v3 §2.4). */
  private fun toggleOverlay(id: Long) = saveOverlays(if (id in overlays) overlays - id else overlays.overlay(id))

  private fun saveOverlays(m: Map<Long, Int>) {
    overlays = m
    prefs.edit().putString(PREF_OVERLAYS, overlaysText(m)).apply()
  }

  private fun endStartPick() {
    hints = hints.filter { it !== startPick }
    startPick = null
  }

  /** 删除轨迹 (#99): its 标注 (and their photos) and 起算点 go with it; 参考, 叠加 and 轨迹详情 let go of it. */
  private fun deleteTrack(id: Long) {
    val photos = TrackDb(this).use { db -> db.waypoints(id).mapNotNull { it.photo }.also { db.deleteTrack(id) } }
    photos.forEach { File(it).delete() }
    waypointsVersion++
    prefs.edit().remove(PREF_TRACK_REVERSED + id).remove(PREF_TRACK_START + id).apply()
    dropGoneTracks()
  }

  /** Lets go of tracks no longer here (deleted here, or on another phone): as 参考 (its 偏离提醒 too), 叠加, open or saved from 周边. */
  private fun dropGoneTracks() {
    val ids = TrackDb(this).use { db -> db.tracks().map { it.id }.toSet() }
    if (referenceTrack?.let { it !in ids } == true) setReference(null)
    if (!ids.containsAll(overlays.keys)) saveOverlays(overlays.filterKeys { it in ids })
    // The recording isn't in 我的轨迹 until it ends.
    if (detailTrack?.let { it !in ids && it != RecordingService.activeTrack.value } == true) detailTrack = null
    nearbySaved = nearbySaved.filterValues { it in ids }
    tracksVersion++
  }

  // ponytail: a track deleted on another phone leaves its keys behind; prune them as readOverlays does if prefs grow.
  private fun trackStart(id: Long?) =
    id?.let { TrackStart(prefs.getBoolean(PREF_TRACK_REVERSED + it, false), prefs.getFloat(PREF_TRACK_START + it, 0f).toDouble()) } ?: TrackStart()

  /** Keeps track [id]'s 起算点 on this phone (§2.7: the track itself doesn't change, nothing syncs). */
  private fun saveTrackStart(id: Long, start: TrackStart) {
    if (trackStart(id) == start) return
    prefs.edit().putBoolean(PREF_TRACK_REVERSED + id, start.reversed).putFloat(PREF_TRACK_START + id, start.startM.toFloat()).apply()
    startsVersion++
    // §2.11: the 发起人 turning the 队伍轨迹 round (or moving its 起点) does it for the team.
    val t = RecordingService.team.value ?: return
    if (t.initiator == t.me && isTeamTrack(t, id)) thread {
      runCatching { giveTeamTrack(t.id, id) }.onFailure { runOnUiThread { hint = Hint("队伍轨迹的起算点没发出去，" + (teamReason((it as? OfflineError)?.code) ?: "再试一次")) } }
    }
  }

  private fun teamTrackHere() = TeamTrackHere.parse(prefs.getString(PREF_TEAM_TRACK, null))

  /** Track [id] is [t]'s 队伍轨迹 here (my copy, or the 发起人's own), the trip still on. */
  private fun isTeamTrack(t: Team, id: Long) = !t.ended && t.track != null && teamTrackHere()?.let { it.team == t.id && it.track == id } == true

  /**
   * [t]'s 队伍轨迹 as this phone has it (my copy, or the 发起人's own), turned by its 起算点, for everyone's 沿轨里程 in the
   * team (§2.11). None without one, or before a member's copy of this version has come (the old one would read wrong).
   */
  // ponytail: loads and turns the whole track on the main thread, as 轨迹详情 does; go async if long ones jank.
  private fun teamWalked(t: Team?): List<List<TrackPoint>>? {
    val tr = t?.takeIf { !it.ended }?.track ?: return null
    val h = teamTrackHere()?.takeIf { it.team == t.id && (t.initiator == t.me || it.version >= tr.version) } ?: return null
    return TrackDb(this).use { it.segments(h.track) }.takeIf { it.isNotEmpty() }?.let { oriented(it, tr.start) }
  }

  /** My 沿轨里程 on the 队伍轨迹 at (lat, lon), for a location message (§2.11); null without one. */
  private fun teamAlong(lat: Double, lon: Double): List<Double>? = teamWalked(RecordingService.team.value)?.let { alongTrack(lat, lon, it).atM }

  /** 发起人 (§2.11): gives track [id], with its 起算点 here, as [team]'s 队伍轨迹. Blocking. */
  private fun giveTeamTrack(team: Long, id: Long) {
    val acct = account ?: throw OfflineError("unauthorized")
    // segments() is WGS-84 whatever the track's 纠偏, as the snapshot wants.
    val json = TrackDb(this).use { db -> teamTrackJson(db.uuid(id), db.trackName(id), trackStart(id), db.segments(id)) }
    api.putTeamTrack(acct, team, json)
    prefs.edit().putString(PREF_TEAM_TRACK, TeamTrackHere(team, id, 0).text).apply()
  }

  /** My copy of a 队伍轨迹 in 我的轨迹, like an import (§2.11), made once per track ([teamTrackCopyUuid]); its id. Blocking. */
  private fun copyTeamTrack(ref: TeamTrackRef, segments: List<List<TrackPoint>>): Long = TrackDb(this).use { db ->
    val uuid = teamTrackCopyUuid(ref.uuid)
    db.idOf(uuid) ?: db.importTrack(ParsedTrack(ref.name, false, segments), ref.name, emptyList(), System.currentTimeMillis(), uuid = uuid)
  }

  private fun setReference(id: Long?) {
    getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_REFERENCE, id ?: 0L).apply()
    referenceTrack = id
    if (id == null) { referenceDrawer = false; endStartPick() }
    // The service only notices the change on its next fix; don't leave an alert for the old one up until then.
    getSystemService(android.app.NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    // ponytail: alerts ride on the recording service's GPS; a separate follow-only service if people follow without recording.
    if (id != null) {
      hint = Hint("已设为参考 · 偏离 ${OFF_TRACK_M.toInt()} m 会提醒")
      dueBattery()
    }
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

  /** The 队伍页 (ux-v2 §4.4): its 群聊, or 建队 / 加入; a login is only asked for on 建队 or 加入 (ux-v2 §8 路径 5). */
  private fun openTeam() {
    teamNote = null
    teamPage = true
    teamInfo = false
    teamJoin = false
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
          teamPage = true
          teamJoin = false
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
      val t = runCatching { quietApi.team(acct, id, 0L) }
      runOnUiThread {
        if (prefs.getLong(PREF_TEAM, 0L) != id) return@runOnUiThread
        val code = (t.exceptionOrNull() as? OfflineError)?.code
        if (code == "team_not_found" || code == "unauthorized") return@runOnUiThread quitTeam()
        t.getOrNull()?.let { RecordingService.showTeam(it); ChatAlerts.announce(this, it) }
        // Ended while the service was gone: its reports become a track now (§2.11).
        if (t.getOrNull()?.ended == true) RecordingService.endTrip(this, id, recording = RecordingService.activeTrack.value != null)
        if (t.getOrNull()?.ended != true && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startTeam(id)
      }
    }
  }

  /** What team [id] has stored since we last heard, without a socket (the trip has ended). */
  private suspend fun catchUp(id: Long) {
    val acct = account ?: return
    val after = RecordingService.team.value?.takeIf { it.id == id }?.cursor ?: return
    val t = withContext(Dispatchers.IO) { runCatching { quietApi.team(acct, id, after) }.getOrNull() } ?: return
    if (RecordingService.team.value?.id == id) {
      RecordingService.showTeam(t)
      ChatAlerts.announce(this, RecordingService.team.value ?: return)
    }
  }

  /**
   * Sends a [messageJson] to the 队伍对话 off the main thread and shows it; [onFail] gets what the server
   * (or no signal) said, by default a 提示条 with 重试 (ux-v2 §6.5).
   */
  private fun sendMessage(json: String, onSent: () -> Unit = {}, onFail: (String?) -> Unit = { messageFailed(json, it) }) {
    val t = RecordingService.team.value ?: return
    val acct = account ?: return onFail("unauthorized")
    thread {
      val sent = runCatching { api.postMessage(acct, t.id, json) }
      runOnUiThread {
        sent.onSuccess { m ->
          RecordingService.team.value?.takeIf { it.id == t.id }?.let { RecordingService.showTeam(it.copy(messages = listOf(m))) }
          onSent()
        // Not an OfflineError (a 200 we couldn't read): it may have gone out.
        }.onFailure { onFail(if (it is OfflineError) it.code else "unreadable") }
      }
    }
  }

  /** The 提示条 for a message that didn't go out, with 重试. */
  private fun messageFailed(json: String, code: String?) {
    hint = Hint("没发出去，" + (teamReason(code) ?: "再试一次"), listOf("重试" to { sendMessage(json) }))
  }

  private fun sendLocation() {
    val fix = currentFix() ?: return toast("正在定位 · 到开阔处更快")
    sendMessage(messageJson("location", lat = fix.latitude, lon = fix.longitude, along = teamAlong(fix.latitude, fix.longitude)))
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
      runCatching { quietApi.image(acct, team, id, thumb).let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }.getOrNull()
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
    val id = prefs.getLong(PREF_TEAM, 0L)
    prefs.edit().remove(PREF_TEAM).apply()
    // Without the service, the trip's reports become a track here (§2.11); with it, it does that on leaving.
    if (!running && id != 0L) RecordingService.endTrip(this, id, recording = false)
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
    outState.putLong("openGroup", openGroup ?: 0L)
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
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
      .setType(type)
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "分享轨迹"))
  }
}

private const val GROUP_NAME_TAKEN = "已有同名标注组"

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
 * A teammate on the map (ux-v2 §4.5): a 队友紫 dot (ux-v3 §2.4) with their initial, a hollow grey ring once they stopped
 * sharing, either edged in [Semantic.stroke]; below 20% battery a small red badge with it.
 */
@Composable
private fun TeammateDot(m: TeamMember, battery: Int?, modifier: Modifier) {
  // 56 dp to tap, the dot in its middle.
  Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(28.dp).then(
        Modifier.border(2.dp, semantic.stroke, CircleShape).padding(2.dp).then(
          if (m.sharing) Modifier.background(semantic.teammate, CircleShape) else Modifier.border(3.dp, MaterialTheme.colorScheme.outline, CircleShape),
        ),
      ),
      contentAlignment = Alignment.Center,
    ) {
      Text(m.name.take(1), color = if (m.sharing) semantic.stroke else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    if (battery != null && battery < 20 && m.sharing) Text(
      "$battery%",
      Modifier.offset(x = 18.dp, y = (-14).dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
      MaterialTheme.colorScheme.onError,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

/** 记录线, 参考轨迹 and the one open in 轨迹详情 (ux-v3 §2.4); 叠加 and 尾迹 a step thinner. */
private val LINE_WIDTH = 5.dp
private val OVERLAY_WIDTH = 3.dp

/** A line over a [Semantic.stroke] casing, so it shows on any basemap, 卫星 included (ux-v3 §2.2). */
@Composable
private fun CasedLine(id: String, geoJson: String, color: Color, width: Dp) {
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson))
  LineLayer(id = "$id-casing", source = source, color = const(semantic.stroke), width = const(width + 3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))
  LineLayer(id = id, source = source, color = const(color), width = const(width), cap = const(LineCap.Round), join = const(LineJoin.Round))
}

/** A filled circle with an [edge] ([Semantic.stroke]), for symbol-layer icons. */
private class DotPainter(private val color: Color, private val edge: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() {
    drawCircle(edge)
    drawCircle(color, size.minDimension / 2 - 1.5.dp.toPx())
  }
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
