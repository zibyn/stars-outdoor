package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatumTest {
  private val lat = 39.90923
  private val lon = 116.397428

  @Test
  fun gcj02ReversesTheOffset() {
    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
    // Tiananmen: GCJ-02 sits a few hundred metres off WGS-84.
    assertTrue(gLon - lon in 0.005..0.008 && gLat - lat in 0.0005..0.002)
    val (wLat, wLon) = Datum.GCJ02.toWgs84(gLat, gLon)
    assertEquals(lat, wLat, 1e-7)
    assertEquals(lon, wLon, 1e-7)
  }

  @Test
  fun bd09ReversesTheOffset() {
    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
    val (bLat, bLon) = gcj02ToBd09(gLat, gLon)
    val (wLat, wLon) = Datum.BD09.toWgs84(bLat, bLon)
    assertEquals(lat, wLat, 1e-6)
    assertEquals(lon, wLon, 1e-6)
  }

  @Test
  fun onlyAppliesInsideChina() {
    assertEquals(35.36 to 138.73, Datum.GCJ02.toWgs84(35.36, 138.73)) // Mt Fuji
    assertEquals(35.36 to 138.73, Datum.BD09.toWgs84(35.36, 138.73))
    assertEquals(lat to lon, Datum.WGS84.toWgs84(lat, lon))
  }
}
