package dev.stars.outdoor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePackagesTest {
  private val base = """
    {"version":8,"sources":{
      "protomaps":{"type":"vector","url":"pmtiles://file://__DIR__/basemap.pmtiles"},
      "dem":{"type":"raster-dem","url":"pmtiles://file://__DIR__/dem.pmtiles","encoding":"terrarium"},
      "routes":{"type":"geojson","data":"file://__DIR__/routes.geojson"},
      "online":{"type":"raster","tiles":["https://t/{z}/{x}/{y}"]}},
     "layers":[
      {"id":"background","type":"background"},
      {"id":"hillshade","type":"hillshade","source":"dem"},
      {"id":"roads","type":"line","source":"protomaps","source-layer":"roads"},
      {"id":"nearby-routes","type":"line","source":"routes"},
      {"id":"sat","type":"raster","source":"online"}]}
  """

  @Test
  fun eachPackageGetsItsOwnCopyOfTheLocalSourcesAndTheirLayers() {
    val style = Json.parseToJsonElement(withPackages(base, listOf("/p/1", "/p/2"))).jsonObject
    val sources = style["sources"]!!.jsonObject
    assertEquals("pmtiles://file:///p/2/dem.pmtiles", sources["dem-pkg1"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    assertEquals("terrarium", sources["dem-pkg1"]!!.jsonObject["encoding"]!!.jsonPrimitive.content)
    assertEquals("pmtiles://file:///p/1/basemap.pmtiles", sources["protomaps-pkg0"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    assertTrue("online-pkg0" !in sources)
    // GeoJSON (the 周边路网) points at its file with data rather than url.
    assertEquals("file:///p/2/routes.geojson", sources["routes-pkg1"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    val layers = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["source"]?.jsonPrimitive?.content }
    assertEquals(
      listOf(
        "background" to null,
        "hillshade" to "dem", "hillshade-pkg0" to "dem-pkg0", "hillshade-pkg1" to "dem-pkg1",
        "roads" to "protomaps", "roads-pkg0" to "protomaps-pkg0", "roads-pkg1" to "protomaps-pkg1",
        "nearby-routes" to "routes", "nearby-routes-pkg0" to "routes-pkg0", "nearby-routes-pkg1" to "routes-pkg1",
        "sat" to "online",
      ),
      layers,
    )
  }

  // #53: 地形 online from the server's tiles, under the local data and each package's copy.
  @Test
  fun remoteLayersGoUnderTheLocalOnesAndTheirPackageCopies() {
    val remote = base.replace("\"online\":", "\"protomaps-remote\":{\"type\":\"vector\",\"tiles\":[\"__API__/v1/tiles/terrain/basemap/{z}/{x}/{y}\"]},\"online\":")
    val style = Json.parseToJsonElement(withPackages(withRemote(remote), listOf("/p/1"))).jsonObject
    assertTrue("protomaps-remote-pkg0" !in style["sources"]!!.jsonObject)
    val layers = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["source"]?.jsonPrimitive?.content }
    assertEquals(
      listOf("roads-remote" to "protomaps-remote", "roads" to "protomaps", "roads-pkg0" to "protomaps-pkg0"),
      layers.filter { it.first.startsWith("roads") },
    )
    assertEquals("roads", style["layers"]!!.jsonArray[layers.indexOf("roads-remote" to "protomaps-remote")].jsonObject["source-layer"]!!.jsonPrimitive.content)
    assertEquals(listOf("hillshade", "hillshade-pkg0"), layers.map { it.first }.filter { it.startsWith("hillshade") })
  }

  @Test
  fun trackRequestIsThinnedButKeepsBothEnds() {
    val points = (0..9999).map { TrackPoint(it.toLong(), 30 + it / 1e4, 100 + it / 1e4, null) }
    val track = Json.parseToJsonElement(trackRequest(listOf(points.take(5000), points.drop(5000)))).jsonObject["track"]!!.jsonArray
    assertTrue(track.size in 1000..MAX_REQUEST_POINTS)
    assertEquals("[100.0,30.0]", track.first().toString())
    assertEquals("[${points.last().lon},${points.last().lat}]", track.last().toString())
  }

  @Test
  fun serverErrorsBecomePlainMessages() {
    assertEquals("该地区暂不支持离线", offlineMessage("region_unsupported"))
    assertTrue(offlineMessage("region_too_large").contains("100 × 100 km"))
    assertTrue(offlineMessage("daily_quota_exceeded").contains("1 GB"))
    assertEquals("下载失败，稍后再试", offlineMessage("internal"))
  }

  @Test fun corridorRowSaysWhereTheTracksPackageStands() {
    val pkg = OfflinePackage(java.io.File("p"), "沿轨迹 a", "v1", "{}", 1)
    assertEquals("未下载", corridorText(null, "v1", null))
    assertEquals("已下载", corridorText(pkg, "v1", null))
    // Not asked yet: no 可更新 guessed.
    assertEquals("已下载", corridorText(pkg, null, null))
    assertEquals("可更新", corridorText(pkg, "v2", null))
    assertEquals("下载中 42%", corridorText(pkg, "v2", 42))
  }
}
