package com.starsdom.outdoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 周边路网 (§2.8): the 徒步线路 and the 公开轨迹, never merged (ADR 0002). Each has a GeoJSON file per offline package
// (routes.geojson, public-tracks.geojson); the 公开轨迹 also come online (Api.nearbyTracks).

enum class NearbyKind(val label: String) { Route("徒步线路"), Public("公开轨迹") }

/** A line of the 周边路网 as a whole. */
data class NearbyTrack(val kind: NearbyKind, val name: String, val segments: List<List<TrackPoint>>)

/** How far from a tap a line still counts as tapped: 24 dp on screen at [zoom] (512 px tiles), within 10–500 m. */
fun tapRadiusM(lat: Double, zoom: Double): Double =
  (metresPerDp(zoom, lat) * 24).coerceIn(10.0, 500.0)

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

private fun lines(kind: NearbyKind, json: String): List<NearbyTrack> =
  Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.mapNotNull { f -> runCatching { line(kind, f.jsonObject) }.getOrNull() }

private fun line(kind: NearbyKind, f: JsonObject): NearbyTrack? {
  val geometry = f["geometry"] as? JsonObject ?: return null
  val name = (f["properties"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
  fun line(c: JsonArray) = c.map { p -> p.jsonArray.let { TrackPoint(0, it[1].jsonPrimitive.double, it[0].jsonPrimitive.double, null) } }
  val coords = geometry["coordinates"]!!.jsonArray
  val segments = when (geometry["type"]?.jsonPrimitive?.content) {
    "LineString" -> listOf(line(coords))
    "MultiLineString" -> coords.map { line(it.jsonArray) }
    else -> return null
  }
  return NearbyTrack(kind, name.orEmpty(), segments)
}

/** A 周边路网 line saved to 我的轨迹 (C2-117): its name, or 「路网轨迹」 when it has none. */
fun nearbyName(name: String): String = name.ifEmpty { "路网轨迹" }

/**
 * 经过这里的轨迹 (§2.8): each can become the 参考轨迹 or be saved to 我的轨迹; a [saved] one's button turns into
 * ［查看］ in place (C2-114…116), opening it. Offline, only the packages' are listed; the 状态条 says so (C2-118).
 */
@Composable
fun NearbySheet(
  tracks: List<NearbyTrack>,
  saved: Map<NearbyTrack, Long>,
  onReference: (NearbyTrack) -> Unit,
  onSave: (NearbyTrack) -> Unit,
  onOpen: (Long) -> Unit,
  modifier: Modifier,
) {
  Sheet(modifier) {
    Text("经过这里的轨迹", style = MaterialTheme.typography.titleLarge)
    LazyColumn(Modifier.heightIn(max = 360.dp)) {
      items(tracks) { t ->
        Column(Modifier.padding(top = 12.dp)) {
          Text(t.name.ifEmpty { t.kind.label })
          Text(listOfNotNull(t.kind.label, distanceText(trackStats(t.segments).distanceM)).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.set_reference), enabled = true, { onReference(t) }, Modifier.weight(1f))
            val id = saved[t]
            if (id != null) PrimaryButton(stringResource(R.string.view), enabled = true, { onOpen(id) }, Modifier.weight(1f))
            else PrimaryButton(stringResource(R.string.save), enabled = true, { onSave(t) }, Modifier.weight(1f))
          }
        }
      }
    }
  }
}
