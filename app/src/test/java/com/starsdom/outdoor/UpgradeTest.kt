package com.starsdom.outdoor

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// #118: one 强制升级 prompt for the whole app, raised by the launch check or any client_outdated answer.
class UpgradeTest {
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/v1/version") { it.respond(200, """{"api":"v1","minClientVersion":7}""") }
    createContext("/v1/search") { it.respond(426, """{"error":"client_outdated","minClientVersion":7}""") }
    start()
  }
  private val url = "http://127.0.0.1:${server.address.port}"

  private fun com.sun.net.httpserver.HttpExchange.respond(code: Int, body: String) {
    sendResponseHeaders(code, body.length.toLong())
    responseBody.use { it.write(body.toByteArray()) }
  }

  @Before fun reset() { ClientOutdated.prompt.value = false }
  @After fun stop() = server.stop(0)

  @Test fun anOnlineFeatureAnsweredClientOutdatedRaisesThePrompt() {
    val e = runCatching { Api(url, "d", 5).search("太白山", 34.0, 107.8) }.exceptionOrNull()
    assertEquals("client_outdated", (e as OfflineError).code)
    assertTrue(ClientOutdated.prompt.value)
  }

  @Test fun backgroundCallsStayQuiet() {
    runCatching { Api(url, "d", 5, quiet = true).search("太白山", 34.0, 107.8) }
    assertFalse(ClientOutdated.prompt.value)
  }

  @Test fun theLaunchCheckComparesWithMinClientVersion() {
    assertTrue(Api(url, "d", 5).outdated())
    assertFalse(Api(url, "d", 7).outdated())
  }
}
