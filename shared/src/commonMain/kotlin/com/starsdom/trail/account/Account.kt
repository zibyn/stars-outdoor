package com.starsdom.trail.account

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth

/** The logged-in account: its number, and the server's bearer token. */
data class Account(val phone: String, val token: String) {
  /** A request as this account. */
  fun auth(): HttpRequestBuilder.() -> Unit = { bearerAuth(token) }
}
