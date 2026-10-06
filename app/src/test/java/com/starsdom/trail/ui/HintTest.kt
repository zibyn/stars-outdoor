package com.starsdom.trail.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HintTest {
  // ux-v3 §6: 只有结果 4 秒、带操作 8 秒、常驻等回答.
  @Test fun resultsFourSecondsActionsEightStickyUntilAnswered() {
    assertEquals(4_000L, hintMs(Hint("已保存")))
    assertEquals(8_000L, hintMs(Hint("已标注", listOf("撤销" to {}))))
    assertNull(hintMs(Hint("记录中断了", listOf("继续" to {}), sticky = true)))
  }

  // ux-v3 §6: one at a time; a one-shot covers a sticky one for a while, then the sticky one comes back.
  @Test fun oneShotCoversStickyWhichComesBack() {
    val ask = Hint("记录中断了", sticky = true)
    val marked = Hint("已标注")
    val saved = Hint("已保存")
    assertEquals(listOf(saved), queueHint(listOf(marked), saved))
    assertEquals(listOf(marked, ask), queueHint(listOf(ask), marked))
    // A newer one-shot replaces the one covering it.
    assertEquals(listOf(saved, ask), queueHint(listOf(marked, ask), saved))
    // Gone, the sticky one is back.
    assertEquals(listOf(ask), queueHint(listOf(marked, ask), null))
    assertEquals(emptyList<Hint>(), queueHint(emptyList(), null))
  }

  @Test fun stickyOnesWaitInOrderBehindWhatShows() {
    val first = Hint("介绍", sticky = true)
    val second = Hint("出发前检查", sticky = true)
    val marked = Hint("已标注")
    assertEquals(listOf(first, second), queueHint(listOf(first), second))
    assertEquals(listOf(marked, first, second), queueHint(listOf(marked, first), second))
  }
}
