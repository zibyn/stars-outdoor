package dev.stars.outdoor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapsTest {
  // The local terrain style as style() builds it: a package copy and one raster import.
  private val terrain = """
    {"version":8,"glyphs":"asset://fonts/{fontstack}/{range}.pbf","sources":{
      "protomaps":{"type":"vector"},"dem":{"type":"raster-dem"},"contours":{"type":"vector"},"import0":{"type":"raster"}},
     "layers":[
      {"id":"background","type":"background"},
      {"id":"import0","type":"raster","source":"import0"},
      {"id":"relief","type":"color-relief","source":"dem"},
      {"id":"hillshade","type":"hillshade","source":"dem"},
      {"id":"hillshade-pkg0","type":"hillshade","source":"dem-pkg0"},
      {"id":"water","type":"fill","source":"protomaps"},
      {"id":"contour","type":"line","source":"contours"},
      {"id":"contour-label","type":"symbol","source":"contours"},
      {"id":"roads","type":"line","source":"protomaps"}]}
  """
  private val openFreeMap = """
    {"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{"openmaptiles":{"type":"vector"}},
     "layers":[{"id":"ofm-background","type":"background"},{"id":"ofm-roads","type":"line","source":"openmaptiles"}]}
  """

  private fun style(basemap: Basemap, overseas: Boolean = false, ofm: String? = openFreeMap, contours: Boolean = true, hillshade: Boolean = true) =
    Json.parseToJsonElement(basemapStyle(terrain, basemap, overseas, ofm, "https://api.test", contours, hillshade, nearby = false)).jsonObject

  private fun ids(style: JsonObject) = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

  @Test
  fun terrainInChinaIsTheLocalStyle() {
    assertEquals(ids(Json.parseToJsonElement(terrain).jsonObject), ids(style(Basemap.Terrain)))
  }

  @Test
  fun overlaySwitchesDropTheirLayersIncludingPackageCopies() {
    assertEquals(
      listOf("background", "import0", "relief", "water", "roads"),
      ids(style(Basemap.Terrain, contours = false, hillshade = false)),
    )
  }

  @Test
  fun satelliteIsTiandituImageryOverTheLocalStyleUnderContoursWithLabelsOnTop() {
    val s = style(Basemap.Satellite)
    // The local style underneath is what shows offline.
    val local = listOf("background", "import0", "relief", "water", "roads")
    assertEquals(local + listOf("tianditu-img", "hillshade", "hillshade-pkg0", "contour", "contour-label", "tianditu-cia"), ids(s))
    val img = s["sources"]!!.jsonObject["tianditu-img"]!!.jsonObject
    assertEquals("https://api.test/v1/tiles/tianditu/img/{z}/{x}/{y}", img["tiles"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("raster", img["type"]!!.jsonPrimitive.content)
    // Same imagery overseas (天地图 has global coverage), without hillshade and contours.
    assertEquals(local + listOf("tianditu-img", "tianditu-cia"), ids(style(Basemap.Satellite, overseas = true)))
  }

  @Test
  fun standardIsTiandituVectorInChina() {
    assertEquals(
      listOf("background", "import0", "relief", "water", "roads", "tianditu-vec", "hillshade", "hillshade-pkg0", "contour", "contour-label", "tianditu-cva"),
      ids(style(Basemap.Standard)),
    )
  }

  @Test
  fun overseasTerrainAndStandardAreOpenFreeMapWithImportsOnTopAndNoHillshadeOrContours() {
    for (b in listOf(Basemap.Terrain, Basemap.Standard)) {
      val s = style(b, overseas = true)
      assertEquals(listOf("ofm-background", "ofm-roads", "import0"), ids(s))
      assertTrue(s["glyphs"]!!.jsonPrimitive.content.startsWith("https://tiles.openfreemap.org/"))
      assertTrue("import0" in s["sources"]!!.jsonObject)
    }
  }

  @Test
  fun overseasTerrainFallsBackToTheLocalStyleUntilOpenFreeMapIsFetched() {
    assertEquals(ids(style(Basemap.Terrain)), ids(style(Basemap.Terrain, overseas = true, ofm = null)))
  }

  // The 周边路网 layers (§2.8): a package's 徒步线路 and the online 公开轨迹, over the paths and under the labels.
  private val withNearby = terrain.replace(
    "\"import0\":{\"type\":\"raster\"}}",
    "\"import0\":{\"type\":\"raster\"},\"routes-pkg0\":{\"type\":\"geojson\"},\"public-tracks\":{\"type\":\"vector\"}}",
  ).replace(
    "{\"id\":\"roads\",\"type\":\"line\",\"source\":\"protomaps\"}]",
    "{\"id\":\"roads\",\"type\":\"line\",\"source\":\"protomaps\"},{\"id\":\"nearby-routes-pkg0\",\"type\":\"line\",\"source\":\"routes-pkg0\"}," +
      "{\"id\":\"nearby-public\",\"type\":\"line\",\"source\":\"public-tracks\"},{\"id\":\"places\",\"type\":\"symbol\",\"source\":\"protomaps\"}]",
  )

  private fun nearby(basemap: Basemap, on: Boolean, overseas: Boolean = false) =
    Json.parseToJsonElement(basemapStyle(withNearby, basemap, overseas, openFreeMap, "https://api.test", true, true, on)).jsonObject

  @Test
  fun nearbySwitchDropsItsLayersWhenOff() {
    assertEquals(listOf("roads", "nearby-routes-pkg0", "nearby-public", "places"), ids(nearby(Basemap.Terrain, true)).takeLast(4))
    assertEquals(listOf("water", "contour", "contour-label", "roads", "places"), ids(nearby(Basemap.Terrain, false)).takeLast(5))
  }

  @Test
  fun nearbyLayersLieOverTiandituAndOverOpenFreeMapOverseas() {
    assertEquals(
      listOf("tianditu-img", "hillshade", "hillshade-pkg0", "contour", "contour-label", "nearby-routes-pkg0", "nearby-public", "tianditu-cia"),
      ids(nearby(Basemap.Satellite, true)).dropWhile { it != "tianditu-img" },
    )
    assertEquals(listOf("tianditu-img", "nearby-routes-pkg0", "nearby-public", "tianditu-cia"), ids(nearby(Basemap.Satellite, true, overseas = true)).dropWhile { it != "tianditu-img" })
    val ofm = nearby(Basemap.Terrain, true, overseas = true)
    assertEquals(listOf("ofm-background", "ofm-roads", "import0", "nearby-routes-pkg0", "nearby-public"), ids(ofm))
    assertTrue(ofm["sources"]!!.jsonObject.keys.containsAll(listOf("import0", "routes-pkg0", "public-tracks")))
  }
}
