package com.starsdom.trail.map

import androidx.compose.ui.graphics.toArgb
import com.starsdom.trail.offline.storage
import com.starsdom.trail.ui.DarkSemantic
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The three basemap cards in the layer drawer (§2.2), in order (C2-119), with what each needs (C2-120, C2-121). */
enum class Basemap(val label: String, val offlineNote: String) {
  Standard("标准", "要联网"),
  Terrain("地形", "可离线"),
  Satellite("卫星", "要联网"),
}

const val PREF_BASEMAP = "basemap"
const val PREF_CONTOURS = "contours"
const val PREF_HILLSHADE = "hillshade"
const val PREF_NEARBY = "nearby"

/** OpenFreeMap's style, light or [dark] (ux-v3 §2.8). */
fun openFreeMapUrl(dark: Boolean) = "https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty"

// ponytail: a guess from the desk; tune on a real phone at night.
/** 天地图's 标准 map in dark (§2.8): dimmed, not inverted. 卫星 stays as it is. */
const val TIANDITU_DARK_BRIGHTNESS = 0.7

/** The terrain style's dark palette (assets/style-dark.tsv): light colour → dark, a tab between; "# " lines are comments. */
fun darkPalette(tsv: String): Map<String, String> =
  tsv.lines().filter { '\t' in it && !it.startsWith("# ") }.associate { it.split('\t').let { (light, dark) -> light to dark } }

/**
 * The map style for [basemap], from [terrain] (the local style, packages and imports included).
 * 卫星, and 标准 in China, are 天地图 tiles through the API ([apiUrl], which holds the key): the base over the
 * local style (which shows through offline, §1.3) but under hillshade and contours, its 注记 on top. Overseas, 地形 and 标准 are OpenFreeMap ([openFreeMap], its
 * style JSON) with the user's imports on top and no hillshade or contours; until it has been fetched,
 * the local style. [contours], [hillshade] and [nearby] (周边路网, §2.8: the layers named nearby-*) are the
 * overlay switches; the 周边路网 lies over any basemap. In dark ([dark], the terrain style's palette, §2.8) the local layers take it, their
 * text halos [Semantic.stroke]'s; 天地图's 标准 is dimmed; 卫星 is left as it is; [openFreeMap] is then its dark style. Its 公开轨迹 come from the tiles when [online]; offline
 * also from the packages' snapshots (nearby-*-snapshot*), as the tiles then show only what the 地图缓存 holds (#59).
 * Where both have a line it looks darker, as the heat is in how many overlap: accepted. The
 * relief and hillshade come from the server's DEM (dem-remote, #53) online and the local ones offline: drawn
 * twice they would darken a package's area. The vector layers stack instead ([withRemote]).
 */
