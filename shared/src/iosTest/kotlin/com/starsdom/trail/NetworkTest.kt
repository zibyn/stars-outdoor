package com.starsdom.trail

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.SocketTimeoutException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTest {
  private fun urlError(code: Long) = DarwinHttpRequestException(NSError(NSURLErrorDomain, code, null))

  @Test fun noNetworkIsOfflineAndNotRetried() {
    for (code in listOf(NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost)) {
      assertEquals("offline", networkCode(urlError(code)))
      assertFalse(retryable(urlError(code)))
    }
  }

  @Test fun aDroppedConnectionIsOfflineAndRetried() {
    assertEquals("offline", networkCode(urlError(NSURLErrorNetworkConnectionLost)))
    assertTrue(retryable(urlError(NSURLErrorNetworkConnectionLost)))
  }

  @Test fun aTimeoutIsTimeoutAndRetried() {
    val e = SocketTimeoutException("timed out")
    assertEquals("timeout", networkCode(e))
    assertTrue(retryable(e))
  }

  @Test fun anythingElseHasNoCode() {
    assertNull(networkCode(urlError(NSURLErrorSecureConnectionFailed)))
    assertNull(networkCode(IllegalStateException()))
    assertFalse(retryable(IllegalStateException()))
  }
}
