package com.starsdom.trail

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.maplibre.compose.map.DefaultMapRuntime

const val PREF_MAP_CACHE = "map_cache_bytes"
/** 惯用手 (ux-v2 §2.2): true mirrors the 惯用手 side to the left. */
const val PREF_LEFT_HANDED = "left_handed"

/** 地图缓存 (§2.3) size limits offered in 设置. */
val MAP_CACHE_LIMITS = listOf("256 MB" to (256L shl 20), "1 GB" to (1L shl 30), "4 GB" to (4L shl 30))
const val DEFAULT_MAP_CACHE = 1L shl 30

/** Outlives 设置: backing out mustn't cancel a 清除 halfway. */
private val cacheScope = MainScope()

fun mapCacheLimit(context: Context): Long =
  context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getLong(PREF_MAP_CACHE, DEFAULT_MAP_CACHE)

/** MapLibre's ambient cache database (its default place) with its journal files: maplibre-compose has no usage call. */
private fun mapCacheBytes(context: Context): Long =
  context.cacheDir.listFiles { f: File -> f.name.startsWith("maplibre-cache.db") }.orEmpty().sumOf { it.length() }

/**
 * 底栏 → 设置 (一级页, no ←; ux-v3 §8.6 第 1–4 条): me on top (头像, 昵称, 上次同步; logged out a placeholder, 登录 and
 * why), opening 账号 / 登录; 出发前 (出发前检查 with what isn't right, its 小抽屉); 记录 (惯用手, 偏离提醒); 地图
 * (地图缓存, its 小抽屉); 关于 · 版本 at the foot, with 有新版本 when there's an [update] (§2.13).
 */
