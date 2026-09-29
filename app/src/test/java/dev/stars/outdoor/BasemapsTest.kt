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
  // The local terrain style as style() builds it: the remote copies (#53), a package copy and one raster import.
  private val terrain = """
    {"version":8,"glyphs":"asset://fonts/{fontstack}/{range}.pbf","sources":{
      "dem-remote":{"type":"raster-dem"},"contours-remote":{"type":"vector"},
      "protomaps":{"type":"vector"},"dem":{"type":"raster-dem"},"contours":{"type":"vector"},"import0":{"type":"raster"}},
     "layers":[
      {"id":"background","type":"background"},
      {"id":"import0","type":"raster","source":"import0"},
      {"id":"relief-remote","type":"color-relief","source":"dem-remote"},
      {"id":"relief","type":"color-relief","source":"dem"},
      {"id":"hillshade-remote","type":"hillshade","source":"dem-remote"},
      {"id":"hillshade","type":"hillshade","source":"dem"},
      {"id":"hillshade-pkg0","type":"hillshade","source":"dem-pkg0"},
      {"id":"water","type":"fill","source":"protomaps"},
      {"id":"contour-remote","type":"line","source":"contours-remote"},
      {"id":"contour","type":"line","source":"contours"},
      {"id":"contour-label","type":"symbol","source":"contours"},
      {"id":"roads","type":"line","source":"protomaps"}]}
  """
  private val openFreeMap = """
    {"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{"openmaptiles":{"type":"vector"}},
     "layers":[{"id":"ofm-background","type":"background"},{"id":"ofm-roads","type":"line","source":"openmaptiles"}]}
  """

  private fun style(basemap: Basemap, overseas: Boolean = false, ofm: String? = openFreeMap, contours: Boolean = true, hillshade: Boolean = true, online: Boolean = true) =
    Json.parseToJsonElement(basemapStyle(terrain, basemap, overseas, ofm, "https://api.test", contours, hillshade, nearby = false, online)).jsonObject

  private fun ids(style: JsonObject) = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

  // With the relief and hillshade from both the server and a package, the package's area would look darker online.
  @Test
  fun terrainInChinaIsTheLocalStyleWithTheDemFromTheServerOnlineAndTheLocalFilesOffline() {
    assertEquals(
      listOf("background", "import0", "relief-remote", "hillshade-remote", "water", "contour-remote", "contour", "contour-label", "roads"),
      ids(style(Basemap.Terrain)),
    )
    assertEquals(
      listOf("background", "import0", "relief", "hillshade", "hillshade-pkg0", "water", "contour-remote", "contour", "contour-label", "roads"),
      ids(style(Basemap.Terrain, online = false)),
    )
  }

  @Test
  fun overlaySwitchesDropTheirLayersIncludingPackageCopies() {
    assertEquals(
      listOf("background", "import0", "relief-remote", "water", "roads"),
      ids(style(Basemap.Terrain, contours = false, hillshade = false)),
    )
  }

  @Test
  fun satelliteIsTiandituImageryOverTheLocalStyleUnderContoursWithLabelsOnTop() {
    val s = style(Basemap.Satellite)
    // The local style underneath is what shows offline.
    val local = listOf("background", "import0", "relief-remote", "water", "roads")
    assertEquals(local + listOf("tianditu-img", "hillshade-remote", "contour-remote", "contour", "contour-label", "tianditu-cia"), ids(s))
    val img = s["sources"]!!.jsonObject["tianditu-img"]!!.jsonObject
    assertEquals("https://api.test/v1/tiles/tianditu/img/{z}/{x}/{y}", img["tiles"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("raster", img["type"]!!.jsonPrimitive.content)
    // Same imagery overseas (天地图 has global coverage), without hillshade and contours.
    assertEquals(local + listOf("tianditu-img", "tianditu-cia"), ids(style(Basemap.Satellite, overseas = true)))
  }

  @Test
  fun standardIsTiandituVectorInChina() {
    assertEquals(
      listOf("background", "import0", "relief-remote", "water", "roads", "tianditu-vec", "hillshade-remote", "contour-remote", "contour", "contour-label", "tianditu-cva"),
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
      "{\"id\":\"nearby-public-snapshot-pkg0\",\"type\":\"line\",\"source\":\"public-snapshot-pkg0\"},{\"id\":\"nearby-public\",\"type\":\"line\",\"source\":\"public-tracks\"},{\"id\":\"places\",\"type\":\"symbol\",\"source\":\"protomaps\"}]",
  )

  private fun nearby(basemap: Basemap, on: Boolean, overseas: Boolean = false, online: Boolean = true) =
    Json.parseToJsonElement(basemapStyle(withNearby, basemap, overseas, openFreeMap, "https://api.test", true, true, on, online)).jsonObject

  @Test
  fun nearbySwitchDropsItsLayersWhenOff() {
    assertEquals(listOf("roads", "nearby-routes-pkg0", "nearby-public", "places"), ids(nearby(Basemap.Terrain, true)).takeLast(4))
    assertEquals(listOf("water", "contour-remote", "contour", "contour-label", "roads", "places"), ids(nearby(Basemap.Terrain, false)).takeLast(6))
  }

  @Test
  fun nearbyLayersLieOverTiandituAndOverOpenFreeMapOverseas() {
    assertEquals(
      listOf("tianditu-img", "hillshade-remote", "contour-remote", "contour", "contour-label", "nearby-routes-pkg0", "nearby-public", "tianditu-cia"),
      ids(nearby(Basemap.Satellite, true)).dropWhile { it != "tianditu-img" },
    )
    assertEquals(listOf("tianditu-img", "nearby-routes-pkg0", "nearby-public", "tianditu-cia"), ids(nearby(Basemap.Satellite, true, overseas = true)).dropWhile { it != "tianditu-img" })
    val ofm = nearby(Basemap.Terrain, true, overseas = true)
    assertEquals(listOf("ofm-background", "ofm-roads", "import0", "nearby-routes-pkg0", "nearby-public"), ids(ofm))
    assertTrue(ofm["sources"]!!.jsonObject.keys.containsAll(listOf("import0", "routes-pkg0", "public-tracks")))
  }

  // §2.8: 公开轨迹 online from the tiles, offline from the packages' snapshots; never both, which would darken the heat.
  @Test
  fun publicTracksComeFromTheTilesOnlineAndTheSnapshotsOffline() {
    assertEquals(listOf("roads", "nearby-routes-pkg0", "nearby-public", "places"), ids(nearby(Basemap.Terrain, true)).takeLast(4))
    assertEquals(listOf("roads", "nearby-routes-pkg0", "nearby-public-snapshot-pkg0", "places"), ids(nearby(Basemap.Terrain, true, online = false)).takeLast(4))
  }

  // ADR 0005: 平台轨迹 likewise, from PostGIS both ways.
  @Test
  fun platformTracksComeFromTheTilesOnlineAndTheSnapshotsOffline() {
    val style = withNearby.replace(
      "{\"id\":\"places\"",
      "{\"id\":\"nearby-platform-snapshot-pkg0\",\"type\":\"line\",\"source\":\"platform-snapshot-pkg0\"},{\"id\":\"nearby-platform\",\"type\":\"line\",\"source\":\"platform-tracks\"},{\"id\":\"places\"",
    )
    fun shown(online: Boolean) = ids(Json.parseToJsonElement(basemapStyle(style, Basemap.Terrain, false, null, "https://api.test", true, true, true, online)).jsonObject).filter { it.startsWith("nearby-p") }
    assertEquals(listOf("nearby-public", "nearby-platform"), shown(true))
    assertEquals(listOf("nearby-public-snapshot-pkg0", "nearby-platform-snapshot-pkg0"), shown(false))
  }
}
