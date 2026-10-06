package com.starsdom.trail

/** Thrown with the server's error code (or "offline", "timeout": [networkCode]) for the app's 原因. */
class OfflineError(val code: String?) : Exception(code)

/** A network failure's code: "timeout" when the server never answered, "offline" when it couldn't be reached; null for anything else (a full disk). */
expect fun networkCode(e: Throwable): String?

/** Whether a failed request goes again (#133): the connection it got died (no answer, reset, closed), which a new one may not; no network at all won't change. */
expect fun retryable(e: Throwable): Boolean
