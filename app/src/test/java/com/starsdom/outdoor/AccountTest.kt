package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTest {
  @Test fun phoneAsTypedOrPasted() {
    for (s in listOf("13800138000", "138 0013 8000", "138-0013-8000", "+86 138 0013 8000", "8613800138000", "+86-13800138000", " 13800138000 ")) {
      assertEquals(s, "13800138000", mainlandPhone(s))
    }
  }

  @Test fun onlyMainlandMobiles() {
    for (s in listOf("", "1380013800", "138001380001", "12800138000", "23800138000", "+85291234567", "+1 415 555 0100", "1380013800a")) {
      assertEquals(s, null, mainlandPhone(s))
    }
  }
}
