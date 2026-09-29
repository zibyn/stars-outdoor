package dev.stars.outdoor

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The three basemap cards in the layer drawer (§2.2), with what each shows offline. */
enum class Basemap(val label: String, val offlineNote: String) {
  Terrain("地形", "国内可离线"),
  Satellite("卫星", "离线仅显示已缓存区域"),
  Standard("标准", "离线仅显示已缓存区域"),
}

const val PREF_BASEMAP = "basemap"
const val PREF_CONTOURS = "contours"
const val PREF_HILLSHADE = "hillshade"
const val PREF_NEARBY = "nearby"

const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * The map style for [basemap], from [terrain] (the local style, packages and imports included).
 * 卫星, and 标准 in China, are 天地图 tiles through the API ([apiUrl], which holds the key): the base over the
 * local style (which shows through offline, §1.3) but under hillshade and contours, its 注记 on top. Overseas, 地形 and 标准 are OpenFreeMap ([openFreeMap], its
 * style JSON) with the user's imports on top and no hillshade or contours; until it has been fetched,
 * the local style. [contours], [hillshade] and [nearby] (周边路网, §2.8: the layers named nearby-*) are the
 * overlay switches; the 周边路网 lies over any basemap. Its 公开轨迹 and 平台轨迹 come from the tiles when [online]; offline
 * also from the packages' snapshots (nearby-*-snapshot*), as the tiles then show only what the 地图缓存 holds (#59).
 * Where both have a line it looks darker, as the heat is in how many overlap: accepted. The
 * relief and hillshade come from the server's DEM (dem-remote, #53) online and the local ones offline: drawn
 * twice they would darken a package's area. The vector layers stack instead ([withRemote]).
 */
fun basemapStyle(terrain: String, basemap: Basemap, overseas: Boolean, openFreeMap: String?, apiUrl: String, contours: Boolean, hillshade: Boolean, nearby: Boolean, online: Boolean): String {
  val root = Json.parseToJsonElement(terrain).jsonObject
  val sources = root["sources"]!!.jsonObject.toMutableMap()
  val id = { l: JsonObject -> l["id"]!!.jsonPrimitive.content }
  // Package copies are "<id>-pkgN", so a prefix catches them too.
  val layers = root["layers"]!!.jsonArray.map { it.jsonObject }
    .filter { (contours || !id(it).startsWith("contour")) && (hillshade || !id(it).startsWith("hillshade")) && (nearby || !id(it).startsWith("nearby")) }
    .filter { !(online && id(it).startsWith("nearby-") && id(it).contains("-snapshot")) }
    .filter { l -> l["source"]?.jsonPrimitive?.content?.takeIf { it.startsWith("dem") }?.let { (it == "dem-remote") == online } ?: true }
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
    fun raster(layer: String) = buildJsonObject { put("id", "tianditu-$layer"); put("type", "raster"); put("source", "tianditu-$layer") }
    // The overlays go above the imagery; overseas only the 周边路网 (§2.2).
    val overlay = { l: JsonObject -> id(l).startsWith("hillshade") || id(l).startsWith("contour") || id(l).startsWith("nearby") }
    val (overlays, under) = layers.partition(overlay)
    return style(root, sources, under + raster(base) + overlays.filter { !overseas || id(it).startsWith("nearby") } + raster(labels))
  }
  if (overseas && openFreeMap != null) {
    val ofm = Json.parseToJsonElement(openFreeMap).jsonObject
    val source = { l: JsonObject -> l["source"]?.jsonPrimitive?.content }
    val carried = layers.filter { source(it)?.startsWith("import") == true || id(it).startsWith("nearby") }
    return style(ofm, ofm["sources"]!!.jsonObject + sources.filterKeys { k -> carried.any { source(it) == k } }, ofm["layers"]!!.jsonArray.map { it.jsonObject } + carried)
  }
  return style(root, sources, layers)
}

// ponytail: fetched once and never refreshed; its sources are TileJSON URLs that track OpenFreeMap's releases.
/** OpenFreeMap's style JSON, fetched once and kept in [file] so imports still show offline overseas; null until then. */
fun openFreeMapStyle(file: File): String? {
  if (!file.exists()) runCatching {
    val json = (URL(OPEN_FREE_MAP_STYLE).openConnection() as HttpURLConnection).run {
      connectTimeout = 15_000
      readTimeout = 30_000
      inputStream.bufferedReader().use { it.readText() }
    }
    Json.parseToJsonElement(json).jsonObject["layers"]!!.jsonArray
    File(file.path + ".tmp").apply { writeText(json) }.renameTo(file)
  }
  return runCatching { file.readText() }.getOrNull()
}