@Composable
fun SettingsScreen(
  loggedIn: Boolean,
  nickname: String?,
  avatar: String?,
  /** When 同步 last went through (HH:mm), while it's on. */
  lastSync: String?,
  preTripFailing: Set<Check>,
  leftHanded: Boolean,
  onLeftHanded: (Boolean) -> Unit,
  offTrackM: Int,
  onOffTrack: (Int) -> Unit,
  update: Boolean,
  onAccount: () -> Unit,
  onAbout: () -> Unit,
  onPreTrip: () -> Unit,
  onHint: (Hint) -> Unit,
) {
  val context = LocalContext.current
  var used by remember { mutableLongStateOf(mapCacheBytes(context)) }
  var limit by remember { mutableLongStateOf(mapCacheLimit(context)) }
  var cacheSheet by rememberSaveable { mutableStateOf(false) }
  val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
  Box(Modifier.fillMaxSize()) {
    Page(Modifier.padding(horizontal = Space.L).verticalScroll(rememberScrollState())) {
      Text(stringResource(R.string.settings_title), Modifier.padding(vertical = Space.M).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
      // C6-02, C6-03.
      // Logged out, the 登录 button is the one target (TalkBack stops once).
      Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = loggedIn, onClick = onAccount), verticalAlignment = Alignment.CenterVertically) {
        if (loggedIn) Avatar(nickname.orEmpty(), avatar, 48.dp)
        else Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = Space.L)) {
          if (loggedIn) {
            Text(nickname.orEmpty())
            lastSync?.let { Text(stringResource(R.string.last_sync, it), color = onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
          } else Text(stringResource(R.string.login_reason), color = onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (!loggedIn) OutlinedButton(onAccount, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.login_title)) }
        else Icon(R.drawable.chevron_right_wght500_24px, null, tint = onSurfaceVariant)
      }
      Section(R.string.section_pretrip)
      // C6-13: the icons of what isn't right, or ✓.
      SettingRow(R.drawable.check_box_wght500_24px, stringResource(R.string.pretrip), onPreTrip) {
        val allPass = stringResource(R.string.pretrip_all_pass)
        if (preTripFailing.isEmpty()) Text("✓", Modifier.semantics { contentDescription = allPass }, MaterialTheme.colorScheme.primary)
        else for (c in Check.entries.filter { it in preTripFailing }) Icon(c.icon, stringResource(c.label), Modifier.padding(start = Space.XXS), MaterialTheme.colorScheme.error, 20.dp)
        Icon(R.drawable.chevron_right_wght500_24px, null, tint = onSurfaceVariant)
      }
      Section(R.string.section_record)
      // C6-05, C6-06: changed in place.
      ChoiceRow(R.drawable.accessibility_new_wght500_24px, stringResource(R.string.handedness), listOf(stringResource(R.string.hand_left) to true, stringResource(R.string.hand_right) to false), leftHanded, onLeftHanded)
      ChoiceRow(R.drawable.wrong_location_fill1_24px, stringResource(R.string.off_track_alert), OFF_TRACK_CHOICES.map { "$it m" to it }, offTrackM, onOffTrack)
      Section(R.string.section_map)
      // C6-07.
      SettingRow(R.drawable.map_wght500_24px, stringResource(R.string.map_cache), { cacheSheet = true }) {
        Text(stringResource(R.string.map_cache_line, Formatter.formatShortFileSize(context, used), MAP_CACHE_LIMITS.firstOrNull { it.second == limit }?.first ?: Formatter.formatShortFileSize(context, limit)), color = onSurfaceVariant)
        Icon(R.drawable.chevron_right_wght500_24px, null, tint = onSurfaceVariant)
      }
      // C6-14.
      SettingRow(R.drawable.info_wght500_24px, stringResource(R.string.about), onAbout, Modifier.padding(top = Space.L), stringResource(R.string.version, BuildConfig.VERSION_NAME)) {
        if (update) Text(stringResource(R.string.bar_settings_update), color = MaterialTheme.colorScheme.error)
      }
    }
    if (cacheSheet) {
      BackHandler { cacheSheet = false }
      MapCacheSheet(
        used, limit,
        onLimit = { bytes ->
          limit = bytes
          context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putLong(PREF_MAP_CACHE, bytes).apply()
          cacheScope.launch { runCatching { DefaultMapRuntime.instance.offlineManager.setMaximumAmbientCacheSize(bytes) } }
        },
        onCleared = { before ->
          used = mapCacheBytes(context)
          cacheSheet = false
          onHint(Hint(context.getString(R.string.hint_cache_cleared, Formatter.formatShortFileSize(context, (before - used).coerceAtLeast(0)))))
        },
        onClose = { cacheSheet = false },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

/**
 * 地图缓存 (C6-08…12): how much it holds, 上限, and 清除缓存 → 再点一次清除 (can't with nothing in it). A failure stays in
 * the sheet with 重试; done, [onCleared] with what it held before.
 */
@Composable
private fun MapCacheSheet(used: Long, limit: Long, onLimit: (Long) -> Unit, onCleared: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  var clearing by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  fun clear() {
    clearing = true
    failed = false
    // Shrinks the file too (MapLibre packs it), which can take seconds.
    cacheScope.launch {
      runCatching { DefaultMapRuntime.instance.offlineManager.clearAmbientCache() }.onSuccess { onCleared(used) }.onFailure { failed = true }
      clearing = false
    }
  }
  ActionSheet(stringResource(R.string.map_cache), modifier, onClose) {
    Text(stringResource(R.string.map_cache_note, Formatter.formatShortFileSize(context, used)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    ChoiceRow(null, stringResource(R.string.map_cache_limit), MAP_CACHE_LIMITS, limit, onLimit)
    if (failed) PageError(stringResource(R.string.result_clear_not_done), ::clear)
    TapAgain(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_armed), Modifier.align(Alignment.End), enabled = used > 0, busy = clearing, onConfirm = ::clear)
  }
}

/** A block's small heading (C6-04). */
@Composable
private fun Section(title: Int) = Text(
  stringResource(title), Modifier.padding(top = Space.XL, bottom = Space.XXS).semantics { heading() },
  MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
)

/** An M3 list row (§8.6 第 2 条): its icon, [label] (with [sub] in small type under it), and at its end [trailing]; ≥ 56 dp. */
@Composable
private fun SettingRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, trailing: @Composable () -> Unit) =
  Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.weight(1f).padding(horizontal = Space.L)) {
      Text(label)
      sub?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
    trailing()
  }

/** [label] and a 分段按钮 of [choices] changed in place, the picked one filled with ✓ (§8.6 第 2 条); wraps under it when big text needs. */
@Composable
private fun <T> ChoiceRow(@DrawableRes icon: Int?, label: String, choices: List<Pair<String, T>>, picked: T, onPick: (T) -> Unit) =
  FlowRow(Modifier.fillMaxWidth().heightIn(min = 56.dp), Arrangement.SpaceBetween, Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
      icon?.let { Icon(it, null, Modifier.padding(end = Space.L), MaterialTheme.colorScheme.onSurfaceVariant) }
      Text(label)
    }
    // Its natural width, not squeezed into what's left of the line: too wide, FlowRow puts it on the next.
    SingleChoiceSegmentedButtonRow(Modifier.layout { m, c -> m.measure(c.copy(maxWidth = Constraints.Infinity)).let { p -> layout(p.width, p.height) { p.place(0, 0) } } }) {
      choices.forEachIndexed { i, (text, value) ->
        SegmentedButton(value == picked, { onPick(value) }, SegmentedButtonDefaults.itemShape(i, choices.size), Modifier.heightIn(min = 48.dp)) {
          // Room for the ✓, which M3 leaves out when it sizes the segments.
          Text(text, Modifier.padding(horizontal = Space.XS), softWrap = false)
        }
      }
    }
  }
