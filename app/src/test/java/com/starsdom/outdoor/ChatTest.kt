package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTest {
  private fun text(seq: Long, from: Long?, text: String = "x") = TeamMessage(seq, from, "队员$from", 100 + seq, "text", text = text)
  private fun team(vararg messages: TeamMessage, cursor: Long = 0) = Team(7, "4827", 1, me = 1, ended = false, cursor = cursor, members = emptyList(), messages = messages.toList())

  @Test fun parsesMessagesAndBuildsRequests() {
    val json = """{"id":7,"code":"0482","initiator":1,"me":2,"ended":true,"cursor":12,"members":[],"messages":[
      {"seq":3,"from":1,"name":"尾号8000","time":100,"kind":"text","text":"到垭口了"},
      {"seq":5,"name":"已注销","time":130,"kind":"sos","lat":34.5,"lon":108.25,"battery":15},
      {"seq":9,"from":2,"name":"老王","time":160,"kind":"image","image":"ab12"}]}"""
    assertEquals(listOf(
      TeamMessage(3, 1, "尾号8000", 100, "text", text = "到垭口了"),
      TeamMessage(5, null, "已注销", 130, "sos", lat = 34.5, lon = 108.25, battery = 15),
      TeamMessage(9, 2, "老王", 160, "image", image = "ab12"),
    ), parseTeam(json).messages)
    assertEquals("""{"kind":"sos","lat":34.5,"lon":108.25,"battery":15}""", messageJson("sos", lat = 34.5, lon = 108.25, battery = 15))
    assertEquals("""{"kind":"text","text":"好"}""", messageJson("text", text = "好"))
  }

  @Test fun liveMessagesAddToTheChatOnce() {
    // A reconnect may resend what we have; our own sent message arrives before its broadcast.
    val merged = mergeTeam(team(text(3, 2), text(5, 1)), team(text(5, 1), text(8, 2), cursor = 8))
    assertEquals(listOf(3L, 5L, 8L), merged.messages.map { it.seq })
    assertEquals(listOf(8L), mergeTeam(team(text(3, 2)).copy(id = 6), team(text(8, 2))).messages.map { it.seq })
  }

  @Test fun unreadAreTeammatesMessagesAfterTheLastRead() {
    val t = team(text(3, 2), text(5, 1), text(8, 2), text(9, null))
    assertEquals(listOf(8L, 9L), unread(t, readSeq = 5).map { it.seq })
    assertEquals(emptyList<Long>(), unread(t, readSeq = 9).map { it.seq })
  }

  @Test fun photosShrinkToALongSideOf1600() {
    assertEquals(1600 to 1200, fitLongSide(4000, 3000, 1600))
    assertEquals(900 to 1600, fitLongSide(2700, 4800, 1600))
    assertEquals(800 to 600, fitLongSide(800, 600, 1600))
  }

  @Test fun whatAMessageSays() {
    assertEquals("到垭口了", text(1, 2, "到垭口了").summary())
    assertEquals("[位置]", TeamMessage(1, 2, "老王", 0, "location", lat = 1.0, lon = 2.0).summary())
    assertEquals("[图片]", TeamMessage(1, 2, "老王", 0, "image", image = "a").summary())
    assertEquals("在求助 · 电量 15%", TeamMessage(1, 2, "老王", 0, "sos", battery = 15).summary())
    assertEquals("在求助", TeamMessage(1, 2, "老王", 0, "sos").summary())
  }
}
