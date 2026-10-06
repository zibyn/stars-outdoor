package com.starsdom.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @Test fun inViewOnlyAboveTheDrawer() {
    val sw = Position(longitude = -10.0, latitude = -10.0)
    val ne = Position(longitude = 10.0, latitude = 10.0)
    assertTrue(inView(-1.0, 1.0, 1.0, 5.0, sw, ne, fromTop = 0.0, toTop = 0.5))
    assertFalse(inView(-1.0, -5.0, 1.0, -1.0, sw, ne, fromTop = 0.0, toTop = 0.5))
    assertTrue(inView(-1.0, -5.0, 1.0, -1.0, sw, ne, fromTop = 0.0, toTop = 1.0))
    assertFalse(inView(-1.0, 1.0, 20.0, 5.0, sw, ne, fromTop = 0.0, toTop = 0.5))
    // Under the top bar.
    assertFalse(inView(-1.0, 1.0, 1.0, 9.5, sw, ne, fromTop = 0.1, toTop = 0.5))
  }
}
