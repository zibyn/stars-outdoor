package com.starsdom.trail.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.starsdom.trail.track.overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ux-v3 §2.2 对比度目标, for both palettes.
class ThemeTest {
  private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
    return (hi / lo).toDouble()
  }

  private fun check(name: String, fg: Color, bg: Color, min: Double) =
    assertTrue("$name ${"%.2f".format(contrast(fg, bg))} < $min", contrast(fg, bg) >= min)

  private fun check(c: ColorScheme, s: Semantic, theme: String) {
    for ((bg, color) in listOf("surface" to c.surface, "surfaceContainer" to c.surfaceContainer, "surfaceContainerHigh" to c.surfaceContainerHigh)) {
      check("$theme onSurface on $bg", c.onSurface, color, 7.0)
      check("$theme onSurfaceVariant on $bg", c.onSurfaceVariant, color, 4.5)
      check("$theme primary on $bg", c.primary, color, 4.5)
      check("$theme error on $bg", c.error, color, 4.5)
      check("$theme warn on $bg", s.warn, color, 4.5)
      check("$theme outline on $bg", c.outline, color, 3.0)
    }
    check("$theme onPrimary", c.onPrimary, c.primary, 4.5)
    check("$theme onPrimaryContainer", c.onPrimaryContainer, c.primaryContainer, 7.0)
    check("$theme inverseOnSurface", c.inverseOnSurface, c.inverseSurface, 7.0)
    check("$theme inversePrimary", c.inversePrimary, c.inverseSurface, 4.5)
    for ((line, color) in listOf("me" to s.me, "recording" to s.recording, "teammate" to s.teammate, "reference" to s.reference) + s.overlays.map { "overlay" to it })
      check("$theme $line vs stroke", color, s.stroke, 3.0)
  }

  @Test fun light() = check(Light, LightSemantic, "light")

  @Test fun dark() = check(Dark, DarkSemantic, "dark")

  // ux-v3 §2.4: no limit on 叠加, the colours go round: the fifth is rose again.
  @Test fun theFifthOverlayIsRoseAgain() {
    val seven = (1L..7L).fold(mapOf<Long, Int>()) { m, id -> m.overlay(id) }
    for (s in listOf(LightSemantic, DarkSemantic)) {
      assertEquals(4, s.overlays.size)
      assertEquals(s.overlays[0], s.overlay(seven.getValue(5)))
    }
  }
}
