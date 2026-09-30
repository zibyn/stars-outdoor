package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class HintTest {
  @Test fun hintStaysLongerWithActionsAndLongestRecording() {
    assertEquals(4_000L, hintMs(active = false, actions = false))
    assertEquals(6_000L, hintMs(active = false, actions = true))
    assertEquals(8_000L, hintMs(active = true, actions = false))
    assertEquals(8_000L, hintMs(active = true, actions = true))
  }

  @Test fun stickyHintWaitsForItsAnswerWhileLaterOnesQueue() {
    val confirm = Hint("下载这附近", sticky = true)
    val marked = Hint("已标注")
    val saved = Hint("已保存")
    // A plain one replaces a plain one.
    assertEquals(listOf(saved), queueHint(listOf(marked), saved))
    // Behind a sticky one a plain one waits, only the newest; sticky ones wait in order.
    assertEquals(listOf(confirm, saved), queueHint(queueHint(listOf(confirm), marked), saved))
    val ask = Hint("定位一直不准", sticky = true)
    assertEquals(listOf(confirm, ask, saved), queueHint(queueHint(listOf(confirm, marked), ask), saved))
    // Closing shows the next.
    assertEquals(listOf(marked), queueHint(listOf(confirm, marked), null))
    assertEquals(emptyList<Hint>(), queueHint(emptyList(), null))
  }
}
