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
      "online":{"type":"raster","tiles":["https://t/{z}/{x}/{y}"]}},
     "layers":[
      {"id":"background","type":"background"},
      {"id":"hillshade","type":"hillshade","source":"dem"},
      {"id":"roads","type":"line","source":"protomaps","source-layer":"roads"},
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
    val layers = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["source"]?.jsonPrimitive?.content }
    assertEquals(
      listOf(
        "background" to null,
        "hillshade" to "dem", "hillshade-pkg0" to "dem-pkg0", "hillshade-pkg1" to "dem-pkg1",
        "roads" to "protomaps", "roads-pkg0" to "protomaps-pkg0", "roads-pkg1" to "protomaps-pkg1",
        "sat" to "online",
      ),
      layers,
    )
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
}
