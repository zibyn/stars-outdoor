package com.starsdom.trail

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.IOException
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet

// Darwin's engine throws NSURLErrorTimedOut as a SocketTimeoutException, the rest as a DarwinHttpRequestException.
private fun urlErrorCode(e: Throwable) = (e as? DarwinHttpRequestException)?.origin?.takeIf { it.domain == NSURLErrorDomain }?.code

/** Reached nothing at all: no network, no such host, nobody listening; a new connection won't fare better. */
private val unreachable = setOf(NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed, NSURLErrorCannotConnectToHost, NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed)

actual fun networkCode(e: Throwable): String? = when {
  e is SocketTimeoutException || e is HttpRequestTimeoutException -> "timeout"
  urlErrorCode(e).let { it in unreachable || it == NSURLErrorNetworkConnectionLost } -> "offline"
  else -> null
}

// NSURLErrorTimedOut doesn't say whether it connected, so unlike Android a connect timeout goes again too.
actual fun retryable(e: Throwable) = e is IOException && urlErrorCode(e) !in unreachable

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
