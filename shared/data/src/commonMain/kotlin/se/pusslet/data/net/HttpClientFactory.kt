package se.pusslet.data.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.json.Json

/** A body larger than the caller allows; the rest of it is never downloaded. */
class ResponseTooLargeException(limit: Long) : Exception("Response larger than $limit bytes")

/**
 * The body as text, read while it streams and refused past [maxBytes]: a feed or page from an address someone typed can be
 * anything, and a plain `get` keeps the whole body in memory first. Use inside `prepareGet(…).execute { }`.
 */
suspend fun HttpResponse.bodyAsTextAtMost(maxBytes: Long): String {
    if ((contentLength() ?: 0L) > maxBytes) throw ResponseTooLargeException(maxBytes)
    val packet = bodyAsChannel().readRemaining(maxBytes + 1)
    if (packet.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
    return packet.readText(charset() ?: Charsets.UTF_8)
}

/** JSON settings shared by all network adapters: tolerant of unknown/missing fields (crowdsourced data!). */
val NetworkJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Platform engine (OkHttp on Android, Darwin on iOS). */
expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Every outgoing request gets timeouts, retry with exponential backoff on transient errors,
 * and an identifying User-Agent (required by Open Food Facts, polite towards everyone else).
 */
fun createHttpClient(userAgent: String): HttpClient = platformHttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(NetworkJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnServerErrors(maxRetries = 3)
        retryOnException(maxRetries = 3, retryOnTimeout = true)
        exponentialDelay(base = 2.0, maxDelayMs = 20_000)
    }
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, userAgent)
    }
}
