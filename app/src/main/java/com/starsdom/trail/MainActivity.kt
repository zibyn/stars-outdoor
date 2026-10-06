package com.starsdom.trail

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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
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
import org.maplibre.compose.location.LocationAccuracyAuthorization
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
  /** Its 昵称 (#184), kept from the last time the server said; null until it has. */
  private var nickname by mutableStateOf<String?>(null)
  /** Its 头像 id (#185) as the server last said (null: none), one being uploaded or dropped, and a picked one being cropped. */
  private var myAvatar by mutableStateOf<String?>(null)
  private var avatarBusy by mutableStateOf(false)
  private var cropping by mutableStateOf<Uri?>(null)
  private val avatars by lazy { AvatarCache(File(filesDir, "avatars")) { id -> quietApi.avatar(account ?: throw OfflineError("unauthorized"), id) } }
  private var accountPage by mutableStateOf(false)
  /** 同步 on (§2.12), and photos over mobile data too. */
  private var syncOn by mutableStateOf(false)
  private var mobilePhotos by mutableStateOf(false)
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
        val back = up && !online
        online = up
        // §8.4 第 14 条: what waited for the network goes by itself.
        if (back) outbox.filter { it.state == SendState.Queued }.forEach(::attempt)
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
  /** Where the 周边路网 is being looked up, a spinner there after 300 ms (§8.2 第 14 条). */
  private var nearbyAt by mutableStateOf<Position?>(null)
  /** 经过这里的轨迹 saved to 我的轨迹 from the list showing, with their ids. */
  private var nearbySaved by mutableStateOf(mapOf<NearbyTrack, Long>())
  /** Taps looked up; a newer tap's answer replaces an older one still in flight. */
  private var nearbySeq = 0
  /** OpenFreeMap's style JSON, light and dark (ux-v3 §2.8), once fetched. */
  private var openFreeMap by mutableStateOf(mapOf<Boolean, String>())
  /** The package downloading (its request) and how far it got, for 轨迹详情's 沿线离线地图 row. */
  private var downloadRequest by mutableStateOf<String?>(null)
  private val downloading get() = downloadRequest != null
  private var downloadName by mutableStateOf("")
  private var downloadPercent by mutableIntStateOf(0)
  /** Packages and imports deleted while their 撤销 is on offer: hidden from 离线地图, still on the map (§8.6 第 16 条). */
  private var trashedFiles by mutableStateOf(mapOf<File, Long>())
  /** The package whose outline is on the map (§8.6 第 15 条), with its 提示条: once that's closed, the outline goes too. */
  private var outlined by mutableStateOf<Pair<OfflinePackage, Hint>?>(null)
  /** The server's offline data version, once asked; packages from another version show 可更新. */
  private var dataVersion by mutableStateOf<String?>(null)
  private var filesVersion by mutableIntStateOf(0)
  private var importing by mutableStateOf(false)
  /** Unfinished track left by a killed recording, its line on the map until 继续 / 结束 (§8.3 第 18 条). */
  private var unfinishedTrack by mutableStateOf<Long?>(null)
  /** The 出发前检查 小抽屉 open (§8.3 第 5 条). */
  private var preTripSheet by mutableStateOf(false)
  /** ▶ waits for the system location switch, turned on in its settings (§8.3 第 2 条). */
  private var startAfterSwitch = false
  /** ▶ waits for the location permission it asked for, rather than a team or the 出发前检查. */
  private var startAfterGrant = false
  /** Left for the location settings while ▶ waits: only coming back from there answers it. */
  private var leftForSwitch = false
  /** The 出发前检查 asked for location or 通知: refused for good, its settings open instead (C6-16). */
  private var fixAsked = false
  /** The 出发前检查 reminders up after starting, by what they're about: one put right goes by itself (§8.3 第 3 条). */
  private var reminderHints = mapOf<Check, Hint>()
  private var detailTrack by mutableStateOf<Long?>(null)
  /** The 小抽屉 open over 轨迹详情 (§8.2 第 8 条), and the export being written (true: KML). */
  private var detailSheet by mutableStateOf<DetailSheet?>(null)
  private var exporting by mutableStateOf<Boolean?>(null)
  /** The 小抽屉 naming a 标注组: a new one (moving a 标注 into it), or one renamed. */
  private var groupSheet by mutableStateOf<GroupSheet?>(null)
  /** A row lit up for a moment ("t5", "g3", "w7"): just made, or back from 撤销 (§8.5 第 12、15 条). */
  private var highlighted by mutableStateOf<String?>(null)
  private var resumeAfterGrant: Long? = null
  private var waypointsVersion by mutableIntStateOf(0)
  /** 标注 being edited, with its unsaved name and description. */
  private var editing by mutableStateOf<Long?>(null)
  private var editName by mutableStateOf("")
  private var editDescription by mutableStateOf("")
  /** 我的轨迹 drawer (ux-v3 §5.5) open, at full height, on which 页签 (remembered, §8.5 第 4 条). */
  private var trackPage by mutableStateOf(false)
  private var trackFull by mutableStateOf(false)
  private var trackTab by mutableIntStateOf(0)
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
  /** Those from this phone's 地名索引 (C2-07), the online search running, and 重试 (C2-06). */
  private var searchLocal by mutableStateOf(setOf<Place>())
  private var searchBusy by mutableStateOf(false)
  private var searchTries by mutableIntStateOf(0)
  /** 惯用手 (ux-v2 §2.2): left mirrors 定位 and 标注 to the left. */
  private var leftHanded by mutableStateOf(false)
  /** 偏离提醒 threshold in 设置 (§8.6 第 3 条); the recording service reads it as each recording starts. */
  private var offTrackM by mutableIntStateOf(OFF_TRACK_M)
  private val darkPalette by lazy { darkPalette(assets.open("style-dark.tsv").bufferedReader().readText()) }
  private val aliases by lazy { aliasPlaces(assets.open("peak-aliases.tsv").bufferedReader().readText()) }
  /** 队伍 (§2.11): the 队伍页 (ux-v2 §4.4), on its 队伍信息 or, after 结束行程, on 建队 / 加入; 尾迹 shown; 省电模式. */
  private var teamPage by mutableStateOf(false)
  private var teamInfo by mutableStateOf(false)
  private var teamJoin by mutableStateOf(false)
  /** A join (its code; "" for 创建队伍) waiting for the login it asked for, then carried out (ux-v2 §8 路径 5). */
  private var teamAfterLogin: String? = null
  /** Creating ("") or joining (its code) in flight, and what went wrong last. */
  private var teamBusy by mutableStateOf<String?>(null)
  /** 登录 opened by the 队伍页: it says so (C4-18). */
  private var loginForTeam by mutableStateOf(false)
  /** In a team without location allowed (#141): still in it, not sharing, the 对话 caught up without the service. */
  private var teamNoLocation by mutableStateOf(false)
  private var teamNote by mutableStateOf<String?>(null)
  private var teamRetry by mutableStateOf<(() -> Unit)?>(null)
  private var trails by mutableStateOf(true)
  /** The teammate picked in the 成员列表, their 尾迹 bold (ux-v3 §2.4); set from there in V17 (#187). */
  private var highlightedMate by mutableStateOf<Long?>(null)
  private var teamSaver by mutableStateOf(false)
  /** Team to share with once location is granted (0 = none; else it's recording that asked), and asked from 去开启. */
  private var teamAfterGrant = 0L
  private var teamFixAsked = false
  /** 队伍对话 (§2.11): what's typed, and the last message read. */
  // ponytail: gone on rotation or process death, like the open page itself; save it if that bites.
  private var chatDraft by mutableStateOf("")
  /** Mine on their way to the 对话 (§8.4 第 14 条), and since when 📍 has waited for a fix (null: not waiting). */
  // ponytail: in memory, like the draft; lost if the app is killed before they go.
  private var outbox by mutableStateOf(listOf<Outgoing>())
  private var outgoingIds = 0L
  private var locatingSince by mutableStateOf<Long?>(null)
  private var locatingTeam = 0L
  private var oneFix: Location? = null
  private var readSeq by mutableLongStateOf(0L)
  /** Counts onResume, so an ended team's 对话 is caught up each time the app comes back (it has no socket). */
  private var resumes by mutableIntStateOf(0)
  // ponytail: photos in memory only, the last 40; a disk cache if people scroll long chats offline.
  private val images = LruCache<String, ImageBitmap>(40)
  private val pickChatPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }
  private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::attachPhoto) }
  private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> cropping = uri }
  // MBTiles/PMTiles and track files have no registered MIME type: picked as anything, checked after.
  private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
  private val pickTrackFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importTrackFile) }
  private fun pickMapFile() = pickFile.launch(arrayOf("*/*"))
  private fun pickTrack() = pickTrackFile.launch(arrayOf("*/*"))
  /** 一键标注 (§9.1): when it started waiting for a good enough fix (ms); null when not waiting. */
  private var waypointWait by mutableStateOf<Long?>(null)
  /** The 提示条 showing (ux-v3 §6) and the sticky ones waiting behind it ([queueHint]). */
  private var hints by mutableStateOf(listOf<Hint>())
  /** The 标注 just made, while its pin drops (§8.3 第 12 条). */
  private var droppedPin by mutableStateOf<Pair<Long, Position>?>(null)
  /** The 提示条 showing, or none; setting one queues it, null closes it. */
  private var hint: Hint?
    get() = hints.firstOrNull()
    set(next) { hints = queueHint(hints, next) }
  // 标注 asks for location only when tapped.
  private val askMarkPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) waypointWait = System.currentTimeMillis()
    // C1-04: denied for good, the system won't ask again; only its settings can.
    else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
      hint = locationDeniedHint()
    }
  }
  private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
      // §8.4 第 4 条: still in the team, here and on the server, only not sharing; the 对话 says so with 去开启.
      if (teamAfterGrant != 0L) teamWithoutLocation(teamAfterGrant)
      // Refused for good, the system won't ask again; only its settings can.
      if (teamAfterGrant != 0L && teamFixAsked && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings()
    } else if (teamAfterGrant != 0L) startTeam(teamAfterGrant)
    else if (fixAsked && granted[Manifest.permission.ACCESS_FINE_LOCATION] != true && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings()
    fixAsked = false
    if (startAfterGrant) {
      // §8.3 第 2 条: granted, on to the switch and the start; refused (or only approximate), it stays, 去开启 at hand.
      if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) record(resumeAfterGrant)
      else hint = locationDeniedHint()
    }
    startAfterGrant = false
    teamAfterGrant = 0L
    teamFixAsked = false
  }
  // 开定位 and the 定位按钮 (§8.1): given, on to the switch if it's off (no GMS, so its settings); refused, nothing
  // until it's needed again; refused for good, 去开启 (C1-04).
  private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted.values.any { it }) {
      readLocationOn()
      if (!locationOn) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
      hint = locationDeniedHint()
    }
  }
  private fun askLocation() = askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
  // 通知 (#140): asked whatever location says; asked from the 出发前检查 once the system won't ask again, its settings.
  private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (fixAsked && !granted && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
    fixAsked = false
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
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
    // 软删除 whose 撤销 a killed app never saw out: gone for good now, never half-deleted (§8.5 第 15 条).
    if (savedInstanceState == null) TrackDb(this).use { it.purgeTrashed(null) }.forEach(::forgetTrack)
    if (RecordingService.activeTrack.value == null) TrackDb(this).use { it.openTrack() }?.let(::offerRecovery)
    // 强制升级 (#118): asked once a launch; offline, nothing is asked and nothing is locked.
    if (savedInstanceState == null) thread { runCatching { if (api.outdated()) ClientOutdated.prompt.value = true } }
    // 应用内更新 (§2.13): GitHub at most once a day.
    if (savedInstanceState == null) thread { checkForUpdate(prefs) }
    referenceTrack = getSharedPreferences("prefs", MODE_PRIVATE).getLong(PREF_REFERENCE, 0L).takeIf { it != 0L }
    overlays = readOverlays(prefs.getString(PREF_OVERLAYS, null), TrackDb(this).use { db -> db.tracks().map { it.id }.toSet() })
    hereWeather = cachedWeather(hereWeatherFile)
    // Each track's 沿途天气 from before the weather stood on its own (§2.9).
    File(filesDir, "weather").deleteRecursively()
    leftHanded = prefs.getBoolean(PREF_LEFT_HANDED, false)
    offTrackM = prefs.getInt(PREF_OFF_TRACK, OFF_TRACK_M)
    account = accounts.get()
    nickname = prefs.getString(PREF_NICKNAME, null)
    myAvatar = prefs.getString(PREF_AVATAR, null)
    // Another phone may have changed the 昵称 or 头像 since.
    if (savedInstanceState == null) account?.let(::fetchMe)
    syncOn = prefs.getBoolean(PREF_SYNC, false)
    mobilePhotos = prefs.getBoolean(PREF_SYNC_MOBILE_PHOTOS, false)
    // §2.12: syncs on opening the app.
    if (savedInstanceState == null) CloudSync.request(this)
    trails = prefs.getBoolean(PREF_TRAILS, true)
    teamSaver = prefs.getBoolean(PREF_TEAM_SAVER, false)
    // Asked by team once; the account's 昵称 is the name now (#184).
    // ponytail: runs every start; drop it once no install from before #184 is left.
    prefs.edit().remove("team_name").apply()
    readSeq = prefs.getLong(PREF_TEAM_READ, 0L)
    // Back in the team after the app (and its service) was killed: catch up, 未读 included.
    prefs.getLong(PREF_TEAM, 0L).takeIf { it != 0L && RecordingService.team.value == null }?.let { id ->
      if (account == null) { prefs.edit().remove(PREF_TEAM).apply(); RecordingService.endTrip(this, id, recording = RecordingService.activeTrack.value != null) } else resumeTeam(id)
    }
    savedInstanceState?.let {
      startAfterSwitch = it.getBoolean("startAfterSwitch")
      leftForSwitch = startAfterSwitch
      startAfterGrant = it.getBoolean("startAfterGrant")
      resumeAfterGrant = it.getLong("resumeAfterGrant").takeIf { id -> id != 0L }
      // Kept so a photo picked after the activity was recreated still lands on its 标注.
      editing = it.getLong("editing").takeIf { id -> id != 0L }
      editName = it.getString("editName").orEmpty()
      editDescription = it.getString("editDescription").orEmpty()
      trackPage = it.getBoolean("trackPage")
      trackFull = it.getBoolean("trackFull")
      trackTab = it.getInt("trackTab")
      openGroup = it.getLong("openGroup").takeIf { id -> id != 0L }
      detailTrack = it.getLong("detailTrack").takeIf { id -> id != 0L }
    } ?: openedFile(intent)
    // A pull may have deleted the 参考轨迹 (or the open one) since last time.
    dropGoneTracks()
    openedChat(intent)
    // 首次打开 (§8.1): one 介绍 asking for location, until answered either way; nothing to ask if it's given already.
    if (!prefs.getBoolean(PREF_INTRO_ANSWERED, false)) {
      if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) answerIntro()
      else hint = Hint(getString(R.string.hint_intro), listOf(
        getString(R.string.action_later) to ::answerIntro,
        getString(R.string.action_allow_location) to { answerIntro(); askLocation() },
      ), sticky = true)
    }

    setContent { AppTheme { CompositionLocalProvider(LocalAvatars provides avatars) {
      // Rebuilt whenever offline files change, so imports show up and deleted files are released.
      // ponytail: reads each import's header on the main thread; move off-thread if people import dozens.
      val terrain = remember(filesVersion) { style() }
      var layers by remember { mutableStateOf(false) }
      // Tapped 我的位置: 分享坐标 / 标注这里 (ux-v3 §5.4).
      var meSheet by remember { mutableStateOf(false) }
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
            Triple(db.trackName(id), db.datum(id), segments)
          }
        }
      }
      val detailSegments = detail?.third
      // 截取 (#88): the points picked, into detailSegments; null when not trimming.
      var trim by remember(detailTrack) { mutableStateOf<IntRange?>(null) }
      // 合并 (#189): the tracks ticked, in that order; then, past the time check, in the order they go together.
      var mergePicked by remember(detailTrack) { mutableStateOf(emptyList<Long>()) }
      var mergeOrdered by remember(detailTrack) { mutableStateOf(emptyList<TrackSummary>()) }
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
      // Teammates with a position; one who stopped sharing stays as a hollow dot where they were (ux-v2 §4.5); none once
      // the trip has ended (#147).
      val mates = team?.takeIf { !it.ended }?.let { t -> t.members.filter { it.id != t.me && it.trail.isNotEmpty() } }.orEmpty()
      // C4-42: a member sees the trip end on the map at once (the 发起人 is in the 对话, with its card).
      var lastTeam by remember { mutableStateOf<Team?>(null) }
      LaunchedEffect(team) {
        val t = team
        if (t != null && t.ended && lastTeam?.let { it.id == t.id && !it.ended } == true && t.initiator != t.me) hint = Hint(getString(R.string.trip_ended))
        lastTeam = t
      }
      // §8.4 第 15 条: 重新连接中 only once the socket has been down 10 s, so a short gap shows nothing.
      val downSince by RecordingService.downSince.collectAsState()
      var reconnecting by remember { mutableStateOf(false) }
      LaunchedEffect(downSince) {
        reconnecting = false
        val since = downSince ?: return@LaunchedEffect
        delay(since + 10_000 - System.currentTimeMillis())
        reconnecting = true
      }
      // 📍 waiting for a fix (C4-37): sent as soon as there's one, given up on after 60 s.
      LaunchedEffect(locatingSince) {
        val since = locatingSince ?: return@LaunchedEffect
        while (System.currentTimeMillis() - since < 60_000) {
          currentFix()?.let { sendFix(it, locatingTeam); locatingSince = null; return@LaunchedEffect }
          delay(1_000)
        }
        locatingSince = null
        hint = failHint(R.string.result_position_not_sent, R.string.reason_weak_fix)
      }
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
      // What's there (§8.2 第 3 条): the search result picked, else the nearest place in the 地名索引, looked up.
      // Kept with the point it's for, so an answer for another point never shows.
      var pressedPlace by remember { mutableStateOf<Pair<Position, Place?>?>(null) }
      LaunchedEffect(pressed) {
        val at = pressed ?: return@LaunchedEffect
        if (pressedPlace?.first == at) return@LaunchedEffect
        pressedPlace = at to withContext(Dispatchers.IO) { nearestPlace(placesNear(placeFiles(), at.latitude, at.longitude), at.latitude, at.longitude) }
      }
      var measureFrom by remember { mutableStateOf<Position?>(null) }
      var measureTo by remember { mutableStateOf<Position?>(null) }
      // Where a 群聊 location points, until the map is tapped.
      var chatPin by remember { mutableStateOf<Position?>(null) }
      val waypoints = remember(waypointsVersion) { TrackDb(this@MainActivity).use { it.waypoints() } }
      val myTracks = remember(tracksVersion) { TrackDb(this@MainActivity).use { it.tracks() } }
      val trackList = rememberLazyListState()
      // 出发前检查 as the phone is now: read again on coming back (from a system dialog or settings) and as things change.
      // Only while something shows it: reading it isn't free.
      var settingsPage by remember { mutableStateOf(false) }
      var aboutPage by remember { mutableStateOf(false) }
      val update by Updates.available.collectAsState()
      var sourcesPage by remember { mutableStateOf(false) }
      val preTripShown = preTripSheet || settingsPage || detailTrack != null && detailTrack == referenceTrack
      val preTripFailing = remember(preTripShown, resumes, locationOn, filesVersion, referenceTrack, online) { if (preTripShown) failing(phoneState()) else emptySet() }
      // 轨迹详情's height in the drawer, and its 窄条's, which the map's keys stand on.
      var detailStop by rememberSaveable { mutableStateOf(DrawerStop.Peek) }
      var peekHeight by remember { mutableStateOf(TrackPeekHeight) }
      // 我的轨迹's numbers, worked out off the main thread as tracks come; a track's points never change (§2.5).
      // ponytail: a 坐标纠偏 change keeps the old distance, a few metres off; key by datum if anyone notices.
      val trackStatsById = remember { mutableStateMapOf<Long, TrackStats>() }
      LaunchedEffect(myTracks) {
        for (t in myTracks) if (t.id !in trackStatsById) trackStatsById[t.id] = withContext(Dispatchers.IO) { trackStats(TrackDb(this@MainActivity).use { it.segments(t.id) }) }
      }
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
        // §8.1: all of China until there's a fix.
        initialCameraPosition = CameraPosition(target = Position(latitude = 35.0, longitude = 104.0), zoom = 3.5),
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
        // Trimming, the track fades but for the piece picked.
        val faded = if (trim != null) 0.35f else 1f
        detailLine?.let { CasedLine("detail-track", it, detailColor.copy(alpha = faded), LINE_WIDTH) }
        val referenceLine = referenceWalked?.let { segments -> remember(segments) { displayLine(segments) } }
        referenceLine?.let { CasedLine("reference-track", it, semantic.reference.copy(alpha = if (detailTrack == referenceTrack) faded else 1f), LINE_WIDTH) }
        val piece = trim
        if (piece != null && detailSegments != null) CasedLine("trim-piece", remember(detailSegments, piece) { displayLine(trimSegments(detailSegments, piece)) }, MaterialTheme.colorScheme.primary, LINE_WIDTH)
        val unfinishedLine = unfinishedTrack?.let { id -> remember(id) { TrackDb(this@MainActivity).use { it.segments(id) } } }
        recordingLine.ifEmpty { unfinishedLine.orEmpty() }.takeIf { it.isNotEmpty() }?.let { line ->
          CasedLine("recording-track", remember(line) { displayLine(line) }, semantic.recording, LINE_WIDTH)
        }
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
        outlined?.takeIf { it.second in hints }?.let { (pkg) -> CasedLine("package-outline", remember(pkg) { packageOutline(pkg) }, MaterialTheme.colorScheme.primary, OVERLAY_WIDTH) }
        // §3.2: not recording, with a 参考轨迹, a poor fix greys the dot with the bar's numbers (same fix as the bar).
        val greyDot = recording == null && referenceTrack != null && me.freshFix()?.let { poorFix(it.horizontalAccuracy?.inMeters) } == true
        val meColor = if (greyDot) MaterialTheme.colorScheme.onSurfaceVariant else semantic.me
        LocationIndicatorLayer(
          id = "me", locationState = me,
          topImage = image(remember(meColor, stroke) { MeDotPainter(meColor, stroke) }, DpSize(22.dp, 22.dp)),
          bearingImage = image(if (me.lastHeading == null) NoPainter else remember(meColor) { MeBeamPainter(meColor) }, DpSize(96.dp, 96.dp)),
          // Its accuracy: with only 大致位置, all there is (§8.1).
          accuracyRadiusColor = meColor.copy(alpha = 0.15f),
          accuracyRadiusBorderColor = meColor.copy(alpha = 0.4f),
        )
        // 标注 over 我的位置, so one just made shows (#144); not over 「起」「终」.
        // 标注 as a symbol layer: MapLibre's collision placement thins them out as you zoom out, and they
        // don't swallow map gestures the way per-标注 composables did.
        SymbolLayer(
          id = "waypoints",
          source = rememberGeoJsonSource(GeoJsonData.JsonString(
            remember(shownWaypoints, referenceWalked, detailWalked, markZoom) {
              waypointFeatures(clearOfEnds(shownWaypoints, listOfNotNull(referenceWalked, detailWalked), markZoom))
            },
          )),
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
        // §3.5: fly to where I am once there's a fix, unless the camera has moved meanwhile (a drag, a search, 轨迹详情).
        state.awaitViewport() // a move before the map is attached is lost
        val start = state.cameraPosition.target
        val at = snapshotFlow { me.lastLocation?.position }.filterNotNull().first()
        if (state.cameraPosition.target == start) state.moveCamera(this@MainActivity, state.cameraPosition.copy(target = at, zoom = 12.0), Motion.FOCUS)
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
      // Each forecast below: what went wrong last (a server code, §8.2 第 13 条), tried again on 重试 and back online.
      var weatherTries by remember { mutableIntStateOf(0) }
      var hereError by remember { mutableStateOf<String?>(null) }
      fun Throwable.weatherCode() = errorCode ?: "server"
      var pointWeather by remember { mutableStateOf<PlaceWeather?>(null) }
      var pointLoading by remember { mutableStateOf(false) }
      var pointError by remember { mutableStateOf<String?>(null) }
      LaunchedEffect(weatherPoint, weatherTries, online) {
        val at = weatherPoint ?: return@LaunchedEffect run { pointWeather = null }
        if (pointWeather?.let { it.lat == at.lat && it.lon == at.lon } == true) return@LaunchedEffect
        pointWeather = null
        pointError = null
        pointLoading = true
        try {
          withContext(Dispatchers.IO) { runCatching { fetchWeather(api, at.lat, at.lon, null) } }
            .onSuccess { pointWeather = it }.onFailure { pointError = it.weatherCode() }
        } finally {
          pointLoading = false
        }
      }
      // 沿途天气 (ADR 0010): the track open in 轨迹详情 as walked, its spots and each one's forecast.
      // ponytail: not cached, like a long-pressed point's; keep it in a file if people check before losing signal.
      val weatherWalked = detailWalked.takeIf { (weatherPlace as? WeatherPlace.Track)?.id == detailTrack }
      val weatherStats = weatherWalked?.let { remember(it) { trackStats(it) } }
      val spots = remember(weatherWalked) { weatherWalked?.let { trackSpots(it) }.orEmpty() }
      var spot by remember { mutableIntStateOf(0) }
      var spotWeather by remember { mutableStateOf(listOf<PlaceWeather?>()) }
      var spotsLoading by remember { mutableStateOf(false) }
      var spotsError by remember { mutableStateOf<String?>(null) }
      // The spots [spotWeather] is for: new spots start over, 重试 and back online fetch only those still missing.
      var spotsFor by remember { mutableStateOf(listOf<TrackSpot>()) }
      LaunchedEffect(spots, weatherTries, online) {
        if (spotsFor != spots) { spot = 0; spotWeather = spots.map { null }; spotsFor = spots }
        if (spotWeather.all { it != null }) return@LaunchedEffect
        spotsError = null
        spotsLoading = true
        try {
          val before = spotWeather
          val got = withContext(Dispatchers.IO) {
            spots.mapIndexed { i, s -> async { before[i]?.let { Result.success(it) } ?: runCatching { fetchWeather(api, s.point.lat, s.point.lon, s.point.ele) } } }.awaitAll()
          }
          spotWeather = got.map { it.getOrNull() }
          spotsError = got.firstNotNullOfOrNull { it.exceptionOrNull() }?.weatherCode()
        } finally {
          spotsLoading = false
        }
      }
      var searching by remember { mutableStateOf(false) }
      // Where 搜索 counts distances from (§8.2 第 1 条): me, else the map's centre.
      fun searchFrom() = (me.freshFix()?.position ?: state.cameraPosition.target).let { it.latitude to it.longitude }
      LaunchedEffect(searchQuery, searchTries) {
        val q = searchQuery.trim()
        searchNote = null
        searchBusy = false
        parseCoordinate(q)?.let { (lat, lon) ->
          searchResults = listOf(Place(coordinateText(lat, lon), "coordinate", lat, lon, "坐标"))
          return@LaunchedEffect
        }
        if (q.isEmpty()) {
          searchResults = emptyList()
          return@LaunchedEffect
        }
        delay(300) // typing: only the last query runs
        val (lat, lon) = searchFrom()
        // 山名别名表 and the offline 地名索引 (pushed for development, and each package's) first, then online.
        val files = listOf(File(dir, "places.sqlite")) + packages().map { File(it.dir, "places.sqlite") }
        val indexed = withContext(Dispatchers.IO) { searchPlaces(files, q) }
        val local = aliases.filter { it.name.contains(q, ignoreCase = true) } + indexed
        searchLocal = indexed.toSet()
        searchResults = rankPlaces(local, q, lat, lon)
        searchBusy = true
        val found = try { withContext(Dispatchers.IO) { runCatching { api.search(q, lat, lon) } } } finally { searchBusy = false }
        found
          .onSuccess { searchResults = rankPlaces(local + it, q, lat, lon); searchNote = if (searchResults.isEmpty()) getString(R.string.search_none) else null }
          .onFailure { e ->
            // C2-04…06: offline, the 状态条 says so; a failed search is a 提示条, what's here stays.
            if (e.errorCode == "offline") searchNote = if (local.isEmpty()) getString(R.string.reason_offline) else null
            else hint = failHint(R.string.result_search_failed, reasonOf(e.errorCode)) { searchTries++ }
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
      // The whole window, as the map and the drawer have it (screenHeightDp leaves out the system bars).
      val window = LocalWindowInfo.current.containerSize.let { with(LocalDensity.current) { it.width.toDp().value.toDouble() to it.height.toDp().value.toDouble() } }
      // ux-v2 §5: [points] in view over 400 ms, between 轨迹详情's top bar and its 窄条 (+ a margin).
      // [drawerDp]: how much of the window's foot is drawer.
      suspend fun fitTrack(points: List<Position>, drawerDp: Double = peekHeight.value.toDouble(), maxZoom: Double = 16.0) {
        if (points.isEmpty()) return
        follow = Follow.Off
        val (at, zoom) = fitCamera(
          points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude },
          window.first, window.second, 40.0, 150.0, 40.0, drawerDp + 80.0, maxZoom,
        )
        state.moveCamera(this@MainActivity, CameraPosition(target = at, zoom = zoom), Motion.FOCUS)
      }
      LaunchedEffect(detailTrack) { fitTrack(detailSegments?.flatten().orEmpty().map { Position(longitude = it.lon, latitude = it.lat) }) }
      // 离线地图's tapped package (or 离线地图已下载's 查看): back to the map, all of the outline in view.
      LaunchedEffect(outlined) {
        val (w, s, e, n) = outlined?.first?.let { outlineBox(packageOutline(it)) } ?: return@LaunchedEffect
        offlinePage = false
        fitTrack(listOf(Position(longitude = w, latitude = s), Position(longitude = e, latitude = n)))
      }
      // §8.5 第 11 条: editing a 标注, the camera goes to it above the half drawer, no nearer than it was (or street level).
      // Done, the lists catch up with what was typed.
      LaunchedEffect(editing) {
        val id = editing
        if (id == null) { waypointsVersion++; return@LaunchedEffect }
        val w = waypoints.firstOrNull { it.id == id } ?: return@LaunchedEffect
        trackFull = false
        fitTrack(listOf(Position(longitude = w.lon, latitude = w.lat)), window.second / 2, maxOf(state.cameraPosition.zoom, 14.0))
      }
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
        // The 参考轨迹抽屉 and 在轨迹上选 are for before setting off.
        if (recording != null) { referenceDrawer = false; endStartPick() }
      }
      // Re-read every 30 s ([now]), so a fix going stale shows as none.
      val fix = remember(now, me.lastLocation) { me.freshFix() }
      val referenceAt = referenceWalked?.let { w -> fix?.let { f -> remember(f, w) { alongTrack(f.position.latitude, f.position.longitude, w) } } }
      val offTrack by RecordingService.offTrack.collectAsState()
      // ux-v3 §5.3: the 窄条's 参考 and recording; 偏离 only counts while recording.
      val reference = referenceStats?.let { Reference(it.distanceM, referenceAt, it.profile, offTrack && recording != null) }
      val since by RecordingService.since.collectAsState()
      val pausedAt by RecordingService.pausedAt.collectAsState()
      val recordingNow = recorded?.let { (stats, last) -> RecordingNow(stats, last, since, pausedAt) }
      val fixAccuracy = fix?.let { it.horizontalAccuracy?.inMeters ?: Double.POSITIVE_INFINITY }
      fun closeDrawers() {
        pressed = null; layers = false; meSheet = false; nearbyTracks = emptyList(); mateSheet = null
        referenceDrawer = false; detailSheet = null; detailTrack = null; trackPage = false
      }
      // A drawer over the 底栏 keeps the recording's line at its top; a tap there closes it (ADR 0012).
      CompositionLocalProvider(LocalDrawerTop provides recordingNow?.let { DrawerTop(it, reference, fixAccuracy, ::closeDrawers) }) {
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
                        val before = referenceStart
                        referenceTrack?.let { ref ->
                          saveTrackStart(ref, referenceStart.copy(startM = on.nearestM))
                          endStartPick()
                          hint = Hint(getString(R.string.hint_start_changed), listOf(getString(R.string.undo) to { saveTrackStart(ref, before) }))
                        }
                      }
                      return@onEvent ClickResult.Consume
                    }
                    // A tap that closes something only closes it: no 「这里没有路网轨迹」 for it.
                    val closing = pressed != null || mateSheet != null || meSheet || nearbyTracks.isNotEmpty()
                    pressed = null
                    chatPin = null
                    mateSheet = null
                    meSheet = false
                    nearbyTracks = emptyList()
                    // 我的位置 takes a tap out of a team (§5.4): 分享坐标 / 标注这里. In a team, the 对话's 📍 位置 does it.
                    val tapped = e.position
                    val mine = me.lastLocation?.position
                    if (measureFrom == null && tapped != null && mine != null && team?.ended != false &&
                      FloatArray(1).also { Location.distanceBetween(tapped.latitude, tapped.longitude, mine.latitude, mine.longitude, it) }[0] <= tapRadiusM(tapped.latitude, state.cameraPosition.zoom)) {
                      meSheet = true
                      layers = false
                      return@onEvent ClickResult.Consume
                    }
                    if (measureFrom == null) {
                      // Offline, a line drawn from the 地图缓存 can't be listed; the 状态条 already says 没有网络 (C2-118).
                      if (nearby && !closing) e.position?.let { at -> findNearby(at, state.cameraPosition.zoom) }
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
                    meSheet = false
                    pressed = e.position ?: return@onEvent ClickResult.Pass
                    ClickResult.Consume
                  }
                }
              }
            },
          ) {
            for (at in listOfNotNull(pressed, measureFrom, measureTo, chatPin)) Box(Modifier.placedAt(at).size(10.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
            nearbyAt?.let { at -> key(at) { Spinner(Modifier.placedAt(at).size(24.dp), strokeWidth = 3.dp) } }
            // The 标注 just made drops onto its place (§8.3 第 12 条), over 我的位置.
            droppedPin?.let { (n, at) -> key(n) { DroppingPin(Modifier.placedAt(at)) { if (droppedPin?.first == n) droppedPin = null } } }
            for (m in mates) key(m.id) {
              val last = m.trail.last()
              val at = Position(longitude = last.lon, latitude = last.lat)
              TeammateDot(m, last.battery, Modifier.placedAt(at).clickable {
                mateSheet = m.id; pressed = null; meSheet = false; layers = false; nearbyTracks = emptyList()
              })
            }
          }
          // §2.2: the 惯用手 side; the top bar and 底栏 don't mirror.
          val handed = if (leftHanded) Alignment.Start else Alignment.End
          // Recording, paused too: the layout stays, the 底栏's ▶ is ⏸ and a data line shows (ux-v3 §5.1).
          val active = recording != null
          val teamUnread = team?.let { unread(it, readSeq).isNotEmpty() } == true
          // A teammate's place on the 队伍轨迹, against mine from a fresh fix only (none: no 领先 / 落后); once per position.
          val mineOnTeamTrack = teamTrackPoints?.let { w -> fix?.position?.let { p -> remember(p, w) { alongTrack(p.latitude, p.longitude, w).atM } } }
          val mateAlong: ((TeamPosition) -> String)? = teamTrackPoints?.let { w ->
            remember(w, mineOnTeamTrack) {
              val seen = HashMap<TeamPosition, String>()
              ({ p: TeamPosition -> seen.getOrPut(p) { mateAlongText(alongTrack(p.lat, p.lon, w).atM, mineOnTeamTrack) } })
            }
          }
          // 轨迹详情's 我的位置, on whichever track is open, 参考 or not.
          val detailAt = detailWalked?.let { w -> fix?.let { f -> remember(f, w) { alongTrack(f.position.latitude, f.position.longitude, w) } } }
          // §2.9: the weather where I am, again once the hour turns or I've moved some 5 km (0.05°), or back online.
          val hereCell = fix?.position?.let { (it.latitude * 20).roundToInt() to (it.longitude * 20).roundToInt() }
          LaunchedEffect(hereCell, now / 3_600_000, online, weatherTries) {
            val at = fix?.position ?: return@LaunchedEffect
            hereError = null
            withContext(Dispatchers.IO) { runCatching { fetchWeather(quietApi, at.latitude, at.longitude, at.altitude, hereWeatherFile) } }
              .onSuccess { hereWeather = it }.onFailure { hereError = it.weatherCode() }
          }
          val batteryNow = remember(now) { battery() }
          // §8.3 第 21 条: recording, as it drops to 20% (from above it); under 15% not any more.
          var batteryBefore by remember { mutableStateOf(batteryNow) }
          LaunchedEffect(batteryNow) {
            val before = batteryBefore
            batteryBefore = batteryNow
            if (active && before != null && before > 20 && batteryNow != null && batteryNow in 15..20) hint = Hint(getString(R.string.hint_low_battery, batteryNow))
          }
          // 标注 made from the notification (C7-27).
          val marks by RecordingService.marks.collectAsState()
          LaunchedEffect(marks) { if (marks > 0) waypointsVersion++ }
          // §8.3 第 20 条: a new 出行提醒 as it comes, with the app up, as a 提示条 too; the notification goes anyway.
          val risk by RecordingService.risk.collectAsState()
          LaunchedEffect(risk) {
            val (at, alert) = risk ?: return@LaunchedEffect
            if (System.currentTimeMillis() - at < 10_000 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
              hint = Hint(riskHint(alert), listOf(getString(R.string.action_see_weather) to { weatherPlace = WeatherPlace.Here }))
            }
          }
          fun openLayers() { layers = !layers; pressed = null; nearbyTracks = emptyList(); meSheet = false; mateSheet = null }
          fun locate() {
            if (me.lastLocation != null) follow = follow.next
            else {
              // No fix yet: the button stays; 「正在定位」 comes with the 状态条.
              if (me.permission !is LocationPermission.Granted) askLocation()
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
            hint = Hint(getString(R.string.hint_pick_mark), listOf(
              getString(R.string.confirm) to { state.cameraPosition.target.let { saveWaypointHere(System.currentTimeMillis(), it.latitude, it.longitude, null) } },
              getString(R.string.cancel) to {},
            ), sticky = true, pick = true)
          }
          LaunchedEffect(waypointWait) {
            val since = waypointWait ?: return@LaunchedEffect
            while (true) {
              val at = me.freshFix()
              // A fix that doesn't say how good it is doesn't pass.
              when (waypointStep(at?.let { it.horizontalAccuracy?.inMeters ?: Double.POSITIVE_INFINITY }, System.currentTimeMillis() - since)) {
                WaypointStep.Save -> at?.let(::saveWaypointHere)
                WaypointStep.Ask -> hint = Hint(getString(R.string.hint_weak_mark), listOfNotNull(
                  at?.let { getString(R.string.use_here) to { saveWaypointHere(it) } },
                  getString(R.string.pick_on_map) to ::pickOnMap,
                  getString(R.string.cancel) to {},
                ), sticky = true)
                WaypointStep.Wait -> { delay(1_000); continue }
              }
              break
            }
            waypointWait = null
          }
          fun zoom(by: Double) = scope.launch { state.moveCamera(this@MainActivity, state.cameraPosition.let { it.copy(zoom = it.zoom + by) }, Motion.CAMERA) }
          // §3.5: back to north-up and out of 2.5D; 朝向 drops back to 跟随. Top of the 圆键列, so it doesn't move 标注.
          val compass: @Composable (Modifier) -> Unit = { modifier ->
            val camera = state.cameraPosition
            if (camera.tilt != 0.0 || camera.bearing != 0.0) Compass(
              onClick = { if (follow == Follow.Off) moveTo(camera.copy(bearing = 0.0, tilt = 0.0), Motion.CAMERA) else { follow = Follow.On; level = true } },
              modifier = modifier,
            )
          }
          // 顶部 (ux-v3 §5.1), top to bottom; what isn't showing leaves no gap. The same recording or not.
          Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
              TopBar(onSearch = { searching = true }, onLayers = ::openLayers) {
                val warn = hereWeather?.let { w -> remember(w, now) { alerts(w, now, now + 12 * 3_600_000L).isNotEmpty() } } == true
                WeatherChip(hereWeather, warn, now) { weatherPlace = WeatherPlace.Here }
              }
              val syncFailed by CloudSync.failed.collectAsState()
              StatusBar(
                status(StatusInput(
                  recording = active,
                  paused = active && paused,
                  locationOn = locationOn,
                  permitted = me.permission !is LocationPermission.NotGranted,
                  precise = (me.permission as? LocationPermission.Granted)?.accuracy != LocationAccuracyAuthorization.Approximate,
                  fixAccuracyM = fix?.let { it.horizontalAccuracy?.inMeters ?: 0.0 },
                  basemap = basemap,
                  online = online,
                  downloadPercent = downloadPercent.takeIf { downloading },
                  syncFailed = syncFailed && syncOn,
                )),
                onAction = { action ->
                  when (action) {
                    StatusAction.OpenLocation -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    StatusAction.Terrain -> pickBasemap(Basemap.Terrain)
                    StatusAction.RetrySync -> CloudSync.request(this@MainActivity)
                  }
                },
              )
              measureFrom?.let { from ->
                val to = measureTo
                val distance = to?.let { FloatArray(1).also { r -> Location.distanceBetween(from.latitude, from.longitude, it.latitude, it.longitude, r) }[0].toDouble() }
                MeasureBanner(distance, onClose = { measureFrom = null; measureTo = null }, Modifier.fillMaxWidth())
              }
            }
          }
          // 底部 (ux-v3 §5.1), bottom up: 底栏 (or 轨迹详情's 窄条), the recording's data, 暂停小栏, the 提示条's strip, and the
          // 圆键列 on the 惯用手 side above it all. A 提示条 never moves 标注; the 暂停小栏 lifts it a strip (§5.4).
          Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.align(handed).padding(horizontal = Space.M), verticalArrangement = Arrangement.spacedBy(Space.M), horizontalAlignment = handed) {
              compass(Modifier)
              MapIconButton(R.drawable.add_wght500_24px, stringResource(R.string.zoom_in), { zoom(1.0) })
              MapIconButton(R.drawable.remove_wght500_24px, stringResource(R.string.zoom_out), { zoom(-1.0) })
              MarkKey(waypointWait != null, ::mark)
              LocateButton(follow, onClick = ::locate)
            }
            Spacer(Modifier.height(HintStrip))
            val motion = MaterialTheme.motionScheme
            // §3.4: from 暂停's place, scaling up as it fades in; its strip opens on the same spring, so the keys above
            // rise with it rather than jump.
            AnimatedVisibility(
              active && paused,
              enter = expandVertically(motion.defaultSpatialSpec(), Alignment.Bottom) + scaleIn(motion.defaultSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(motion.defaultEffectsSpec()),
              exit = shrinkVertically(motion.defaultSpatialSpec(), Alignment.Bottom) + scaleOut(motion.defaultSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(motion.defaultEffectsSpec()),
            ) {
              PauseBar(
                leftHanded,
                onResume = { recordingAction("resume"); buzz() },
                onEnd = {
                  // Not stopService: the service carries on for the team. The hold already buzzed.
                  // Not a point recorded: nothing to keep (C3-28), never 「已保存 · 0 m」.
                  val empty = recordingLine.all { it.isEmpty() }
                  startService(Intent(this@MainActivity, RecordingService::class.java).setAction("stop").putExtra(RecordingService.EXTRA_DISCARD, empty))
                  val id = recording
                  if (empty || id == null) hint = Hint(getString(R.string.hint_not_saved_no_fix))
                  else {
                    // Its back closes it, as after an import (§5.5); its name comes once looked up (§8.3 第 15 条).
                    trackPage = false
                    detailTrack = id
                    hint = Hint(getString(R.string.hint_saved, distanceText(live?.distanceM ?: 0.0)))
                    scope.launch {
                      withContext(Dispatchers.IO) { TrackDb(this@MainActivity).use { nameRecording(it, id) } }
                      datumVersion++
                      tracksVersion++
                    }
                  }
                },
                onEndTooShort = { hint = Hint(getString(R.string.hold_to_end)) },
                modifier = Modifier.padding(bottom = Space.XS).hintAnchor(),
              )
            }
            // §3.3: rising from behind the 底栏 on the expressive spring as recording starts, and back (the only two
            // 表现力时刻); with a 参考 it's there before and after, the same strip, coming and going on the standard one.
            val stripMotion = if (reference == null) MotionScheme.expressive() else motion
            AnimatedVisibility(
              (recordingNow != null || reference != null) && detailTrack == null,
              enter = expandVertically(stripMotion.defaultSpatialSpec(), Alignment.Bottom) + fadeIn(stripMotion.defaultEffectsSpec()),
              exit = shrinkVertically(stripMotion.defaultSpatialSpec(), Alignment.Bottom) + fadeOut(stripMotion.defaultEffectsSpec()),
            ) {
              DataStrip(
                recordingNow, reference, fixAccuracy,
                // #139: as 爬升 and 最高海拔, from the recorded points.
                altitudeM = recordingLine.lastOrNull()?.lastOrNull()?.ele,
                battery = batteryNow,
                onOpenReference = { referenceDrawer = true },
                onStopReference = ::stopReference,
                modifier = Modifier.padding(horizontal = Space.M, vertical = Space.XS).hintAnchor(),
              )
            }
            // 轨迹详情's 窄条 takes the 底栏's place.
            if (detailTrack != null) Spacer(Modifier.height(peekHeight))
            else BottomBar(
              unread = teamUnread,
              update = update != null,
              recording = active && !paused,
              onTracks = { trackPage = true },
              onTeam = ::openTeam,
              // Paused, it's ▶ again and goes on, as 继续 does.
              onStart = { if (!active) record(null) else { recordingAction(if (paused) "resume" else "pause"); buzz() } },
              onOffline = { offlinePage = true },
              onSettings = { settingsPage = true },
            )
          }
          if (meSheet) {
            BackHandler { meSheet = false }
            // C2-14: 「我的位置」, ［⊕ 标注］［分享坐标］.
            PlaceSheet(
              stringResource(R.string.me), null,
              listOf(
                stringResource(R.string.mark) to { meSheet = false; mark() },
                stringResource(R.string.share_coordinate) to { meSheet = false; currentFix()?.let { shareCoordinate(it.latitude, it.longitude) } },
              ),
              Modifier.align(Alignment.BottomCenter),
            )
          }
          // ux-v2 §4.1: one drawer at a time. 轨迹详情 replaces the one open, and the next one opened replaces it, but for
          // the long-press card: it opens over the track's 窄条 and the track stays.
          fun otherDrawer() = layers || meSheet || nearbyTracks.isNotEmpty() || mateSheet != null || referenceDrawer || teamPage
          LaunchedEffect(detailTrack) { detailSheet = null; detailStop = DrawerStop.Peek; if (detailTrack != null) { pressed = null; layers = false; meSheet = false; nearbyTracks = emptyList(); mateSheet = null; referenceDrawer = false } }
          // The 参考轨迹抽屉 replaces the one open, and any opened after it (from search, a notification…) replaces it.
          // Opened, the 队伍页 closes every drawer and the 群聊's pin under it.
          LaunchedEffect(teamPage) { if (teamPage) { pressed = null; layers = false; meSheet = false; nearbyTracks = emptyList(); mateSheet = null; chatPin = null } }
          fun notReference() = pressed != null || layers || meSheet || nearbyTracks.isNotEmpty() || mateSheet != null || teamPage
          LaunchedEffect(referenceDrawer) { if (referenceDrawer) { pressed = null; layers = false; meSheet = false; nearbyTracks = emptyList(); mateSheet = null } }
          LaunchedEffect(notReference()) { if (notReference()) referenceDrawer = false }
          // Read again inside: when both open at once, 轨迹详情 (just closed the other above) stays.
          LaunchedEffect(otherDrawer()) { if (otherDrawer()) detailTrack = null }
          // 我的轨迹 likewise, the long-press card too; opened, it closes the others.
          LaunchedEffect(otherDrawer() || pressed != null) { if (otherDrawer() || pressed != null) { trackPage = false; openGroup = null; editing = null } }
          val drawerOpen = trackPage || openGroup != null || editing != null
          LaunchedEffect(drawerOpen) { if (drawerOpen) { pressed = null; layers = false; meSheet = false; nearbyTracks = emptyList(); mateSheet = null; referenceDrawer = false } }
          LaunchedEffect(highlighted) { if (highlighted != null) { delay(2_000); highlighted = null } }
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
          // Without a socket (ended, or no service for want of location): caught up every 10 s while the page shows.
          // ponytail: polling only stands in for the service until location is allowed (#141).
          LaunchedEffect(teamPage, team?.id, team?.ended, resumes, teamNoLocation) {
            val t = team ?: return@LaunchedEffect
            if (!t.ended && !teamNoLocation) return@LaunchedEffect
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
              // C5-04: with 撤销, which puts them back as they were.
              onClearOverlays = {
                val before = overlays
                saveOverlays(emptyMap())
                hint = Hint(getString(R.string.hint_overlays_cleared, before.size), listOf(getString(R.string.undo) to { saveOverlays(overlays + before) }))
              },
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
              nearbyTracks, nearbySaved,
              onReference = { saveNearby(it)?.let(::setReference); nearbyTracks = emptyList() },
              onSave = { t -> saveNearby(t)?.let { nearbySaved += t to it } },
              onOpen = { detailTrack = it },
              modifier = Modifier.align(Alignment.BottomCenter),
            )
          }
          if (settingsPage) {
            BackHandler { settingsPage = false }
            SettingsScreen(
              account != null, nickname, myAvatar,
              lastSync = remember(syncOn, pulled) { lastSyncText().takeIf { syncOn } },
              preTripFailing = preTripFailing,
              leftHanded = leftHanded,
              onLeftHanded = { leftHanded = it; prefs.edit().putBoolean(PREF_LEFT_HANDED, it).apply() },
              offTrackM = offTrackM,
              onOffTrack = { offTrackM = it; prefs.edit().putInt(PREF_OFF_TRACK, it).apply() },
              update = update != null,
              onAccount = { accountPage = true },
              onAbout = { aboutPage = true },
              onPreTrip = { preTripSheet = true },
              onHint = { hint = it },
            )
          }
          if (aboutPage) {
            BackHandler { aboutPage = false }
            AboutScreen(update, onBack = { aboutPage = false }, onSources = { sourcesPage = true }, onHint = { hint = it })
          }
          if (sourcesPage) {
            BackHandler { sourcesPage = false }
            SourcesScreen(
              onBack = { sourcesPage = false },
              onOpen = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
              osmExtract = BuildConfig.API_URL + "/v1/data/osm-extract",
            )
          }
          if (offlinePage) {
            BackHandler { offlinePage = false }
            OfflineMapScreen(
              packages = packages.filter { it.dir !in trashedFiles },
              dataVersion = dataVersion,
              download = downloadRequest?.let { Triple(it, downloadName, downloadPercent) },
              freeBytes = remember(filesVersion, trashedFiles) { dir.usableSpace },
              now = remember { System.currentTimeMillis() },
              onOpen = ::showOutline,
              onUpdate = { downloadPackage(it.name, it.request, old = it) },
              onDeletePackage = { trashFile(it.dir, it.bytes) },
              files = files.filter { it !in trashedFiles },
              importing = importing,
              onImport = ::pickMapFile,
              onDelete = { trashFile(it, it.length()) },
              onBackToMap = { offlinePage = false },
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
                hint = Hint(getString(R.string.hint_start_pick), listOf(getString(R.string.cancel) to {}), sticky = true).also { startPick = it }
              },
              onStop = ::stopReference,
              onClose = { referenceDrawer = false },
            )
          }
          mateSheet?.let { id ->
            val m = mates.firstOrNull { it.id == id } ?: return@let
            BackHandler { mateSheet = null }
            MateSheet(m, team ?: return@let, now, here, mateAlong, Modifier.align(Alignment.BottomCenter))
          }
          if (teamPage) {
            val t = team
            // A logout meanwhile reads as an expired login.
            fun acct() = account ?: throw OfflineError("unauthorized")
            when {
              t == null || teamJoin -> {
                BackHandler { if (t != null) teamJoin = false else teamPage = false }
                TeamJoinScreen(
                  loggedIn = account != null,
                  inTeam = t?.ended == false,
                  lookup = { api.teamCard(acct(), it) },
                  onNeedLogin = { loginForTeam = true; accountPage = true },
                  creating = teamBusy == "", joining = teamBusy?.isNotEmpty() == true,
                  note = teamNote, onRetry = teamRetry,
                  onCreate = { joinTeam(null) },
                  onJoin = ::joinTeam,
                  nowMs = now,
                  online = online,
                )
              }
              teamInfo -> {
                BackHandler { teamInfo = false }
                TeamInfoScreen(
                  t, now, here, mateAlong,
                  highlighted = highlightedMate, onHighlight = { highlightedMate = it },
                  saver = teamSaver,
                  onSharing = ::setSharing,
                  onSaver = { teamSaver = !teamSaver; prefs.edit().putBoolean(PREF_TEAM_SAVER, teamSaver).apply() },
                  leave = { api.leaveTeam(acct(), t.id) },
                  end = { api.endTeam(acct(), t.id) },
                  // C4-76: back on the map.
                  onLeft = { quitTeam(); teamInfo = false; teamPage = false; hint = Hint(getString(R.string.hint_left_team)) },
                  onEnded = { tripEnded(t); teamInfo = false },
                  tracks = myTracks,
                  giveTrack = { id ->
                    val replaced = TeamTrackHere.parse(prefs.getString(PREF_TEAM_TRACK, null))?.takeIf { it.team == t.id && it.version == 0L }?.track
                    giveTeamTrack(t.id, id)
                    replaced
                  },
                  // C4-65: 撤销 puts back the one replaced, or none; if that fails, it says so.
                  onTrackGiven = { replaced ->
                    hint = Hint(getString(R.string.hint_team_track_set), listOf(getString(R.string.undo) to {
                      thread {
                        runCatching { if (replaced != null) giveTeamTrack(t.id, replaced) else api.deleteTeamTrack(acct(), t.id) }
                          .onFailure { e -> runOnUiThread { hint = failHint(R.string.result_team_track_failed, reasonOf(e.errorCode)) } }
                      }
                    }))
                  },
                  dropTrack = { api.deleteTeamTrack(acct(), t.id) },
                  onBack = { teamInfo = false },
                  online = online,
                  reconnecting = reconnecting,
                )
              }
              else -> {
                BackHandler { teamPage = false }
                ChatScreen(
                  t, here, now,
                  outbox = outbox.filter { it.team == t.id },
                  onResend = ::attempt,
                  loadImage = { id, thumb -> loadImage(t.id, id, thumb) },
                  draft = chatDraft,
                  onDraft = { chatDraft = it },
                  onSend = { text -> send(Outgoing(++outgoingIds, t.id, "text", text = text, json = messageJson("text", text = text))) },
                  onLocation = ::sendLocation,
                  locating = locatingSince != null,
                  onPhoto = { pickChatPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                  // Back on the map, centred on it.
                  onFocus = { lat, lon ->
                    teamPage = false
                    val at = Position(longitude = lon, latitude = lat)
                    chatPin = at
                    moveTo(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 14.0)), Motion.FOCUS)
                  },
                  onInfo = { teamInfo = true },
                  // C4-73: to 建队 / 加入; the old 对话 goes once another team is joined.
                  onNewTeam = { teamJoin = true },
                  onClose = { teamPage = false },
                  noLocation = teamNoLocation,
                  onAllowLocation = { teamFixAsked = true; shareWithTeam(t.id) },
                  online = online,
                  reconnecting = reconnecting,
                )
              }
            }
          }
          if (searching) {
            BackHandler { searching = false }
            SearchScreen(searchQuery, searchResults, searchLocal, searchFrom(), searchBusy, searchNote, onQuery = { searchQuery = it }, onPick = { p ->
              searching = false
              val at = Position(longitude = p.lon, latitude = p.lat)
              moveTo(state.cameraPosition.copy(target = at, zoom = maxOf(state.cameraPosition.zoom, 13.0)), Motion.FOCUS)
              pressedPlace = at to p.takeIf { it.kind != "coordinate" }
              pressed = at
            }, online = online)
          }
          // 我的轨迹 (ux-v3 §5.5): one drawer, the list and 轨迹详情 taking turns in it, only fading (§3.4). Back from a
          // 轨迹详情 opened from the list goes back to it as it was, the camera staying; one that opened by itself (an
          // import, the end of a recording) closes the drawer.
          // A 标注组 and a 标注 being edited go on the same way (§8.5 第 10、11 条).
          val detailShown = detailTrack != null && detail != null
          val editingShown = editing?.let { id -> waypoints.firstOrNull { it.id == id } }
          val groupShown = openGroup?.let { gid -> groups.firstOrNull { it.id == gid } }
          val page = when {
            editingShown != null -> DrawerPage.Waypoint(editingShown.id)
            detailShown -> DrawerPage.Detail(detailTrack!!)
            groupShown != null -> DrawerPage.Group(groupShown.id)
            trackPage -> DrawerPage.List
            else -> null
          }
          // Closing over the map, it slides away as it was: a 轨迹详情 doesn't turn into the list on the way.
          var lastPage by remember { mutableStateOf<DrawerPage>(DrawerPage.List) }
          if (page != null) lastPage = page
          val inDetail = lastPage is DrawerPage.Detail
          // A track come in (import, recording, sync) is on top: the list goes there, even kept scrolled down.
          LaunchedEffect(myTracks.firstOrNull()?.id) { trackList.requestScrollToItem(0) }
          val motion = MaterialTheme.motionScheme
          AnimatedVisibility(
            page != null,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutVertically(motion.defaultSpatialSpec()) { it } + fadeOut(motion.defaultEffectsSpec()),
          ) {
            BackHandler {
              when (page) {
                is DrawerPage.Waypoint -> editing = null
                is DrawerPage.Detail -> detailTrack = null
                is DrawerPage.Group -> openGroup = null
                else -> trackPage = false
              }
            }
            val stop = if (inDetail) detailStop else if (trackFull) DrawerStop.Full else DrawerStop.Half
            StopDrawer(
              stop,
              onUp = { if (inDetail) detailStop = if (detailStop == DrawerStop.Peek) DrawerStop.Half else DrawerStop.Full else trackFull = true },
              onDown = {
                if (inDetail) detailStop = if (detailStop == DrawerStop.Full) DrawerStop.Half else DrawerStop.Peek
                else if (trackFull) trackFull = false else { trackPage = false; openGroup = null; editing = null }
              },
              onTap = { if (inDetail) detailStop = if (detailStop == DrawerStop.Peek) DrawerStop.Half else DrawerStop.Peek else trackFull = !trackFull },
              onPeek = { peekHeight = it },
            ) {
              AnimatedContent(
                lastPage,
                if (stop == DrawerStop.Peek) Modifier else Modifier.weight(1f),
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) },
              ) { shown ->
                Column(if (stop == DrawerStop.Peek) Modifier else Modifier.fillMaxSize()) {
                  val shownId = (shown as? DrawerPage.Detail)?.id
                  val d = detail.takeIf { shownId != null && shownId == detailTrack }
                  // Fading out after back, a page's data may already be gone: it just goes.
                  if (shown is DrawerPage.Waypoint) waypoints.firstOrNull { it.id == shown.id }?.let { w ->
                    WaypointEditor(
                      w, editName, editDescription, now,
                      // C5-22: kept as typed; the lists catch up on the way out.
                      onName = { n -> editName = n; TrackDb(this@MainActivity).use { it.setWaypointText(w.id, n.trim(), editDescription.trim()) } },
                      onDescription = { t -> editDescription = t; TrackDb(this@MainActivity).use { it.setWaypointText(w.id, editName.trim(), t.trim()) } },
                      groups = groups.takeIf { w.trackId == null },
                      onGroup = { g -> moveWaypoint(w.id, g) },
                      onNewGroup = { groupSheet = GroupSheet.New(moving = w.id) },
                      onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                      onDownload = { editing = null; downloadNearby(w.lat, w.lon, editName.trim().ifEmpty { null }) },
                      onBack = { editing = null },
                      onDelete = { editing = null; trash(Trash.Waypoint, w.id, getString(R.string.hint_deleted)) },
                    )
                  }
                  else if (shown is DrawerPage.Group) groups.firstOrNull { it.id == shown.id }?.let { g ->
                    GroupPage(
                      g, remember(waypoints, g.id) { waypoints.filter { it.groupId == g.id } }, now, highlighted,
                      onBack = { openGroup = null },
                      onWaypoint = ::openWaypoint,
                      onRename = { groupSheet = GroupSheet.Rename(g.id) },
                      onExport = { groupSheet = GroupSheet.Export(g.id) },
                      // C5-30: how many go with it.
                      onDelete = {
                        openGroup = null
                        trash(Trash.Group, g.id, if (g.count > 0) getString(R.string.hint_deleted_group, g.count) else getString(R.string.hint_deleted))
                      },
                    )
                  }
                  else if (shown is DrawerPage.List) TrackList(
                    trackTab, { trackTab = it }, trackList,
                    tracks = myTracks,
                    stats = trackStatsById,
                    now = now,
                    reference = referenceTrack,
                    overlays = overlays,
                    importing = importingTrack,
                    onOpen = { detailTrack = it },
                    // §8.5 第 6 条: overlaid where it can't be seen, the camera fits it above the drawer, which stays at half.
                    onOverlay = { id ->
                      val on = id !in overlays
                      toggleOverlay(id)
                      if (on) scope.launch {
                        val points = withContext(Dispatchers.IO) { TrackDb(this@MainActivity).use { it.segments(id) } }.flatten().map { Position(longitude = it.lon, latitude = it.lat) }
                        if (points.isEmpty()) return@launch
                        val (sw, ne) = state.getVisibleBounds() ?: return@launch
                        // Under the top bar (as fitTrack keeps clear) or the drawer, it can't be seen; full, nothing can.
                        val seen = !trackFull && inView(
                          points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude },
                          sw, ne, fromTop = 150 / window.second, toTop = 0.5,
                        )
                        if (seen) return@launch
                        trackFull = false
                        fitTrack(points, window.second / 2)
                      }
                    },
                    // Track files often arrive with no or a generic MIME type; the content decides the format.
                    onImport = ::pickTrack,
                    groups = groups,
                    // A track's 标注 are with the track (on the map when it's drawn), not in this list; a group's in the group.
                    waypoints = remember(waypoints) { waypoints.filter { it.trackId == null && it.groupId == null } },
                    onWaypoint = ::openWaypoint,
                    onGroup = { openGroup = it },
                    onGroupShown = { g -> TrackDb(this@MainActivity).use { it.setGroupShown(g.id, !g.shown) }; waypointsVersion++ },
                    onWaypointShown = { w -> TrackDb(this@MainActivity).use { it.setWaypointShown(w.id, !w.shown) }; waypointsVersion++ },
                    onNewGroup = { groupSheet = GroupSheet.New(moving = null) },
                    onExportLoose = { groupSheet = GroupSheet.Export(null) },
                    highlighted = highlighted,
                    onBackToMap = { trackPage = false },
                  )
                  // Fading out after back, its data is already gone: it just goes.
                  else if (d != null && shownId != null) {
                    val id = shownId
                    val (name, _, segments) = d
                    val request = remember(segments) { trackRequest(segments) }
                    // Under any 坐标来源 it's this track's package: the 2 km corridor dwarfs the shift (#112).
                    val requests = remember(id) { corridorRequests(id) }
                    val pkg = packages.firstOrNull { it.request in requests }
                    val tooLarge = remember(segments) { corridorTooLarge(segments) }
                    val download = { downloadPackage(name, request, old = pkg) }
                    // §8.2 第 10 条: a nudge to take the map along, the first 3 times only, until any package is downloaded.
                    // Not over a 提示条 already up (已导入 opens it).
                    LaunchedEffect(id) {
                      val shown = prefs.getInt(PREF_CORRIDOR_NUDGES, 0)
                      if (pkg != null || tooLarge || id == recording || hint != null || shown >= 3 || prefs.getBoolean(PREF_DOWNLOADED_ANY, false) || packages.isNotEmpty()) return@LaunchedEffect
                      prefs.edit().putInt(PREF_CORRIDOR_NUDGES, shown + 1).apply()
                      hint = Hint(getString(R.string.hint_corridor_nudge), listOf(getString(R.string.action_download) to download))
                    }
                    val stats = remember(segments) { trackStats(segments) }
                    val planned = remember(id) { TrackDb(this@MainActivity).use { it.planned(id) } }
                    val piece = trim
                    if (piece != null) {
                      BackHandler { trim = null }
                      TrimPanel(segments, planned, piece, onRange = { trim = it }, onCancel = { trim = null }, onSave = { detailSheet = DetailSheet.Trim })
                    } else TrackDetail(
                      detailStop, name,
                      source = remember(id) { TrackDb(this@MainActivity).use { it.source(id) } },
                      planned = planned,
                      public = remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.isPublic(id) } },
                      stats = stats,
                      // The profile as walked from its 起算点; the numbers are the track's own.
                      profile = remember(detailWalked) { detailWalked?.let { trackStats(it).profile }.orEmpty() },
                      reversed = detailStart.reversed,
                      onReversed = { r -> saveTrackStart(id, detailStart.copy(reversed = r)) },
                      color = if (id == referenceTrack) semantic.reference else overlays[id]?.let { semantic.overlay(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                      here = detailAt,
                      onHere = { if (me.lastLocation != null) follow = Follow.On },
                      reference = id == referenceTrack,
                      overlaid = id in overlays,
                      corridor = corridor(pkg, dataVersion, downloadPercent.takeIf { downloadRequest in requests }, tooLarge),
                      imported = remember(id, datumVersion) { TrackDb(this@MainActivity).use { it.imported(id) } },
                      recording = id == recording,
                      teamTrack = team?.let { isTeamTrack(it, id) } == true,
                      preTrip = if (id == referenceTrack) preTripFailing else emptySet(),
                      onPreTrip = { preTripSheet = true },
                      onBack = { detailTrack = null },
                      onWeather = { weatherPlace = WeatherPlace.Track(id) },
                      // §8.2 第 7 条: set, the drawer goes and the camera takes in the whole line, over the 窄条 it gets.
                      onReference = {
                        if (id == referenceTrack) setReference(null)
                        else {
                          setReference(id)
                          detailTrack = null
                          trackPage = false
                          scope.launch { fitTrack(segments.flatten().map { Position(longitude = it.lon, latitude = it.lat) }, REFERENCE_STRIP_DP) }
                        }
                      },
                      onOverlay = { toggleOverlay(id) },
                      onDownload = download,
                      onRename = { detailSheet = DetailSheet.Rename },
                      onDatum = { detailSheet = DetailSheet.Datum },
                      onExport = { detailSheet = DetailSheet.Export },
                      // The whole track to start, the drawer down to the panel. A lone point has nothing to trim.
                      onTrim = { segments.sumOf { it.size }.takeIf { it >= 2 }?.let { trim = 0 until it; detailStop = DrawerStop.Peek } },
                      onMerge = { mergePicked = listOf(id); detailSheet = DetailSheet.Merge },
                      // C2-72: logged out (or 同步 off), straight to 登录, which says why; 撤回 at once.
                      onPublic = {
                        if (TrackDb(this@MainActivity).use { it.isPublic(id) } || account == null || !syncOn) { togglePublic(id); datumVersion++ }
                        else detailSheet = DetailSheet.Public
                      },
                      onDelete = { deleteTrack(id) },
                      onDeleteRefused = { hint = failHint(R.string.result_cannot_delete, R.string.reason_team_track) },
                    )
                  }
                }
              }
            }
          }
          detailSheet?.let { sheet ->
            val id = detailTrack ?: return@let
            val (name, datum, _) = detail ?: return@let
            val close = { detailSheet = null }
            BackHandler(onBack = close)
            val at = Modifier.align(Alignment.BottomCenter)
            when (sheet) {
              DetailSheet.Rename -> NameSheet(
                stringResource(R.string.rename), name, stringResource(R.string.save),
                { n -> TrackDb(this@MainActivity).use { it.setName(id, n) }; datumVersion++; tracksVersion++; close() }, close, at,
              )
              DetailSheet.Datum -> DatumSheet(datum, { d -> TrackDb(this@MainActivity).use { it.setDatum(id, d) }; datumVersion++; waypointsVersion++; close() }, close, at)
              // C2-73: the 「已公开」 tag says it.
              DetailSheet.Public -> PublicSheet({ togglePublic(id); datumVersion++; close() }, close, at)
              // #88: saved, the piece opens in 轨迹详情; the original stays as it was.
              // ponytail: written (photos copied too) on the main thread, as 改名 is; go async if a long track janks.
              DetailSheet.Trim -> trim?.let { piece ->
                NameSheet(stringResource(R.string.trim_save), stringResource(R.string.trim_name, name), stringResource(R.string.save), { n ->
                  val new = TrackDb(this@MainActivity).use { it.trimTrack(id, piece, n, getString(R.string.trimmed_from, name), System.currentTimeMillis()) }
                  close()
                  tracksVersion++
                  waypointsVersion++
                  detailTrack = new
                }, close, at)
              }
              DetailSheet.Merge -> MergeSheet(
                myTracks, trackStatsById, now, remember(id) { TrackDb(this@MainActivity).use { it.planned(id) } }, mergePicked,
                onToggle = { t -> mergePicked = if (t in mergePicked) mergePicked - t else mergePicked + t },
                onMerge = {
                  val order = mergeOrder(mergePicked.mapNotNull { p -> myTracks.firstOrNull { it.id == p } })
                  if (TrackDb(this@MainActivity).use { it.mergeOverlaps(order.map(TrackSummary::id)) }) hint = Hint(getString(R.string.merge_overlap))
                  else { mergeOrdered = order; detailSheet = DetailSheet.MergeName }
                },
                onCancel = close, modifier = at,
              )
              // As 截取: saved, the new track opens in 轨迹详情; the originals stay as they were.
              DetailSheet.MergeName -> mergeOrdered.firstOrNull()?.let { first ->
                NameSheet(stringResource(R.string.merge_save), stringResource(R.string.merge_name, first.name), stringResource(R.string.save), { n ->
                  val new = TrackDb(this@MainActivity).use { it.mergeTracks(mergeOrdered.map(TrackSummary::id), n, getString(R.string.merged_from, mergeOrdered.size), System.currentTimeMillis()) }
                  close()
                  tracksVersion++
                  waypointsVersion++
                  detailTrack = new
                }, close, at)
              }
              DetailSheet.Export -> ExportSheet(
                remember(id, waypointsVersion) { TrackDb(this@MainActivity).use { db -> db.waypoints(id).count { w -> w.photo?.let { File(it).isFile } == true } } },
                exporting, { kml -> exportTrack(id, kml) }, close, at,
              )
            }
          }
          if (preTripSheet) {
            BackHandler { preTripSheet = false }
            PreTripSheet(preTripFailing, brandNote(Build.MANUFACTURER), ::fixCheck, ::openAppSettings, { preTripSheet = false }, Modifier.align(Alignment.BottomCenter))
          }
          groupSheet?.let { sheet ->
            val close = { groupSheet = null }
            BackHandler(onBack = close)
            val names = remember(groups) { TrackDb(this@MainActivity).use { it.groupNames() } }
            val at = Modifier.align(Alignment.BottomCenter)
            when (sheet) {
              // C5-10: built, the new row lights up; no 提示条. Asked from a 标注, it goes in there.
              is GroupSheet.New -> NameSheet(
                stringResource(R.string.new_group), "", stringResource(R.string.create),
                { n -> addGroup(n)?.let { gid -> sheet.moving?.let { moveWaypoint(it, gid) }; highlighted = "g$gid" }; close() }, close, at,
                placeholder = stringResource(R.string.group_placeholder), taken = { it in names },
              )
              is GroupSheet.Rename -> groups.firstOrNull { it.id == sheet.id }?.let { g ->
                NameSheet(
                  stringResource(R.string.rename), g.name, stringResource(R.string.save),
                  { n -> if (TrackDb(this@MainActivity).use { it.renameGroup(g.id, n) }) waypointsVersion++ else hint = Hint(getString(R.string.group_name_taken)); close() }, close, at,
                  taken = { it in names },
                )
              }
              // #72: as a track's, but only 标注: a group's under its name, those 不在组里 as 「标注 10月5日」.
              is GroupSheet.Export -> ExportSheet(
                remember(sheet, waypoints) { waypoints.count { w -> w.trackId == null && w.groupId == sheet.id && w.photo?.let { File(it).isFile } == true } },
                exporting,
                { kml ->
                  val name = sheet.id?.let { gid -> groups.firstOrNull { it.id == gid }?.name }
                    ?: getString(R.string.loose_export_name, dayText(System.currentTimeMillis(), System.currentTimeMillis()))
                  export(kml) { db -> Exported(name, db.waypoints().filter { it.trackId == null && it.groupId == sheet.id }) }
                },
                close, at,
              )
            }
          }
          weatherPlace?.let { place ->
            val close = { weatherPlace = null }
            BackHandler(onBack = close)
            when (place) {
              WeatherPlace.Here -> WeatherScreen(
                stringResource(R.string.me), hereWeather, loading = fix != null && online && hereError == null, now, close, online,
                hereError, { weatherTries++ }, noFix = fix == null,
              )
              is WeatherPlace.Point -> WeatherScreen(place.name ?: coordinateText(place.lat, place.lon), pointWeather, pointLoading, now, close, online, pointError, { weatherTries++ })
              is WeatherPlace.Track -> {
                // Each spot's days, so the pins and choices follow the day picked.
                val days = remember(spotWeather, now / 3_600_000) { spotWeather.map { w -> w?.let { weatherDays(it, now, TimeZone.getDefault()) } } }
                WeatherScreen(
                  detail?.first.orEmpty(), spotWeather.getOrNull(spot), spotsLoading, now, close, online, spotsError, { weatherTries++ },
                  above = { day ->
                    val picked = days.map { it?.getOrNull(day) }
                    TrackSpots(
                      weatherStats?.profile.orEmpty(), weatherStats?.distanceM ?: 0.0, spots,
                      picked.map { d -> d?.let { "${it.high}°/${it.low}°" } }, picked.map { it?.stormy == true }, spot, spotWeather.any { it != null },
                    ) { spot = it }
                  },
                )
              }
            }
          }
          pressed?.let { at ->
            BackHandler { pressed = null }
            val place = pressedPlace?.takeIf { it.first == at }?.second
            val title = placeTitle(place, at.latitude, at.longitude)
            PlaceSheet(
              title,
              listOfNotNull(regionLine(place?.detail), coordinateText(at.latitude, at.longitude).takeIf { place != null }).joinToString(" · ").ifEmpty { null },
              listOf(
                // §8.2 地点小抽屉 · 无网络: the buttons work as ever; tapped, 「没有网络」.
                stringResource(R.string.weather) to {
                  if (!online) hint = Hint(getString(R.string.reason_offline))
                  else { pressed = null; weatherPlace = WeatherPlace.Point(at.latitude, at.longitude, place?.name) }
                },
                stringResource(R.string.download_nearby) to { pressed = null; downloadNearby(at.latitude, at.longitude, place?.name) },
                stringResource(R.string.mark) to { pressed = null; markAt(System.currentTimeMillis(), at.latitude, at.longitude, null, place?.name, ::openWaypoint) },
              ),
              Modifier.align(Alignment.BottomCenter),
              menu = listOf(
                stringResource(R.string.measure) to { pressed = null; measureFrom = at; measureTo = null },
                stringResource(R.string.copy_coordinate) to {
                  getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("坐标", coordinateText(at.latitude, at.longitude)))
                  // Android 13+ confirms copies itself.
                  if (Build.VERSION.SDK_INT < 33) hint = Hint(getString(R.string.hint_copied))
                },
                stringResource(R.string.share_coordinate) to { shareCoordinate(at.latitude, at.longitude) },
              ),
            )
          }
          pendingImport?.let { (fileName, file) ->
            BackHandler { pendingImport = null }
            ImportPickScreen(
              fileName, file.tracks, pickChecked,
              onToggle = { i -> pickChecked = if (i in pickChecked) pickChecked - i else pickChecked + i },
              onImport = { pendingImport = null; saveImport(fileName, file, pickChecked.sorted()) },
              onBack = { pendingImport = null },
            )
          }
          // 登录 over everything, 轨迹详情 included (#134).
          if (accountPage) {
            BackHandler(onBack = ::closeLogin)
            // 昵称 and 头像 fresh each time it opens (another phone may have changed them).
            LaunchedEffect(account) { account?.let(::fetchMe) }
            AccountScreen(
              account,
              nickname = nickname,
              avatar = myAvatar,
              avatarBusy = avatarBusy,
              onPickAvatar = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
              onDropAvatar = ::dropAvatar,
              saveNickname = { n -> account?.let { api.setNickname(it, n) } ?: throw OfflineError("unauthorized") },
              onNickname = ::keepNickname,
              onBack = ::closeLogin,
              forTeam = loginForTeam,
              sendCode = api::sendCode,
              login = api::login,
              onLogin = {
                accounts.set(it)
                account = it
                fetchMe(it)
                accountPage = false
                loginForTeam = false
                // C6-31: 同步 comes on with it (§8.6 第 6 条).
                hint = Hint(getString(R.string.hint_logged_in_syncing))
                teamAfterLogin?.let { joinTeam(it.ifEmpty { null }) }
                teamAfterLogin = null
                setSync(true)
              },
              inTeam = team != null,
              // C6-45: back to 设置.
              onLogout = { logout(); accountPage = false; hint = Hint(getString(R.string.hint_logged_out)) },
              sync = syncOn,
              lastSync = remember(accountPage, pulled) { lastSyncText() },
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
                keepNickname(null)
                keepAvatar(null)
                avatars.clear()
                accountPage = false
                hint = Hint(getString(R.string.hint_account_deleted))
              },
              online = online,
            )
          }
          cropping?.let { uri ->
            AvatarCropScreen(
              uri, onCancel = { cropping = null },
              onUse = { cropping = null; uploadAvatar(it) },
              onUnreadable = { cropping = null; hint = failHint(R.string.result_avatar_not_changed, R.string.reason_photo) },
            )
          }
          // Back is 取消 on a 提示条 waiting for an answer, even while a one-shot covers it; so is its cross.
          hints.firstOrNull { it.sticky }?.let { ask -> BackHandler { hints = hints - ask } }
          if (hints.any { it.pick }) Crosshair(Modifier.align(Alignment.Center))
          hint?.let { h -> LaunchedEffect(h) { hintMs(h)?.let { delay(it); hint = null } } }
          HintHost(hint, onClose = { hint = null })
          if (ClientOutdated.prompt.collectAsState().value) {
            UpgradePrompt(onUpgrade = {
              ClientOutdated.prompt.value = false
              // Today's check may predate the release that raised MIN_CLIENT_VERSION.
              thread { checkForUpdate(prefs, force = true) }
              aboutPage = true
            }, onDismiss = { ClientOutdated.prompt.value = false })
          }
        }
      }
    } } }
  }

  private fun style(): String {
    val packages = packages().map { it.dir.absolutePath }
    val base = withPackages(withRemote(assets.open("style.json").bufferedReader().readText()), packages).replace("__DIR__", dir.absolutePath)
      .replace("__API__", BuildConfig.API_URL)
    return withImports(base, importsDir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull(::importOf))
  }

  private fun packages(): List<OfflinePackage> =
    packagesDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }.mapNotNull(::readPackage)

  /** 下载附近 (§8.2 第 11 条): about 20 × 20 km around the point, at once; named 「{地名}附近」 (C2-92), [name] if known. */
  private fun downloadNearby(lat: Double, lon: Double, name: String?) {
    val (w, s, e, n) = nearbyBbox(lat, lon)
    downloadPackage((name ?: String.format(Locale.ROOT, "%.3f, %.3f", lat, lon)) + "附近", bboxRequest(w, s, e, n))
  }

  /** Downloads an offline package (§2.3) into packages/; an update replaces [old] once the new one is complete. */
  private fun downloadPackage(name: String, request: String, old: OfflinePackage? = null) {
    if (!online) return run { hint = Hint(getString(R.string.reason_offline)) }
    if (downloading) return run { hint = failHint(R.string.result_wait_download) }
    downloadRequest = request
    downloadName = name
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
          prefs.edit().putBoolean(PREF_DOWNLOADED_ANY, true).apply()
          // §8.2 第 11 条: its 轨迹详情 still open, the button there says it; else 查看 shows its outline (C2-86).
          if (detailTrack?.let { request in corridorRequests(it) } != true) hint = Hint(getString(R.string.hint_map_downloaded), listOf(getString(R.string.view) to { showOutline(it) }))
        }.onFailure {
          // 范围太大 or 这里不支持 won't go better on 重试.
          val reason = reasonOf(it.errorCode)
          hint = failHint(R.string.result_download_failed, reason, if (reason in listOf(R.string.reason_too_large, R.string.reason_unsupported)) null else { -> downloadPackage(name, request, old) })
        }
      }
    }
  }

  /** 「{包名} · 7.5 MB」 with ✕ (C6-63), waiting for it: the outline is on the map while it's up. */
  private fun showOutline(pkg: OfflinePackage) {
    val h = Hint(getString(R.string.hint_package, pkg.name, Formatter.formatShortFileSize(this, pkg.bytes)), listOf(HINT_CLOSE to {}), sticky = true)
    // In front of any sticky one already up, so its ✕ is there to take the outline away.
    hints = listOf(h) + hints.filter { it !== outlined?.second }
    outlined = pkg to h
  }

  // ponytail: the trash is in memory; killed within the 8 s, the package stays. Keep it in prefs if that surprises anyone.
  /** 删除 in 离线地图 (C6-62): hidden at once, 「已删除 · 7.5 MB」 with 撤销; the files go once the 提示条 is gone. */
  private fun trashFile(file: File, bytes: Long) {
    val at = System.nanoTime()
    trashedFiles += file to at
    hint = Hint(getString(R.string.hint_deleted_size, Formatter.formatShortFileSize(this, bytes)), listOf(getString(R.string.undo) to { trashedFiles -= file }))
    // A little after the 提示条 is gone, so a last-moment 撤销 still finds it; deleted again since, the later one decides.
    Handler(Looper.getMainLooper()).postDelayed({
      if (trashedFiles[file] != at) return@postDelayed
      file.deleteRecursively()
      trashedFiles -= file
      filesVersion++
    }, HINT_LONGEST_MS + 500)
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
    if (ext !in importableExtensions) return run { hint = failHint(R.string.result_import_failed, R.string.reason_format, ::pickMapFile) }
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
        hint = if (ok) Hint(getString(R.string.hint_map_imported)) else failHint(R.string.result_import_failed, R.string.reason_file, ::pickMapFile)
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
    // Location allowed in the system settings meanwhile: the team's service starts, sharing as before (#141).
    val teamId = prefs.getLong(PREF_TEAM, 0L)
    if (teamNoLocation && teamId != 0L && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startTeam(teamId)
    // §8.3 第 2 条: back from the location settings, on with the start if it's on now.
    if (startAfterSwitch && leftForSwitch) {
      startAfterSwitch = false
      leftForSwitch = false
      readLocationOn()
      if (locationOn) record(resumeAfterGrant)
      else hint = Hint(getString(R.string.status_location_off), listOf(getString(R.string.action_open_location) to { record(resumeAfterGrant) }))
    }
    // Put right meanwhile (battery optimisation off in its settings…): its reminder goes, the next comes, numbered anew.
    if (reminderHints.isNotEmpty()) {
      val still = failing(phoneState())
      if (!still.containsAll(reminderHints.keys)) showReminders(reminderHints.keys.filter { it in still })
    }
  }

  override fun onDestroy() {
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
    unregisterReceiver(locationSwitch)
    super.onDestroy()
  }

  override fun onPause() {
    super.onPause()
    if (startAfterSwitch) leftForSwitch = true
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
          result.isFailure -> hint = failHint(R.string.result_import_failed, R.string.reason_file, ::pickTrack)
          file == null -> hint = failHint(R.string.result_import_failed, R.string.reason_file_too_big, ::pickTrack)
          file.tracks.isEmpty() && file.waypoints.isEmpty() -> hint = failHint(R.string.result_import_failed, R.string.reason_file_empty, ::pickTrack)
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
            // 标注组 named after the file, not what the file calls itself (#121); lit up once it shows (§8.5 第 13 条).
            db.importGroup(fileName.substringBeforeLast('.'), waypoints).let { runOnUiThread { highlighted = "g$it" } }
            emptyList()
          } else selected.mapIndexed { n, i ->
            val t = file.tracks[i]
            db.importTrack(t, importName(t, fileName, i, file.tracks.size), if (n == 0) waypoints else emptyList(), System.currentTimeMillis(), imported = true)
          }
        }
      }
      runOnUiThread {
        importingTrack = false
        tracksVersion++
        waypointsVersion++
        ids.onSuccess {
          hint = Hint(when {
            it.size > 1 -> getString(R.string.hint_imported_tracks, it.size)
            it.isEmpty() -> getString(R.string.hint_imported_waypoints, file.waypoints.size)
            else -> getString(R.string.hint_imported_km, distanceValue(trackStats(file.tracks[selected.single()].segments).distanceM))
          })
          // §8.2 第 4 条: one goes on into its 轨迹详情, whose back closes it all; more stay in the list, new on top.
          when {
            it.size == 1 -> { trackPage = false; detailTrack = it.single() }
            else -> { detailTrack = null; openGroup = null; editing = null; trackPage = true; trackTab = if (it.isEmpty()) 1 else 0 }
          }
        }.onFailure { hint = failHint(R.string.result_import_not_done) { saveImport(fileName, file, selected) } }
      }
    }
  }

  /** Adds a 标注 named [name], on the track being recorded if any. */
  private fun addWaypoint(timeMs: Long, lat: Double, lon: Double, ele: Double?, name: String): Waypoint {
    val track = RecordingService.activeTrack.value
    val id = TrackDb(this).use { db -> db.addWaypoint(track, timeMs, lat, lon, ele).also { db.updateWaypoint(it, name, "", null) } }
    waypointsVersion++
    return Waypoint(id, track, timeMs, lat, lon, ele, name, "", null)
  }

  /** A 标注 at (lat, lon), named [named] (the 地点小抽屉's 地名, §8.2 第 3 条), else after what's near ([defaultWaypointName]). */
  private fun markAt(timeMs: Long, lat: Double, lon: Double, ele: Double?, named: String? = null, then: (Waypoint) -> Unit) = thread {
    val name = named ?: defaultWaypointName(nearestPlace(placesNear(placeFiles(), lat, lon), lat, lon), timeMs, System.currentTimeMillis())
    runOnUiThread { then(addWaypoint(timeMs, lat, lon, ele, name)) }
  }

  /** Editing a 标注, in the 我的轨迹 drawer (§8.5 第 11 条), the camera on it. */
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
    markAt(timeMs, lat, lon, ele) { w ->
      buzz()
      droppedPin = w.id to Position(longitude = lon, latitude = lat)
      hint = Hint(getString(R.string.hint_marked), listOf(getString(R.string.undo) to { deleteWaypoint(w) }, getString(R.string.add_details) to { openWaypoint(w) }))
    }
  }

  /** 新建标注组 (#121); null, with a 提示条, if the name is taken (by one whose 撤销 is still on offer). */
  private fun addGroup(name: String): Long? =
    TrackDb(this).use { it.addGroup(name) }.also { if (it == null) hint = Hint(getString(R.string.group_name_taken)) else waypointsVersion++ }

  private fun moveWaypoint(id: Long, groupId: Long?) {
    TrackDb(this).use { it.setWaypointGroup(id, groupId) }
    waypointsVersion++
  }

  /** At once: a 标注 just made whose 撤销 was tapped (it never reached the server). */
  private fun deleteWaypoint(w: Waypoint) {
    TrackDb(this).use { it.deleteWaypoint(w.id) }
    waypointsVersion++
  }

  private fun saveWaypoint(w: Waypoint, photo: String? = w.photo) {
    TrackDb(this).use { it.updateWaypoint(w.id, editName.trim(), editDescription.trim(), photo) }
    waypointsVersion++
  }

  /**
   * 软删除 (§8.5 第 15 条): hidden at once, 「{text}」 with 撤销 for 8 s, which brings it back where it was, lit up, and
   * [restore]s what let go of it. After, it's deleted for good, and synced (nothing syncs meanwhile).
   */
  private fun trash(kind: Trash, id: Long, text: String, restore: () -> Unit = {}) {
    val at = System.currentTimeMillis()
    CloudSync.hold(this, HINT_LONGEST_MS + 1_000)
    TrackDb(this).use { it.trash(kind, id, at) }
    tracksVersion++
    waypointsVersion++
    hint = Hint(text, listOf(getString(R.string.undo) to {
      TrackDb(this).use { it.untrash(kind, id) }
      restore()
      tracksVersion++
      waypointsVersion++
      highlighted = when (kind) { Trash.Track -> "t"; Trash.Group -> "g"; Trash.Waypoint -> "w" } + id
    }))
    // A little after the 提示条 is gone, so a last-moment 撤销 still finds it.
    Handler(Looper.getMainLooper()).postDelayed({
      TrackDb(this).use { it.purgeTrashed(at) }.forEach(::forgetTrack)
      dropGoneTracks()
      waypointsVersion++
    }, HINT_LONGEST_MS + 500)
  }

  // ponytail: copies on the main thread; fine for phone photos, move off-thread if it janks.
  private fun attachPhoto(uri: Uri) {
    val id = editing ?: return
    val w = TrackDb(this).use { db -> db.waypoints().firstOrNull { it.id == id } } ?: return
    val file = File(filesDir, "photos/$id-${System.currentTimeMillis()}.jpg").apply { parentFile!!.mkdirs() }
    val ok = runCatching { contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }.isSuccess
    if (!ok) return run { file.delete(); hint = failHint(R.string.result_add_failed, R.string.reason_photo) }
    saveWaypoint(w, file.path)
    w.photo?.let { File(it).delete() }
  }

  /**
   * 经过这里的轨迹 (§2.8) for a tap at [at]: the 徒步线路 of the pushed data and every package, and the 公开轨迹
   * online, else from the packages' snapshots. Shown once found; nothing near, 「这里没有路网轨迹」 (C2-111). The online
   * lookup failing other than offline says so too, the snapshots listed meanwhile.
   */
  private fun findNearby(at: Position, zoom: Double) {
    val seq = ++nearbySeq
    val radius = tapRadiusM(at.latitude, zoom)
    val dirs = listOf(dir) + packages().map { it.dir }
    // ponytail: re-reads the files on every tap; small per package, but the pushed full-China routes.geojson
    // takes seconds. Keep them parsed (by filesVersion) if that bites outside development.
    fun read(name: String) = dirs.mapNotNull { File(it, name).takeIf(File::exists)?.readText() }
    nearbyAt = at
    thread {
      val fetched = runCatching { listOf(api.nearbyTracks(at.latitude, at.longitude, radius)) }
      val tracks = (fetched.getOrNull() ?: read("public-tracks.geojson")).map { NearbyKind.Public to it }
      val found = nearbyTracks(read("routes.geojson").map { NearbyKind.Route to it } + tracks, at.latitude, at.longitude, radius)
      runOnUiThread {
        if (seq != nearbySeq) return@runOnUiThread
        nearbyAt = null
        if (!nearby) return@runOnUiThread
        nearbyTracks = found
        nearbySaved = emptyMap()
        val code = fetched.exceptionOrNull()?.errorCode
        if (fetched.isFailure && code != "offline") hint = failHint(R.string.result_nearby_failed, reasonOf(code)) { findNearby(at, zoom) }
        else if (found.isEmpty()) hint = Hint(getString(R.string.hint_no_nearby))
      }
    }
  }

  /** Saves a 周边路网 line to 我的轨迹 as a 计划轨迹 (it has no times); its id, or null if that failed. */
  private fun saveNearby(t: NearbyTrack): Long? = runCatching {
    val now = System.currentTimeMillis()
    TrackDb(this).use { it.importTrack(ParsedTrack(t.name, true, t.segments), nearbyName(t.name), emptyList(), now) }
  }.onSuccess { tracksVersion++ }.onFailure { hint = failHint(R.string.result_save_not_done) { saveNearby(t) } }.getOrNull()

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

  /**
   * 删除轨迹 (#99, §8.5 第 15 条), softly: 参考, 叠加 and 轨迹详情 let go of it, 撤销 takes them back; its 标注 (and their
   * photos) and 起算点 go with it for good.
   */
  private fun deleteTrack(id: Long) {
    val wasReference = referenceTrack == id
    val overlay = overlays[id]
    if (wasReference) setReference(null, announce = false)
    if (overlay != null) saveOverlays(overlays - id)
    if (detailTrack == id) detailTrack = null
    trash(Trash.Track, id, getString(R.string.hint_deleted)) {
      if (wasReference) setReference(id, announce = false)
      overlay?.let { saveOverlays(overlays + (id to it)) }
    }
  }

  /** A track deleted for good: its 起算点 kept on this phone goes too. */
  private fun forgetTrack(id: Long) = prefs.edit().remove(PREF_TRACK_REVERSED + id).remove(PREF_TRACK_START + id).apply()

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
      runCatching { giveTeamTrack(t.id, id) }.onFailure { runOnUiThread { hint = failHint(R.string.result_start_not_sent, reasonOf(it.errorCode)) { saveTrackStart(id, start) } } }
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

  /** 取消参考, with 撤销 (§8.3 第 8 条), which puts it back quietly. */
  private fun stopReference() {
    val before = referenceTrack ?: return
    setReference(null)
    hint = Hint(getString(R.string.hint_reference_stopped), listOf(getString(R.string.undo) to { setReference(before, announce = false) }))
  }

  private fun setReference(id: Long?, announce: Boolean = true) {
    val before = referenceTrack
    getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong(PREF_REFERENCE, id ?: 0L).apply()
    referenceTrack = id
    if (id == null) { referenceDrawer = false; endStartPick() }
    // The service only notices the change on its next fix; don't leave an alert for the old one up until then.
    getSystemService(android.app.NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION)
    // ponytail: alerts ride on the recording service's GPS; a separate follow-only service if people follow without recording.
    // C2-77, with 撤销 back to the one before.
    if (id != null && announce) {
      hint = Hint(getString(R.string.hint_reference_set), listOf(getString(R.string.undo) to { setReference(before, announce = false) }))
    }
  }

  /** Track [id]'s 沿线 package requests, one per 坐标来源: under any it's the same package (the 2 km corridor dwarfs the shift, #112). */
  private fun corridorRequests(id: Long): List<String> = TrackDb(this).use { db -> Datum.entries.map { trackRequest(db.segments(id, it)) } }

  /** 公开轨迹 (§2.8) or 撤回: the server gets it with 同步, so that comes first. */
  private fun togglePublic(id: Long) {
    // C2-72: straight to 登录 (or 同步), which says why.
    if (account == null || !syncOn) {
      accountPage = true
      return
    }
    // C2-73: the 「已公开」 tag shows or goes in place.
    TrackDb(this).use { db -> db.setPublic(id, !db.isPublic(id)) }
  }

  /** Backed out of 登录: a join it was asked for is dropped, not carried out by a later login. */
  private fun closeLogin() {
    accountPage = false
    teamAfterLogin = null
    loginForTeam = false
  }

  /** The 队伍页 (ux-v2 §4.4): its 群聊, or 建队 / 加入; a login is only asked for on 建队 or 加入 (ux-v2 §8 路径 5). */
  private fun openTeam() {
    teamNote = null
    teamPage = true
    teamInfo = false
    teamJoin = false
  }

  /**
   * 创建队伍 ([code] null) or joins [code], then into its 对话 with no permission in the way (§8.4 第 4 条); logged out,
   * it logs in first and then carries on by itself.
   */
  private fun joinTeam(code: String?) {
    val acct = account ?: run {
      // C4-18: the 登录 page over the 队伍 page says why.
      teamAfterLogin = code.orEmpty()
      loginForTeam = true
      accountPage = true
      return
    }
    teamBusy = code.orEmpty()
    teamNote = null
    thread {
      val t = runCatching { if (code == null) api.createTeam(acct) else api.joinTeam(acct, code) }
      runOnUiThread {
        teamBusy = null
        t.onSuccess {
          prefs.edit().putLong(PREF_TEAM, it.id).apply()
          RecordingService.showTeam(it)
          shareWithTeam(it.id)
          teamPage = true
          teamJoin = false
        }.onFailure {
          // C4-10: a code that isn't there says so by itself, and 重试 won't change that.
          val notThere = it.errorCode == "team_not_found"
          teamNote = if (notThere) getString(R.string.reason_no_team) else errorText(if (code == null) R.string.result_create_team_failed else R.string.result_join_team_failed, it.errorCode)
          teamRetry = { joinTeam(code) }.takeIf { !notThere }
        }
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
        if (t.getOrNull()?.ended != true) {
          if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startTeam(id) else teamWithoutLocation(id)
        }
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

  /** Into the 对话 at once (faded) and off to the server (§8.4 第 14 条). */
  private fun send(o: Outgoing) {
    outbox = outbox + o
    attempt(o)
  }

  /**
   * Sends [o] (uploading its photo first, with progress): gone from the outbox once the server has it; offline it
   * waits for the network ([SendState.Queued]), otherwise ⚠ 没发出 until tapped.
   */
  private fun attempt(o: Outgoing) {
    fun update(f: (Outgoing) -> Outgoing) { outbox = outbox.map { if (it.id == o.id) f(it) else it } }
    val acct = account ?: return update { it.copy(state = SendState.Failed) }
    if (!online) return update { it.copy(state = SendState.Queued) }
    update { it.copy(state = SendState.Sending) }
    thread {
      val sent = runCatching {
        val json = o.json ?: run {
          val jpeg = runCatching { shrinkPhoto(this, o.photo!!) }.getOrElse { throw UnreadablePhoto() }
          val image = api.uploadImage(acct, o.team, jpeg) { p -> runOnUiThread { update { it.copy(progress = p) } } }
          // Uploaded once: a resend only posts the message.
          messageJson("image", image = image).also { j -> runOnUiThread { update { it.copy(json = j) } } }
        }
        api.postMessage(acct, o.team, json)
      }
      runOnUiThread {
        sent.onSuccess { m ->
          outbox = outbox.filter { it.id != o.id }
          RecordingService.team.value?.takeIf { it.id == o.team }?.let { RecordingService.showTeam(it.copy(messages = listOf(m))) }
        // ponytail: no idempotency key, so a timeout after the server stored it can post it twice on 重发; add a client id if it shows.
        }.onFailure { e ->
          // C4-36: sending it again won't help.
          if (e is UnreadablePhoto) {
            outbox = outbox.filter { it.id != o.id }
            hint = failHint(R.string.result_not_sent, R.string.reason_image)
          } else update { it.copy(state = sendStateAfter(e.errorCode, online), progress = null) }
        }
      }
    }
  }

  /** 📍 (C4-37): where I am now, or once there's a fix (to the team it was asked in); without location, it's asked for. */
  private fun sendLocation() {
    val t = RecordingService.team.value ?: return
    currentFix()?.let { return sendFix(it, t.id) }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return run { teamFixAsked = true; shareWithTeam(t.id) }
    if (locatingSince != null) return
    locatingSince = System.currentTimeMillis()
    locatingTeam = t.id
    // GPS may be off (not recording or sharing): one fix of its own, which the wait below picks up.
    @Suppress("DEPRECATION")
    getSystemService(LocationManager::class.java).requestSingleUpdate(LocationManager.GPS_PROVIDER, { oneFix = it }, mainLooper)
  }

  private fun sendFix(fix: Location, team: Long) =
    send(Outgoing(++outgoingIds, team, "location", json = messageJson("location", lat = fix.latitude, lon = fix.longitude, along = teamAlong(fix.latitude, fix.longitude))))

  private fun shareCoordinate(lat: Double, lon: Double) {
    // Shared text still says WGS-84 (§6.5).
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "坐标（WGS-84）：" + coordinateText(lat, lon))
    startActivity(Intent.createChooser(send, "分享坐标"))
  }

  /** 暂停 / 继续 the recording. */
  private fun recordingAction(action: String) = startService(Intent(this, RecordingService::class.java).setAction(action))

  /** 共享我的位置 on or off. */
  private fun setSharing(on: Boolean) {
    // No service without location (#141): sharing on asks for it first.
    if (teamNoLocation) {
      if (on) RecordingService.team.value?.let { teamFixAsked = true; shareWithTeam(it.id) }
      return
    }
    startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_SHARE).putExtra(RecordingService.EXTRA_SHARING, on))
    // C4-59: it took, here at once.
    if (!on) hint = Hint(getString(R.string.hint_stopped_sharing))
  }

  /**
   * 结束行程 went through (#137): ended here at once, not when the socket says so (it may be down); sharing stops and
   * the 对话 shows its card.
   */
  private fun tripEnded(t: Team) {
    RecordingService.showTeam(t.copy(ended = true, messages = emptyList(), members = t.members.map { it.copy(sharing = false, trail = emptyList()) }))
    if (!teamNoLocation) startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_TEAM_ENDED))
    teamNoLocation = false
  }

  /** A picked photo into the 对话: shrunk (§3.2) and uploaded with its progress on the bubble. */
  private fun sendPhoto(uri: Uri) {
    val t = RecordingService.team.value ?: return
    send(Outgoing(++outgoingIds, t.id, "image", photo = uri))
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
  private fun currentFix(): Location? = (RecordingService.lastFix ?: oneFix ?: try {
    getSystemService(LocationManager::class.java).let { it.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: it.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
  } catch (e: SecurityException) {
    null
  })?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }

  /** Starts sharing with team [id] (§2.11), asking for location first if needed, and for 通知 either way (#140). */
  private fun shareWithTeam(id: Long) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
      return startTeam(id)
    }
    teamAfterGrant = id
    askPermissions.launch(
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
  }

  /** In team [id] without location: shown as not sharing on the server too, until it's allowed (#141). */
  private fun teamWithoutLocation(id: Long) {
    teamNoLocation = true
    val acct = account ?: return
    thread { runCatching { api.setSharing(acct, id, false) } }
  }

  private fun startTeam(id: Long) {
    teamNoLocation = false
    startForegroundService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_TEAM).putExtra(RecordingService.EXTRA_TEAM, id))
  }

  /** Forgets the team here (退出队伍 done, or logged out). */
  private fun quitTeam() {
    // Only a running service has a team to forget; starting one just for that would need location.
    val running = RecordingService.activeTrack.value != null || RecordingService.team.value?.ended == false
    val id = prefs.getLong(PREF_TEAM, 0L)
    prefs.edit().remove(PREF_TEAM).apply()
    teamNoLocation = false
    outbox = emptyList()
    highlightedMate = null
    // Without the service, the trip's reports become a track here (§2.11); with it, it does that on leaving.
    if (!running && id != 0L) RecordingService.endTrip(this, id, recording = false)
    RecordingService.showTeam(null)
    if (running && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_TEAM_QUIT))
    }
  }

  private fun fetchMe(acct: Account) = thread {
    runCatching { api.me(acct) }.onSuccess { (name, avatar) -> runOnUiThread { keepNickname(name); keepAvatar(avatar) } }
  }

  /** The 头像 id as the server last said it (null: none or logged out), kept for the next start. */
  private fun keepAvatar(id: String?) {
    myAvatar = id
    prefs.edit().putString(PREF_AVATAR, id).apply()
  }

  /**
   * 换头像 (§8.6 第 10 条): the spinner on the 头像 meanwhile; kept here too, so it shows offline. A failure keeps the
   * old one and says so with 重试 (C6-35).
   */
  private fun uploadAvatar(jpeg: ByteArray): Unit = changeAvatar({ api.setAvatar(it, jpeg).also { id -> avatars.keep(id, jpeg) } }) { uploadAvatar(jpeg) }

  private fun dropAvatar(): Unit = changeAvatar({ api.dropAvatar(it); null }, ::dropAvatar)

  private fun changeAvatar(call: (Account) -> String?, retry: () -> Unit) {
    val acct = account ?: return
    if (avatarBusy) return
    avatarBusy = true
    thread {
      val result = runCatching { call(acct) }
      runOnUiThread {
        avatarBusy = false
        result.onSuccess(::keepAvatar).onFailure { hint = failHint(R.string.result_avatar_not_changed, reasonOf(it.errorCode), retry) }
      }
    }
  }

  /** When 同步 last went through, 「14:05」, if ever. */
  private fun lastSyncText() = prefs.getLong(PREF_SYNC_LAST, 0L).takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it)) }

  /** The 昵称 as the server last said it (null: logged out), kept for the next start. */
  private fun keepNickname(name: String?) {
    nickname = name
    prefs.edit().putString(PREF_NICKNAME, name).apply()
  }

  /** §2.12 退出登录: local data stays; the server forgets the token when it can be reached. */
  private fun logout() {
    val old = account ?: return
    quitTeam()
    setSync(false)
    accounts.set(null)
    account = null
    keepNickname(null)
    keepAvatar(null)
    avatars.clear()
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

  /** C1-04: location refused for good, only its settings can give it. */
  private fun locationDeniedHint() = Hint(getString(R.string.hint_location_denied), listOf(getString(R.string.action_open_settings) to ::openAppSettings))
  private fun answerIntro() = prefs.edit().putBoolean(PREF_INTRO_ANSWERED, true).apply()
  private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean("startAfterSwitch", startAfterSwitch)
    outState.putBoolean("startAfterGrant", startAfterGrant)
    outState.putLong("resumeAfterGrant", resumeAfterGrant ?: 0L)
    outState.putLong("editing", editing ?: 0L)
    outState.putString("editName", editName)
    outState.putString("editDescription", editDescription)
    outState.putBoolean("trackPage", trackPage)
    outState.putBoolean("trackFull", trackFull)
    outState.putInt("trackTab", trackTab)
    outState.putLong("openGroup", openGroup ?: 0L)
    outState.putLong("detailTrack", detailTrack ?: 0L)
  }

  /**
   * Starts recording, or continues unfinished track [resume] in a new segment. The 出发前检查's first two stop it
   * (§8.3 第 2 条, #142): without precise location the system asks, with location off its settings open (no GMS to ask
   * in place); done there, it starts by itself.
   */
  private fun record(resume: Long?) {
    resumeAfterGrant = resume
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      startAfterGrant = true
      return askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    readLocationOn()
    if (!locationOn) {
      startAfterSwitch = true
      return startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
    startRecording(resume)
  }

  private fun startRecording(resume: Long?) {
    startForegroundService(Intent(this, RecordingService::class.java).apply { if (resume != null) putExtra(RecordingService.EXTRA_TRACK, resume) })
    if (resume == null) {
      buzz()
      // C3-01: the ▶ turning into ⏸ says it; in a team, that they can see me (C3-02), before the 出发前检查's reminders.
      if (RecordingService.team.value?.ended == false) hint = Hint(getString(R.string.hint_team_sees_you))
      remind()
    } else {
      unfinishedTrack = null
      // §8.3 第 18 条: going on after it was cut off, the battery is brought up whatever was skipped.
      if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) showReminders(listOf(Check.Battery))
    }
  }

  /** §8.3 第 18 条: a recording cut off ([id]), its line on the map and a 提示条 waiting: 继续, or 结束 and keep it. */
  private fun offerRecovery(id: Long) {
    unfinishedTrack = id
    hint = Hint(getString(R.string.hint_interrupted), listOf(getString(R.string.resume) to { record(id) }, getString(R.string.end) to { finishUnfinished(id) }), sticky = true)
  }

  /** 结束 on a recording cut off: kept, ended at its last point and named, at once (no confirming: 删除 and 撤销 are there). */
  private fun finishUnfinished(id: Long) {
    unfinishedTrack = null
    thread {
      val savedM = TrackDb(this).use { db ->
        if (db.segments(id).all { it.isEmpty() }) null.also { db.discardTrack(id) }
        else { db.endAtLastPoint(id); nameRecording(db, id); trackStats(db.segments(id)).distanceM }
      }
      runOnUiThread {
        tracksVersion++
        hint = Hint(savedM?.let { getString(R.string.hint_saved, distanceText(it)) } ?: getString(R.string.hint_not_saved_no_fix))
      }
    }
  }

  /** The phone as the 出发前检查 reads it (§8.3 第 1 条), now. */
  private fun phoneState() = PhoneState(
    precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    locationOn = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java)),
    notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
    batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
    offlineCovered = !online || offlineCovered(),
  )

  /**
   * The 参考轨迹 (some 50 points along it), or without one where I am, is in the offline packages; not knowing
   * where I am, it is.
   */
  // ponytail: reads every package's meta and the track on the main thread; fine for a few, cache by filesVersion if not.
  private fun offlineCovered(): Boolean {
    val requests = packages().map { it.request }
    referenceTrack?.let { ref ->
      val points = TrackDb(this).use { it.segments(ref) }.flatten()
      val step = maxOf(1, points.size / 50)
      return points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }.all { covered(it.lat, it.lon, requests) }
    }
    val at = currentFix() ?: return true
    return covered(at.latitude, at.longitude, requests)
  }

  /** Puts [c] right (C6-16): the system's own dialog or settings, or the download. */
  private fun fixCheck(c: Check) {
    prefs.edit().remove(PREF_SKIPS + c.name).apply()
    fixAsked = c == Check.Precise || c == Check.Notifications
    when (c) {
      Check.Precise -> askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
      Check.LocationOn -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      Check.Notifications ->
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openNotificationSettings()
      Check.Battery -> {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
      }
      Check.Offline -> downloadForTrip()
    }
  }

  /** The 离线地图 the 出发前检查 asks for: along the 参考轨迹, else around where I am. */
  private fun downloadForTrip() {
    if (!online) return run { hint = Hint(getString(R.string.reason_offline)) }
    referenceTrack?.let { ref ->
      val (name, segments) = TrackDb(this).use { it.trackName(ref) to it.segments(ref) }
      return downloadPackage(name, trackRequest(segments))
    }
    val at = currentFix() ?: return run { hint = failHint(R.string.result_download_failed, R.string.reason_weak_fix) }
    downloadNearby(at.latitude, at.longitude, null)
  }

  private fun openNotificationSettings() = startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

  /**
   * Once started (§8.3 第 3、4 条): what isn't right but doesn't stop a recording, one 提示条 at a time waiting for an
   * answer, 「1/3」 on each. 跳过 counts; skipped [SKIPS_ENOUGH] times in a row, it isn't brought up on starting again.
   */
  private fun remind() {
    val failing = failing(phoneState())
    // 「连跳」: right since, the count starts over.
    prefs.edit().apply { for (c in Check.entries) if (c !in failing) remove(PREF_SKIPS + c.name) }.apply()
    showReminders(reminders(failing, { prefs.getInt(PREF_SKIPS + it.name, 0) }, online))
  }

  /** [due] as the reminders up, in place of any still waiting from before; answered, each leaves the set. */
  private fun showReminders(due: List<Check>) {
    hints = hints - reminderHints.values.toSet()
    reminderHints = due.withIndex().associate { (i, c) ->
      c to Hint(getString(c.remind, i + 1, due.size), listOf(
        getString(c.action) to { reminderHints = reminderHints - c; fixCheck(c) },
        getString(R.string.skip) to { reminderHints = reminderHints - c; prefs.edit().putInt(PREF_SKIPS + c.name, prefs.getInt(PREF_SKIPS + c.name, 0) + 1).apply() },
      ), sticky = true)
    }
    reminderHints.values.forEach { hint = it }
  }

  /** 导出 a track (§8.5 第 14 条) under its name (#146). */
  private fun exportTrack(id: Long, kml: Boolean) =
    export(kml) { db -> Exported(db.trackName(id), db.waypoints(id), db.segments(id), db.planned(id), "分享轨迹") }

  /**
   * 导出 [what] — a track, a 标注组, or the 标注 不在组里 (#72): GPX, or KML. 标注 photos only travel with GPX: then it's a
   * zip of the GPX and a photos/ folder, each photo linked from its <wpt>. Written off the main thread, then the system
   * share; failing, a 提示条 says why (C5-35).
   */
  private fun export(kml: Boolean, what: (TrackDb) -> Exported) {
    exporting = kml
    thread {
      val result = runCatching {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val (name, all, segments, planned, share) = TrackDb(this).use(what)
        val photos = if (kml) emptyList() else all.filter { w -> w.photo?.let { File(it).isFile } == true }
        val waypoints = all.map { w -> w.copy(photo = if (w in photos) "photos/" + File(w.photo!!).name else null) }
        when {
          kml -> File(dir, exportFileName(name, "kml")).apply { writeText(toKml(name, segments, waypoints)) } to "application/vnd.google-earth.kml+xml"
          photos.isEmpty() -> File(dir, exportFileName(name, "gpx")).apply { writeText(toGpx(name, segments, waypoints, planned)) } to "application/gpx+xml"
          else -> File(dir, exportFileName(name, "zip")).apply {
            ZipOutputStream(outputStream()).use { zip ->
              zip.putNextEntry(ZipEntry(exportFileName(name, "gpx")))
              zip.write(toGpx(name, segments, waypoints, planned).toByteArray())
              for (w in photos) {
                zip.putNextEntry(ZipEntry("photos/" + File(w.photo!!).name))
                File(w.photo).inputStream().use { it.copyTo(zip) }
              }
            }
          } to "application/zip"
        }.let { (file, type) -> Triple(FileProvider.getUriForFile(this, "$packageName.files", file), type, share) }
      }
      runOnUiThread {
        exporting = null
        result.onSuccess { (uri, type, share) ->
          detailSheet = null
          groupSheet = null
          val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          startActivity(Intent.createChooser(send, share))
        }.onFailure {
          hint = if (noSpace(it)) failHint(R.string.result_export_failed, R.string.reason_no_space).let { h ->
            Hint(h.text, listOf(getString(R.string.action_clean) to { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }))
          } else failHint(R.string.result_export_not_done) { export(kml, what) }
        }
      }
    }
  }
}

