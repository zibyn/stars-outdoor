package com.starsdom.trail.account

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarTest {
  // A 4000×3000 photo in a 300 px 取景框.
  private val w = 4000
  private val h = 3000
  private val d = 300f

  // C6-34: it opens just covering the circle, centred: the short side fits it.
  @Test fun startsJustCoveringTheCircle() {
    val c = startCrop(w, h, d)
    assertEquals(0.1f, c.scale, 1e-6f)
    assertEquals(Rect(500f, 0f, 3500f, 3000f), c.source(w, h, d))
  }

  // Never smaller than covering the circle, never dragged off it.
  @Test fun staysOverTheCircle() {
    val small = Crop(0.01f, 0f, 0f).clamped(w, h, d)
    assertEquals(0.1f, small.scale, 1e-6f)
    // At 0.1 the photo is 400×300 on screen: 50 px of slack left and right, none up and down.
    val dragged = Crop(0.1f, 500f, -40f).clamped(w, h, d)
    assertEquals(50f, dragged.x, 1e-6f)
    assertEquals(0f, dragged.y, 1e-6f)
    assertEquals(Rect(0f, 0f, 3000f, 3000f), dragged.source(w, h, d))
  }

  // Pinching about a point keeps that point under the fingers.
  @Test fun zoomsAboutTheFingers() {
    val c = startCrop(w, h, d)
    // 100 px right of the circle's centre is photo x 2000 + 100 / 0.1 = 3000.
    val z = c.transformed(Offset(100f, 0f), Offset.Zero, 2f, w, h, d)
    assertEquals(0.2f, z.scale, 1e-6f)
    assertEquals(3000f, w / 2f + (100f - z.x) / z.scale, 1e-3f)
    assertEquals(Rect(1750f, 750f, 3250f, 2250f), z.source(w, h, d))
  }
}
