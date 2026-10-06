package com.starsdom.trail

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Ktor's ConnectTimeoutException is a ConnectException, so timeouts go first.
actual fun networkCode(e: Throwable): String? = when (e) {
  is SocketTimeoutException, is ConnectTimeoutException, is HttpRequestTimeoutException -> "timeout"
  is UnknownHostException, is SocketException -> "offline"
  else -> null
}

actual fun retryable(e: Throwable) = e is IOException && e !is UnknownHostException && e !is ConnectException && e !is NoRouteToHostException

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