fun basemapStyle(terrain: String, basemap: Basemap, overseas: Boolean, openFreeMap: String?, apiUrl: String, contours: Boolean, hillshade: Boolean, nearby: Boolean, online: Boolean, dark: Map<String, String>? = null): String {
  val root = Json.parseToJsonElement(terrain).jsonObject
  val sources = root["sources"]!!.jsonObject.toMutableMap()
  val id = { l: JsonObject -> l["id"]!!.jsonPrimitive.content }
  // Package copies are "<id>-pkgN", so a prefix catches them too.
  val layers = root["layers"]!!.jsonArray.map { it.jsonObject }
    .filter { (contours || !id(it).startsWith("contour")) && (hillshade || !id(it).startsWith("hillshade")) && (nearby || !id(it).startsWith("nearby")) }
    .filter { !(online && id(it).startsWith("nearby-") && id(it).contains("-snapshot")) }
    .filter { l -> l["source"]?.jsonPrimitive?.content?.takeIf { it.startsWith("dem") }?.let { (it == "dem-remote") == online } ?: true }
    .map { if (dark == null || basemap == Basemap.Satellite) it else darkened(it, dark) }
  val tianditu = when {
    basemap == Basemap.Satellite -> "img" to "cia"
    basemap == Basemap.Standard && !overseas -> "vec" to "cva"
    else -> null
  }
  fun style(root: JsonObject, sources: Map<String, JsonElement>, layers: List<JsonObject>) =
    JsonObject(root + mapOf("sources" to JsonObject(sources), "layers" to buildJsonArray { layers.forEach { add(it) } })).toString()
  if (tianditu != null) {
    val (base, labels) = tianditu
    for (layer in listOf(base, labels)) sources["tianditu-$layer"] = buildJsonObject {
      put("type", "raster")
      put("tiles", buildJsonArray { add(JsonPrimitive("$apiUrl/v1/tiles/tianditu/$layer/{z}/{x}/{y}")) })
      put("tileSize", 256)
      put("minzoom", 1)
      put("maxzoom", 18)
      put("attribution", "© 天地图")
    }
    fun raster(layer: String) = buildJsonObject {
      put("id", "tianditu-$layer"); put("type", "raster"); put("source", "tianditu-$layer")
      if (dark != null && basemap == Basemap.Standard) put("paint", buildJsonObject { put("raster-brightness-max", TIANDITU_DARK_BRIGHTNESS) })
    }
    // The overlays go above the imagery; overseas only the 周边路网 (§2.2).
    val overlay = { l: JsonObject -> id(l).startsWith("hillshade") || id(l).startsWith("contour") || id(l).startsWith("nearby") }
    // 天地图's 注记 name only the 行政区划 (县, 乡镇, 村, 社区), so the peaks and pois (垭口, 水源, 营地, 景点) go on top of it.
    val (peaks, rest) = layers.partition { id(it).startsWith("peaks") || id(it).startsWith("pois") }
    val (overlays, under) = rest.partition(overlay)
    return style(root, sources, under + raster(base) + overlays.filter { !overseas || id(it).startsWith("nearby") } + raster(labels) + peaks)
  }
  if (overseas && openFreeMap != null) {
    val ofm = Json.parseToJsonElement(openFreeMap).jsonObject
    val source = { l: JsonObject -> l["source"]?.jsonPrimitive?.content }
    val carried = layers.filter { source(it)?.startsWith("import") == true || id(it).startsWith("nearby") }
    return style(ofm, ofm["sources"]!!.jsonObject + sources.filterKeys { k -> carried.any { source(it) == k } }, ofm["layers"]!!.jsonArray.map { it.jsonObject } + carried)
  }
  return style(root, sources, layers)
}

private val darkHalo = "#%06x".format(DarkSemantic.stroke.toArgb() and 0xFFFFFF)

/** [layer] with its paint colours through [palette], text halos [darkHalo]. */
private fun darkened(layer: JsonObject, palette: Map<String, String>): JsonObject {
  val paint = layer["paint"]?.jsonObject ?: return layer
  fun recolour(e: JsonElement, to: (String) -> String): JsonElement = when {
    e is JsonArray -> JsonArray(e.map { recolour(it, to) })
    e is JsonPrimitive && e.isString && e.content.startsWith("#") -> JsonPrimitive(to(e.content))
    else -> e
  }
  return JsonObject(layer + ("paint" to JsonObject(paint.mapValues { (k, v) -> recolour(v) { c -> if ("halo" in k) darkHalo else palette[c] ?: c } })))
}

// ponytail: fetched once and never refreshed; its sources are TileJSON URLs that track OpenFreeMap's releases.
/** OpenFreeMap's style JSON from [url], fetched once and kept in [file] so imports still show offline overseas; null until then. */
suspend fun openFreeMapStyle(file: File, url: String): String? {
  if (!file.exists()) runCatching {
    val json = storage.get(url).also { check(it.status.isSuccess()) }.bodyAsText()
    Json.parseToJsonElement(json).jsonObject["layers"]!!.jsonArray
    File(file.path + ".tmp").apply { writeText(json) }.renameTo(file)
  }
  return runCatching { file.readText() }.getOrNull()
}
