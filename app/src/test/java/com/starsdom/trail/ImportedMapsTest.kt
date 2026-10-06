package com.starsdom.trail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportedMapsTest {
  private fun header(tileType: Int) = ByteArray(127).also {
    "PMTiles".toByteArray().copyInto(it)
    it[7] = 3
    it[99] = tileType.toByte()
  }

  @Test
  fun readsPmtilesTileKindFromHeader() {
    assertEquals(Tiles.Vector, pmtilesKind(header(1)))
    assertEquals(Tiles.Raster, pmtilesKind(header(2)))
    assertEquals(Tiles.Raster, pmtilesKind(header(4)))
    assertNull(pmtilesKind(header(0)))
    assertNull(pmtilesKind("SQLite format 3\u0000".toByteArray() + ByteArray(111)))
  }

  private val base = """
    {"version":8,"sources":{"protomaps":{"type":"vector","url":"pmtiles://file:///d/basemap.pmtiles"}},
     "layers":[
      {"id":"background","type":"background"},
      {"id":"water","type":"fill","source":"protomaps","source-layer":"water"},
      {"id":"roads","type":"line","source":"protomaps","source-layer":"roads"},
      {"id":"contour","type":"line","source":"contours","source-layer":"contours"}]}
  """

  @Test
  fun rasterImportDrawsJustAboveBackground() {
    val style = Json.parseToJsonElement(withImports(base, listOf(Import("mbtiles:///d/imports/sat.mbtiles", Tiles.Raster)))).jsonObject
    val src = style["sources"]!!.jsonObject["import0"]!!.jsonObject
    assertEquals("raster", src["type"]!!.jsonPrimitive.content)
    assertEquals("mbtiles:///d/imports/sat.mbtiles", src["url"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("background", "import0", "water", "roads", "contour"),
      style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun vectorImportReusesBasemapLayers() {
    val style = Json.parseToJsonElement(withImports(base, listOf(Import("pmtiles://file:///d/imports/hk.pmtiles", Tiles.Vector)))).jsonObject
    assertEquals("vector", style["sources"]!!.jsonObject["import0"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    val layers = style["layers"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("background", "water", "water-import0", "roads", "roads-import0", "contour"),
      layers.map { it["id"]!!.jsonPrimitive.content },
    )
    assertEquals("import0", layers[2]["source"]!!.jsonPrimitive.content)
    assertEquals("water", layers[2]["source-layer"]!!.jsonPrimitive.content)
  }
}
