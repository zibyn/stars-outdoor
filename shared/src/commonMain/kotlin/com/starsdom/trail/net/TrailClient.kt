package com.starsdom.trail.net

import com.starsdom.trail.OfflineError
import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.networkCode
import com.starsdom.trail.retryable
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Quiet = AttributeKey<Unit>("Quiet")

/** Marks background work, whose client_outdated never raises the 强制升级 prompt. */
fun HttpRequestBuilder.quiet() = attributes.put(Quiet, Unit)

/** Whether the server wants a newer build than [clientVersion] (/v1/version, never gated itself). */
suspend fun BaseApi.outdated(clientVersion: Long) = clientVersion < getVersion().body().minClientVersion

/**
 * The API (server/openapi.yaml), generated (ADR 0016), over [engine]. Every request carries [deviceId] and [clientVersion].
 * A failure is an [OfflineError]: the server's code (any, known to this build or not) or [networkCode]'s.
 * client_outdated calls [onOutdated] unless the request is [quiet].
 * A [retryable] failure goes once more (#133), not for POSTs: the server may have done it already (a message sent twice, a 验证码 used up).
 */
fun trailClient(baseUrl: String, deviceId: String, clientVersion: Long, engine: HttpClientEngine, onOutdated: () -> Unit) = BaseApi(
  HttpClientOgen.Base(
    HttpClient(engine) {
      install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
      install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
      }
      defaultRequest {
        header("X-Device-Id", deviceId)
        header("X-Client-Version", clientVersion)
      }
      install(HttpRequestRetry) {
        noRetry()
        retryOnExceptionIf(maxRetries = 1) { request, cause -> request.method != HttpMethod.Post && retryable(cause) }
        delayMillis { 0 }
      }
      HttpResponseValidator {
        validateResponse { response ->
          if (response.status.isSuccess()) return@validateResponse
          val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content }.getOrNull()
          if (code == "client_outdated" && Quiet !in response.call.request.attributes) onOutdated()
          throw OfflineError(code)
        }
        handleResponseExceptionWithRequest { cause, _ -> networkCode(cause)?.let { throw OfflineError(it) } }
      }
    },
    "$baseUrl/v1",
  ),
)
