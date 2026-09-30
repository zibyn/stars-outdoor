package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position
import kotlin.math.log2

class FitCameraTest {
  @Test fun fitsTheWiderSideAroundTheCentre() {
    val (at, zoom) = fitCamera(-1.0, -1.0, 1.0, 1.0, 512.0, 1024.0, 0.0, 0.0, 0.0, 0.0)
    assertEquals(log2(180.0), zoom, 1e-6)
    assertEquals(0.0, at.latitude, 1e-9)
    assertEquals(0.0, at.longitude, 1e-9)
  }

  @Test fun keepsTheTrackAboveTheDrawer() {
    // Half the height is drawer: the box fills the top half, so the screen centre is its south edge.
    val (at, zoom) = fitCamera(-1.0, -1.0, 1.0, 1.0, 4096.0, 512.0, 0.0, 0.0, 0.0, 256.0)
    assertEquals(-1.0, at.latitude, 1e-6)
    assertEquals(0.0, at.longitude, 1e-9)
    assertEquals(log2(180.0) - 1, zoom, 0.01)
  }

  @Test fun aSinglePointDoesNotZoomForever() {
    assertEquals(16.0, fitCamera(107.0, 34.0, 107.0, 34.0, 400.0, 800.0, 40.0, 80.0, 40.0, 400.0).second, 0.0)
  }
}
