package com.starsdom.trail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class Tiles { Vector, Raster }

val importableExtensions = setOf("pmtiles", "mbtiles")

/** A user-imported MBTiles/PMTiles file, as a MapLibre source url (pmtiles://file:///… or mbtiles:///…). */
data class Import(val url: String, val tiles: Tiles)

/** Tile kind from a PMTiles v3 header (first 127 bytes); null if not PMTiles v3 or unknown tile type. */
fun pmtilesKind(header: ByteArray): Tiles? {
  if (header.size < 127 || String(header, 0, 7) != "PMTiles" || header[7].toInt() != 3) return null
  return when (header[99].toInt()) {
    1 -> Tiles.Vector
    in 2..5 -> Tiles.Raster
    else -> null
  }
}

/**
 * Adds imports to the style. Raster imports draw just above the background (relief/hillshade/vectors on top).
 * Vector imports are assumed to use the Protomaps basemap schema and reuse the basemap's layers.
 */
fun withImports(style: String, imports: List<Import>): String {
  val root = Json.parseToJsonElement(style).jsonObject
  var layers = root["layers"]!!.jsonArray.map { it.jsonObject }
  val sources = root["sources"]!!.jsonObject.toMutableMap()
  imports.forEachIndexed { i, import ->
    val id = "import$i"
    sources[id] = buildJsonObject {
      put("type", if (import.tiles == Tiles.Vector) "vector" else "raster")
      put("url", import.url)
      // ponytail: most user raster tiles are 256px; read tile size from the file if 512px imports show up.
      if (import.tiles == Tiles.Raster) put("tileSize", 256)
    }
    layers = if (import.tiles == Tiles.Raster) {
      val raster = buildJsonObject { put("id", id); put("type", "raster"); put("source", id) }
      layers.take(1) + raster + layers.drop(1)
    } else {
      layers.flatMap { layer ->
        if (layer["source"]?.jsonPrimitive?.content != "protomaps") listOf(layer)
        else listOf(layer, JsonObject(layer + mapOf("id" to JsonPrimitive("${layer["id"]!!.jsonPrimitive.content}-$id"), "source" to JsonPrimitive(id))))
      }
    }
  }
  return JsonObject(root + mapOf("sources" to JsonObject(sources), "layers" to buildJsonArray { layers.forEach { add(it) } })).toString()
}
