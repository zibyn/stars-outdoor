package com.starsdom.trail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * What a 队伍传输 does, by the server's rules (server/teams_test.go, chat_test.go): the in-memory one keeps them, or
 * TeamSessionTest proves nothing; the HTTP one, over the network, keeps them too.
 */
abstract class TeamTransportContract {
  /** A fresh server's [transport] and two of its accounts, in a scope to start streams in. */
  class Served(val transport: TeamTransport, val a: Account, val b: Account, scope: CoroutineScope) : CoroutineScope by scope

  abstract fun serve(test: suspend Served.() -> Unit)

  // One cursor for positions and messages: what's after it is exactly what came since.
  @Test fun positionsAndMessagesShareOneCursor() = serve {
    val t = transport.create(a)
    transport.join(b, t.code)
    transport.postPositions(b, t.id, listOf(TeamPosition(10, 34.0, 108.0, null)))
    val cursor = transport.team(a, t.id, 0).cursor
    transport.postMessage(b, t.id, messageJson("text", text = "到了"))
    transport.postPositions(b, t.id, listOf(TeamPosition(20, 34.1, 108.0, null)))
    val since = transport.team(a, t.id, cursor)
    assertEquals(listOf("到了"), since.messages.map { it.text })
    assertEquals(listOf(20L), since.members.first { it.id == 2L }.trail.map { it.timeS })
    assertEquals(cursor + 2, since.cursor)
  }

  // The stream: the team after the cursor first, each change, then closed once the trip has ended.
  @Test fun theStreamClosesAfterTheEnd() = serve {
    val t = transport.create(a)
    val heard = Channel<Live>(Channel.UNLIMITED)
    val job = launch { transport.live(a, t.id, 0).collect { heard.send(it) } }
    assertEquals(Live.Open, heard.receive())
    assertEquals(false, (heard.receive() as Live.Change).team.ended)
    transport.postMessage(a, t.id, messageJson("text", text = "出发"))
    transport.end(a, t.id)
    job.join()
    heard.close()
    val changes = heard.toList().map { (it as Live.Change).team }
    assertEquals(listOf(false, true), changes.map { it.ended })
    assertEquals(listOf("出发"), changes[0].messages.map { it.text })
    // Ended, a new stream gets the team once and closes.
    assertEquals(2, transport.live(a, t.id, 0).toList().size)
  }

  // Not a member (never was, or left): team_not_found, the stream included.
  @Test fun notAMemberIsNotFound() = serve {
    val t = transport.create(a)
    for (call in listOf<suspend () -> Unit>({ transport.team(b, t.id, 0) }, { transport.live(b, t.id, 0).toList() }, { transport.postMessage(b, t.id, messageJson("text", text = "x")) })) {
      try {
        call()
        fail()
      } catch (e: OfflineError) {
        assertEquals("team_not_found", e.code)
      }
    }
  }

  // After 结束行程 positions are refused, the 对话 goes on; a resend with the same key is stored once.
  @Test fun endedRefusesPositionsAndKeysStoreOnce() = serve {
    val t = transport.create(a)
    transport.end(a, t.id)
    try {
      transport.postPositions(a, t.id, listOf(TeamPosition(10, 34.0, 108.0, null)))
      fail()
    } catch (e: OfflineError) {
      assertEquals("team_ended", e.code)
    }
    val first = transport.postMessage(a, t.id, messageJson("text", text = "到家了", key = "k"))
    assertEquals(first, transport.postMessage(a, t.id, messageJson("text", text = "到家了", key = "k")))
    assertEquals(1, transport.team(a, t.id, 0).messages.size)
  }
}

class MemoryTeamTransportTest : TeamTransportContract() {
  override fun serve(test: suspend Served.() -> Unit) = runTest {
    val server = MemoryTeamTransport()
    Served(server, server.account(1), server.account(2), this).test()
  }
}