/** What 导出 writes: a name for the file and its document, 标注, and a track's segments if it's one; [share] titles the chooser. */
private data class Exported(
  val name: String, val waypoints: List<Waypoint>, val segments: List<List<TrackPoint>> = emptyList(), val planned: Boolean = false, val share: String = "分享标注",
)

/** What 轨迹详情's ⋮ opens over it (§8.2 第 8 条). */
enum class DetailSheet { Rename, Datum, Public, Export, Trim, Merge, MergeName }

/** Times 轨迹详情 nudged to download along the track (§8.2 第 10 条), and whether any package was ever downloaded. */
private const val PREF_CORRIDOR_NUDGES = "corridor_nudges"
private const val PREF_DOWNLOADED_ANY = "downloaded_any"
/** The 首次打开 介绍 answered, 稍后 or 开定位 (§8.1). */
private const val PREF_INTRO_ANSWERED = "intro_answered"

/** Room the 底栏 and the 参考 窄条 take, for the camera taking in a track just set as 参考. */
private const val REFERENCE_STRIP_DP = 160.0

/** 出发前检查 items skipped in a row, by [Check] name (§8.3 第 4 条). */
private const val PREF_SKIPS = "pretrip_skips_"

/** What the 我的轨迹 drawer shows (ux-v3 §5.5); back goes 标注 → 轨迹详情 → 标注组 → the list → closed. */
sealed interface DrawerPage {
  data object List : DrawerPage
  data class Group(val id: Long) : DrawerPage
  data class Detail(val id: Long) : DrawerPage
  data class Waypoint(val id: Long) : DrawerPage
}

/** The 小抽屉 naming a 标注组 (C5-10, C5-28). */
sealed interface GroupSheet {
  data class New(val moving: Long?) : GroupSheet
  data class Rename(val id: Long) : GroupSheet
  /** 导出 a 标注组's 标注, or (null) those 不在组里 (#72). */
  data class Export(val id: Long?) : GroupSheet
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
 * A teammate on the map (ux-v2 §4.5): their [Avatar] (photo or 首字 on 队友紫, ux-v3 §8.4 第 7 条), a hollow grey ring once
 * they stopped sharing, either edged in [Semantic.stroke]; below 20% battery a small red badge with it.
 */
@Composable
private fun TeammateDot(m: TeamMember, battery: Int?, modifier: Modifier) {
  // 56 dp to tap, the dot in its middle.
  Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.size(28.dp).border(2.dp, semantic.stroke, CircleShape).padding(2.dp)) { Avatar(m.name, m.avatar, 24.dp, sharing = m.sharing) }
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
  LineLayer(id = "$id-casing", source = source, color = const(semantic.stroke.copy(alpha = semantic.stroke.alpha * color.alpha)), width = const(width + 3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))
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

/** A picked photo that couldn't be read: no use sending it again (C4-36). */
private class UnreadablePhoto : Exception()
