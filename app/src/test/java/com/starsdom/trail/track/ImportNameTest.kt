package com.starsdom.trail.track

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportNameTest {
  @Test
  fun importNameUsesFileName() {
    fun t(name: String) = ParsedTrack(name, false, emptyList())
    assertEquals("武功山", importName(t("导航线片段1"), "武功山.gpx", 0, 1))
    assertEquals("武功山 · D1", importName(t("D1"), "武功山.gpx", 0, 2))
    assertEquals("武功山 2", importName(t(""), "武功山.gpx", 1, 2))
  }
}
