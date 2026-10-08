package se.pusslet.data.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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
