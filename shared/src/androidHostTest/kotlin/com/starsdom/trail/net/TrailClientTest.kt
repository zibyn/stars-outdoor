package com.starsdom.trail.net

import com.starsdom.trail.OfflineError
import com.starsdom.trail.retryable
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// What every request gets (#219): the two headers, 强制升级, one retry, failures as OfflineErrors.
class TrailClientTest {
  private val requests = mutableListOf<HttpRequestData>()
  private var outdated = 0

  private fun client(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    trailClient("http://x", "device", 5, MockEngine { requests += it; answer(it) }) { outdated++ }

  private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf("Content-Type", "application/json"))

  private suspend fun failure(block: suspend () -> Unit) = (runCatching { block() }.exceptionOrNull() as OfflineError).code

  @Test fun everyRequestCarriesTheDeviceIdAndVersion() = runTest {
    assertEquals("7", client { json("""{"version":"7"}""") }.getOfflineVersion().body().version)
    assertEquals("http://x/v1/offline/version", requests.single().url.toString())
    assertEquals("device", requests.single().headers["X-Device-Id"])
    assertEquals("5", requests.single().headers["X-Client-Version"])
  }

  @Test fun theLaunchCheckComparesWithMinClientVersion() = runTest {
    val api = client { json("""{"api":"v1","minClientVersion":7}""") }
    assertTrue(api.outdated(5))
    assertFalse(api.outdated(7))
  }

  @Test fun clientOutdatedRaisesThePromptUnlessQuiet() = runTest {
    val api = client { json("""{"error":"client_outdated","minClientVersion":7}""", HttpStatusCode.UpgradeRequired) }
    assertEquals("client_outdated", failure { api.getMe() })
    assertEquals(1, outdated)
    assertEquals("client_outdated", failure { api.getOfflineVersion { quiet() } })
    assertEquals(1, outdated)
  }

  @Test fun anErrorIsItsCodeEvenOneThisBuildDoesNotKnow() = runTest {
    assertEquals("brand_new", failure { client { json("""{"error":"brand_new"}""", HttpStatusCode.BadRequest) }.getMe() })
    assertEquals(null, failure { client { respond("<html>", HttpStatusCode.BadGateway) }.getMe() })
    assertEquals(0, outdated)
  }

  @Test fun aDeadConnectionGoesOnceMore() = runTest {
    val api = client { if (requests.size == 1) throw SocketException("Connection reset") else json("""{"version":"7"}""") }
    assertEquals("7", api.getOfflineVersion().body().version)
    assertEquals(2, requests.size)
  }

  @Test fun aDeadConnectionGoesOnlyOnceMore() = runTest {
    assertEquals("offline", failure { client { throw SocketException("Connection reset") }.getOfflineVersion() })
    assertEquals(2, requests.size)
  }

  @Test fun aPostIsNotSentTwice() = runTest {
    assertEquals("offline", failure { client { throw SocketException("Connection reset") }.postAuthLogout() })
    assertEquals(1, requests.size)
  }

  @Test fun noNetworkIsNotRetried() = runTest {
    assertEquals("offline", failure { client { throw UnknownHostException() }.getOfflineVersion() })
    assertEquals(1, requests.size)
  }

  @Test fun theServerNeverAnsweringIsATimeoutNotOffline() = runTest {
    assertEquals("timeout", failure { client { throw SocketTimeoutException() }.getOfflineVersion() })
    assertEquals("timeout", failure { client { throw ConnectTimeoutException("connect") }.getOfflineVersion() })
  }

  @Test fun theServersAnswerIsNotRetried() = runTest {
    failure { client { json("""{"error":"internal"}""", HttpStatusCode.InternalServerError) }.getOfflineVersion() }
    assertEquals(1, requests.size)
  }

  @Test fun aConnectTimeoutIsNotRetriedLikeNoNetwork() {
    assertTrue(retryable(SocketTimeoutException()))
    assertFalse(retryable(ConnectTimeoutException("connect")))
    assertFalse(retryable(OfflineError("internal")))
  }
}
