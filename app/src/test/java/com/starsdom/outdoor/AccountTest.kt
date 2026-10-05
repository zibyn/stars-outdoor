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

  // C6-37: trimmed, 1–12 characters, an emoji one each.
  @Test fun nicknameRules() {
    assertEquals("小李🏔", nicknameOf("  小李🏔 "))
    assertEquals(null, nicknameOf("   "))
    assertEquals("一二三四五六七八九十一二", nicknameOf("一二三四五六七八九十一二"))
    assertEquals(null, nicknameOf("一二三四五六七八九十一二三"))
    assertEquals(12, nicknameLength("🏔".repeat(12)))
  }

  // C6-39
  @Test fun phoneShownMasked() = assertEquals("+86 138****8000", maskedPhone("13800138000"))

  // A dot's or avatar's 首字: the first character whole, an emoji included.
  @Test fun initialIsTheFirstCharacter() {
    assertEquals("岩", initial("岩羊27"))
    assertEquals("🏔", initial("🏔小李"))
    assertEquals("", initial(""))
  }
}
