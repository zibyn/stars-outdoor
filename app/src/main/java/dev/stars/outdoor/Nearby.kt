package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 周边路网 (§2.8): three layers, never merged (ADR 0002). Each has a GeoJSON file per offline package
// (routes.geojson, platform.geojson, public-tracks.geojson); the 平台轨迹 and 公开轨迹 also come online,
// in one answer (Api.nearbyTracks, [byKind]).

enum class NearbyKind(val label: String) { Route("徒步线路"), Platform("平台轨迹"), Public("公开轨迹") }

/** A line of the 周边路网 as a whole; [source] credits a 平台轨迹. */
data class NearbyTrack(val kind: NearbyKind, val name: String, val source: String?, val segments: List<List<TrackPoint>>)

/** How far from a tap a line still counts as tapped: 24 dp on screen at [zoom] (512 px tiles), within 10–500 m. */
fun tapRadiusM(lat: Double, zoom: Double): Double =
  (40_075_016.686 * cos(Math.toRadians(lat)) / (512 * 2.0.pow(zoom)) * 24).coerceIn(10.0, 500.0)

/**
 * 经过这里的轨迹: the lines of [collections] (GeoJSON FeatureCollections, each of its kind) passing within [radiusM]
 * of the point, nearest first. A line in several (overlapping packages) comes once, but only within its own kind:
 * the layers are never merged (ADR 0002). An unreadable file or feature adds nothing.
 */
fun nearbyTracks(collections: List<Pair<NearbyKind, String>>, lat: Double, lon: Double, radiusM: Double): List<NearbyTrack> {
  // Metres on a plane around the point: plenty for a few hundred metres.
  val kx = 111_320 * cos(Math.toRadians(lat))
  val ky = 110_540.0
  fun distance(s: List<TrackPoint>): Double = s.indices.minOf { i ->
    val ax = (s[i].lon - lon) * kx
    val ay = (s[i].lat - lat) * ky
    val b = s.getOrNull(i + 1) ?: return@minOf hypot(ax, ay)
    val dx = (b.lon - lon) * kx - ax
    val dy = (b.lat - lat) * ky - ay
    val t = if (dx == 0.0 && dy == 0.0) 0.0 else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    hypot(ax + t * dx, ay + t * dy)
  }
  return collections.flatMap { (kind, json) -> runCatching { lines(kind, json) }.getOrDefault(emptyList()) }
    .distinctBy { it.kind to it.segments }
    .map { it to it.segments.filter { s -> s.isNotEmpty() }.minOfOrNull(::distance) }
    .filter { (_, d) -> d != null && d <= radiusM }
    .sortedBy { it.second }
    .map { it.first }
}

/** Api.nearbyTracks' one FeatureCollection as a 平台轨迹 and a 公开轨迹 one, by each feature's `kind`, for [nearbyTracks]. */
fun byKind(json: String): List<Pair<NearbyKind, String>> {
  val features = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray
  return listOf(NearbyKind.Platform to "platform", NearbyKind.Public to "public").map { (kind, name) ->
    val mine = features.filter { (it.jsonObject["properties"] as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull == name }
    kind to JsonObject(mapOf("type" to JsonPrimitive("FeatureCollection"), "features" to JsonArray(mine))).toString()
  }
}

private fun lines(kind: NearbyKind, json: String): List<NearbyTrack> =
  Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.mapNotNull { f -> runCatching { line(kind, f.jsonObject) }.getOrNull() }

private fun line(kind: NearbyKind, f: JsonObject): NearbyTrack? {
  val geometry = f["geometry"] as? JsonObject ?: return null
  val props = f["properties"] as? JsonObject
  fun prop(key: String) = props?.get(key)?.jsonPrimitive?.contentOrNull
  fun line(c: JsonArray) = c.map { p -> p.jsonArray.let { TrackPoint(0, it[1].jsonPrimitive.double, it[0].jsonPrimitive.double, null) } }
  val coords = geometry["coordinates"]!!.jsonArray
  val segments = when (geometry["type"]?.jsonPrimitive?.content) {
    "LineString" -> listOf(line(coords))
    "MultiLineString" -> coords.map { line(it.jsonArray) }
    else -> return null
  }
  return NearbyTrack(kind, prop("name").orEmpty(), prop("source"), segments)
}

/** A 周边路网 line saved to 我的轨迹 (ux-v2 §6.5): 「{线名} {M月d日}」, or 「路网轨迹 {M月d日}」 when it has no name. */
fun nearbyName(name: String, nowMs: Long): String = name.ifEmpty { "路网轨迹" } + " " + SimpleDateFormat("M月d日", Locale.CHINA).format(Date(nowMs))

/** Offline, 经过这里的轨迹 come from the packages only; lines in the 地图缓存 show on the map but aren't listed (§2.8). */
const val OFFLINE_NEARBY = "离线中：只能列出离线包内的轨迹"

/**
 * 经过这里的轨迹 (§2.8): each can become the 参考轨迹 or be saved to 我的轨迹; a [saved] one's button turns into
 * 「已保存 · 查看」 in place (ux-v2 §6.5), opening it. [note]: [OFFLINE_NEARBY] when offline.
 */
@Composable
fun NearbySheet(
  tracks: List<NearbyTrack>,
  note: String?,
  saved: Map<NearbyTrack, Long>,
  onReference: (NearbyTrack) -> Unit,
  onSave: (NearbyTrack) -> Unit,
  onOpen: (Long) -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    BasicText("经过这里的轨迹", style = TextStyle(fontSize = 18.sp))
    note?.let { BasicText(it, style = TextStyle(color = Color.Gray, fontSize = 12.sp)) }
    LazyColumn(Modifier.heightIn(max = 360.dp)) {
      items(tracks) { t ->
        Column(Modifier.padding(top = 12.dp)) {
          BasicText(t.name.ifEmpty { t.kind.label }, style = TextStyle(fontSize = 16.sp))
          BasicText(listOfNotNull(t.kind.label, t.source, distanceText(trackStats(t.segments).distanceM)).joinToString(" · "), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
          Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("设为参考轨迹", enabled = true, { onReference(t) }, Modifier.weight(1f))
            val id = saved[t]
            if (id != null) PrimaryButton("已保存 · 查看", enabled = true, { onOpen(id) }, Modifier.weight(1f))
            else PrimaryButton("保存到我的轨迹", enabled = true, { onSave(t) }, Modifier.weight(1f))
          }
        }
      }
    }
  }
}
