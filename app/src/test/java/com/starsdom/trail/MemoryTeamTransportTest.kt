package com.starsdom.trail

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The in-memory 队伍传输 keeps the server's rules (server/teams_test.go, chat_test.go), or TeamSessionTest proves nothing.
class MemoryTeamTransportTest {
  private val server = MemoryTeamTransport()
  private val a = server.account(1)
  private val b = server.account(2)

  // One cursor for positions and messages: what's after it is exactly what came since.
  @Test fun positionsAndMessagesShareOneCursor() = runTest {
    val t = server.create(a)
    server.join(b, t.code)
    server.postPositions(b, t.id, listOf(TeamPosition(10, 34.0, 108.0, null)))
    val cursor = server.team(a, t.id, 0).cursor
    server.postMessage(b, t.id, messageJson("text", text = "到了"))
    server.postPositions(b, t.id, listOf(TeamPosition(20, 34.1, 108.0, null)))
    val since = server.team(a, t.id, cursor)
    assertEquals(listOf("到了"), since.messages.map { it.text })
    assertEquals(listOf(20L), since.members.first { it.id == 2L }.trail.map { it.timeS })
    assertEquals(cursor + 2, since.cursor)
  }

  // The stream: the team after the cursor first, each change, then closed once the trip has ended.
  @Test fun theStreamClosesAfterTheEnd() = runTest {
    val t = server.create(a)
    val heard = mutableListOf<Live>()
    val job = launch { server.live(a, t.id, 0).toList(heard) }
    runCurrent()
    server.postMessage(a, t.id, messageJson("text", text = "出发"))
    server.end(a, t.id)
    runCurrent()
    assertTrue(job.isCompleted)
    assertEquals(Live.Open, heard[0])
    val changes = heard.drop(1).map { (it as Live.Change).team }
    assertEquals(listOf(false, false, true), changes.map { it.ended })
    assertEquals(listOf("出发"), changes[1].messages.map { it.text })
    // Ended, a new stream gets the team once and closes.
    assertEquals(2, server.live(a, t.id, 0).toList().size)
  }

  // Not a member (never was, or left): team_not_found, the stream included.
  @Test fun notAMemberIsNotFound() = runTest {
    val t = server.create(a)
    for (call in listOf<suspend () -> Unit>({ server.team(b, t.id, 0) }, { server.live(b, t.id, 0).toList() }, { server.postMessage(b, t.id, messageJson("text", text = "x")) })) {
      try {
        call()
        fail()
      } catch (e: OfflineError) {
        assertEquals("team_not_found", e.code)
      }
    }
  }

  // After 结束行程 positions are refused, the 对话 goes on; a resend with the same key is stored once.
  @Test fun endedRefusesPositionsAndKeysStoreOnce() = runTest {
    val t = server.create(a)
    server.end(a, t.id)
    try {
      server.postPositions(a, t.id, listOf(TeamPosition(10, 34.0, 108.0, null)))
      fail()
    } catch (e: OfflineError) {
      assertEquals("team_ended", e.code)
    }
    val first = server.postMessage(a, t.id, messageJson("text", text = "到家了", key = "k"))
    assertEquals(first, server.postMessage(a, t.id, messageJson("text", text = "到家了", key = "k")))
    assertEquals(1, server.messages(t.id).size)
  }
}
