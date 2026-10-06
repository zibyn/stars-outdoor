package com.starsdom.trail

import com.starsdom.trail.track.TrackPoint
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  // C2-55…60: the 下载沿线 button by where the track's package stands.
  @Test fun corridorButtonSaysWhereTheTracksPackageStands() {
    val pkg = OfflinePackage(java.io.File("p"), "a", "v1", "{}", 1)
    assertEquals(Corridor.Download, corridor(null, "v1", null, tooLarge = false))
    assertEquals(Corridor.Done, corridor(pkg, "v1", null, tooLarge = false))
    // Not asked yet: no 更新 guessed.
    assertEquals(Corridor.Done, corridor(pkg, null, null, tooLarge = false))
    assertEquals(Corridor.Update, corridor(pkg, "v2", null, tooLarge = false))
    assertEquals(Corridor.Percent(42), corridor(pkg, "v2", 42, tooLarge = false))
    assertEquals(Corridor.TooLarge, corridor(null, "v1", null, tooLarge = true))
  }

  // §8.2 第 12 条: by the box around the track and its 2 km corridor, against the server's 100 × 100 km of area.
  @Test fun tooLargeByTheBoxAroundIt() {
    fun line(dLat: Double, dLon: Double) = listOf(listOf(TrackPoint(0, 30.0, 103.0, null), TrackPoint(0, 30.0 + dLat, 103.0 + dLon, null)))
    // 555 km north, 4 km wide: as the server measures it, small.
    assertFalse(corridorTooLarge(line(5.0, 0.0)))
    assertFalse(corridorTooLarge(line(0.7, 0.8)))
    assertTrue(corridorTooLarge(line(0.9, 1.0)))
    assertFalse(corridorTooLarge(emptyList()))
  }

  @Test fun nearbyIsAbout20KmEachWayAroundThePoint() {
    val (w, south, e, n) = nearbyBbox(30.0, 103.0)
    assertEquals(20_000.0, haversine(TrackPoint(0, 30.0, w, null), TrackPoint(0, 30.0, e, null)), 100.0)
    assertEquals(20_000.0, haversine(TrackPoint(0, south, 103.0, null), TrackPoint(0, n, 103.0, null)), 100.0)
    assertEquals(103.0, (w + e) / 2, 1e-9)
    assertEquals(30.0, (south + n) / 2, 1e-9)
  }

  // §8.6 第 15 条: the server's outline as it came; an older package's, the box it asked for or around its track.
  @Test fun outlineIsTheServersElseTheBoxAskedFor() {
    val dir = kotlin.io.path.createTempDirectory().toFile()
    val square = """{"type":"Polygon","coordinates":[[[107.7,33.9],[107.9,33.9],[107.9,34.1],[107.7,33.9]]]}"""
    writePackage(OfflinePackage(dir, "太白山附近", "v1", bboxRequest(1.0, 2.0, 3.0, 4.0), 7, square))
    val pkg = readPackage(dir)!!
    assertEquals(square, packageOutline(pkg))
    assertEquals(listOf(107.7, 33.9, 107.9, 34.1), outlineBox(packageOutline(pkg)))
    val old = pkg.copy(outline = null)
    assertEquals(listOf(1.0, 2.0, 3.0, 4.0), outlineBox(packageOutline(old)))
    assertTrue(packageOutline(old).startsWith("""{"type":"Polygon""""))
    val track = old.copy(request = trackRequest(listOf(listOf(TrackPoint(0, 30.0, 103.0, null), TrackPoint(0, 30.5, 102.5, null), TrackPoint(0, 30.2, 103.4, null)))))
    assertEquals(listOf(102.5, 30.0, 103.4, 30.5), outlineBox(packageOutline(track)))
    // meta.json from before outlines still reads.
    java.io.File(dir, "meta.json").writeText("""{"name":"a","version":"v1","request":"{}","bytes":1}""")
    assertEquals(null, readPackage(dir)!!.outline)
  }

  // C6-60: 「6.8 MB · 10月3日」, 「6.8 MB · 可更新」, 「42%」 while it downloads.
  @Test fun packageLineSaysSizeAndDateOrWhatsGoingOn() {
    val day = java.util.Calendar.getInstance().apply { set(2026, 9, 3, 12, 0) }.timeInMillis
    assertEquals("6.8 MB · 10月3日", packageLine("6.8 MB", day, day, stale = false, percent = null))
    assertEquals("6.8 MB · 可更新", packageLine("6.8 MB", day, day, stale = true, percent = null))
    assertEquals("42%", packageLine("6.8 MB", day, day, stale = true, percent = 42))
  }

  // #133: no answer isn't no network; a dead connection goes again on a new one, no network doesn't.
  @Test fun networkFailures() {
    assertEquals("timeout", networkCode(SocketTimeoutException()))
    assertEquals("offline", networkCode(UnknownHostException()))
    assertEquals("offline", networkCode(ConnectException()))
    assertEquals("offline", networkCode(SocketException("Connection reset")))
    assertEquals(null, networkCode(IOException("No space left on device")))
    assertTrue(retryable(SocketTimeoutException()))
    assertTrue(retryable(SocketException("Connection reset")))
    assertTrue(retryable(IOException("unexpected end of stream")))
    assertFalse(retryable(UnknownHostException()))
    assertFalse(retryable(ConnectException()))
    assertFalse(retryable(OfflineError("server")))
  }
}
