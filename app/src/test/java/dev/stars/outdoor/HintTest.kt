package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class HintTest {
  @Test fun hintStaysLongerWithActionsAndLongestRecording() {
    assertEquals(4_000L, hintMs(active = false, actions = false))
    assertEquals(6_000L, hintMs(active = false, actions = true))
    assertEquals(8_000L, hintMs(active = true, actions = false))
    assertEquals(8_000L, hintMs(active = true, actions = true))
  }
}
