package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyTest {
  // 太白山穿越 runs north along lon 107.77 from lat 33.95 to 33.97; 泰山 is far away.
  private val routes = """{"type":"FeatureCollection","features":[
    {"type":"Feature","properties":{"name":"太白山穿越","osm_id":"1"},"geometry":{"type":"MultiLineString","coordinates":[[[107.77,33.95],[107.77,33.97]]]}},
    {"type":"Feature","properties":{"name":"泰山十八盘"},"geometry":{"type":"LineString","coordinates":[[117.1,36.2],[117.1,36.25]]}}]}"""
  // 公开轨迹 carry no name; the same one comes from two overlapping packages.
  private val public = """{"type":"FeatureCollection","features":[
    {"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[[[107.7702,33.955],[107.7702,33.965]]]}}]}"""

  private fun near(lat: Double, lon: Double, radiusM: Double) = nearbyTracks(
    listOf(NearbyKind.Route to routes, NearbyKind.Public to public, NearbyKind.Public to public, NearbyKind.Route to "not json"),
    lat, lon, radiusM,
  )

  @Test
  fun linesWithinTheRadiusNearestFirstEachOnce() {
    // 18 m east of the route, 0 from the public track.
    val found = near(33.96, 107.7702, 30.0)
    assertEquals(listOf(NearbyKind.Public to "", NearbyKind.Route to "太白山穿越"), found.map { it.kind to it.name })
    assertEquals(listOf(listOf(33.95 to 107.77, 33.97 to 107.77)), found[1].segments.map { s -> s.map { it.lat to it.lon } })
  }

  @Test
  fun nothingBeyondTheRadius() {
    assertEquals(listOf(NearbyKind.Public), near(33.96, 107.7702, 10.0).map { it.kind })
    assertEquals(emptyList<NearbyTrack>(), near(34.5, 108.0, 500.0))
  }

  @Test
  fun measuresToTheSegmentNotJustItsPoints() {
    // Midway along the route, far from both its points.
    assertEquals(listOf("太白山穿越"), nearbyTracks(listOf(NearbyKind.Route to routes), 33.9601, 107.7701, 15.0).map { it.name })
  }

  @Test
  fun aBrokenFeatureDropsOnlyItself() {
    val mixed = routes.replace("\"features\":[", "\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"}},")
    check(mixed != routes)
    assertEquals(listOf("太白山穿越"), nearbyTracks(listOf(NearbyKind.Route to mixed), 33.96, 107.77, 15.0).map { it.name })
  }

  // C2-117: no date in the name.
  @Test fun savedLinesAreNamedAsTheyAre() {
    assertEquals("鳌太线", nearbyName("鳌太线"))
    assertEquals("路网轨迹", nearbyName(""))
  }
}
