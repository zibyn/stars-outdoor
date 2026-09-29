package dev.stars.outdoor

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.maplibre.compose.map.DefaultMapRuntime

const val PREF_MAP_CACHE = "map_cache_bytes"

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

/** 菜单 → 设置: for now the 地图缓存 row: how much it holds, its limit, and 清除. */
@Composable
fun SettingsScreen() {
  val context = LocalContext.current
  var used by remember { mutableLongStateOf(mapCacheBytes(context)) }
  var limit by remember { mutableLongStateOf(mapCacheLimit(context)) }
  var clearing by remember { mutableStateOf(false) }
  val offline = DefaultMapRuntime.instance.offlineManager
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("设置", style = TextStyle(fontSize = 22.sp))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText("地图缓存 " + if (clearing) "清除中…" else Formatter.formatShortFileSize(context, used), Modifier.weight(1f), style = TextStyle(fontSize = 16.sp))
      BasicText(
        "清除",
        Modifier.clickable(enabled = !clearing) {
          clearing = true
          // Shrinks the file too (MapLibre packs it), which can take seconds.
          cacheScope.launch {
            runCatching { offline.clearAmbientCache() }
            used = mapCacheBytes(context)
            clearing = false
          }
        }.padding(8.dp),
        style = TextStyle(color = Color(0xFFE4572E), fontSize = 16.sp),
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText("上限", style = TextStyle(fontSize = 14.sp))
      for ((label, bytes) in MAP_CACHE_LIMITS) BasicText(
        label,
        Modifier.clickable {
          limit = bytes
          context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putLong(PREF_MAP_CACHE, bytes).apply()
          cacheScope.launch { runCatching { offline.setMaximumAmbientCacheSize(bytes) } }
        }.heightIn(min = 48.dp).padding(12.dp),
        style = TextStyle(color = if (bytes == limit) Color(0xFF2F9E6E) else Color.Gray, fontSize = 16.sp),
      )
    }
    BasicText("在线看过的地方离线时尽力显示，最久未用的先删。缓存不保证离线可用，要离线请下载离线包。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
  }
}
