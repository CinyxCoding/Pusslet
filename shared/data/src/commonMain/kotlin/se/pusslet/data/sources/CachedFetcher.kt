package se.pusslet.data.sources

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.net.bodyAsTextAtMost
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A value from an external source with its freshness, so the UI can say "uppdaterad 07:12" or "kunde inte uppdatera". */
data class Sourced<T>(
    val value: T,
    val fetchedAt: Instant,
    /** True when the last refresh failed and this is an older cached value. */
    val stale: Boolean = false,
    val error: String? = null,
)

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * HTTP GET with a Room-backed cache: fresh cache is returned without network, conditional requests use ETag /
 * Last-Modified, and on failure the last good value is returned (marked stale). Sources never crash the app.
 */
class CachedFetcher(
    private val http: HttpClient,
    private val cache: CacheDao,
    private val clock: Clock = Clock.System,
) {
    private val log = Logger.withTag("Fetch")

    suspend fun get(
        key: String,
        url: String,
        maxAge: Duration,
        force: Boolean = false,
        /** A larger answer is a failed fetch (the cached copy stays), never a full memory. */
        maxBytes: Long = DEFAULT_MAX_BYTES,
        validate: (String) -> Boolean = { true },
    ): Sourced<String>? {
        val cached = cache.get(key)
        val now = clock.now()
        if (!force && cached != null && now - Instant.fromEpochMilliseconds(cached.fetchedAt) < maxAge) {
            return Sourced(cached.body, Instant.fromEpochMilliseconds(cached.fetchedAt), stale = false, error = cached.lastError)
        }
        return try {
            download(key, url, cached, maxBytes, validate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "error"
            // The source's name, not the host: a subscribed calendar's address can say who it belongs to.
            log.w { "Fetch failed for ${key.substringBefore(':')}: ${e::class.simpleName}" }
            if (cached != null) {
                cache.setError(key, reason)
                Sourced(cached.body, Instant.fromEpochMilliseconds(cached.fetchedAt), stale = true, error = reason)
            } else {
                null
            }
        }
    }

    /** One HTTP round trip with conditional headers; throws [SourceException] for anything but fresh, valid content. */
    private suspend fun download(
        key: String,
        url: String,
        cached: CacheEntryEntity?,
        maxBytes: Long,
        validate: (String) -> Boolean,
    ): Sourced<String> {
        val now = clock.now()
        // Streamed, so the body is read (and refused) as it comes rather than held whole first.
        val fresh = http.prepareGet(url) {
            cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }.execute { response ->
            when {
                response.status == HttpStatusCode.NotModified && cached != null -> null
                response.status.isSuccess() -> CacheEntryEntity(
                    key = key,
                    body = response.bodyAsTextAtMost(maxBytes),
                    fetchedAt = now.toEpochMilliseconds(),
                    etag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                )
                else -> throw SourceException("HTTP ${response.status.value} from ${hostOf(url)}")
            }
        }
        if (fresh == null) {
            // Not modified (only asked with a cached copy): that copy is fresh again.
            val again = checkNotNull(cached)
            cache.put(again.copy(fetchedAt = now.toEpochMilliseconds(), lastError = null))
            return Sourced(again.body, now)
        }
        if (!validate(fresh.body)) throw SourceException("Unexpected content from ${hostOf(url)}")
        cache.put(fresh)
        return Sourced(fresh.body, now)
    }

    suspend fun peek(key: String): Sourced<String>? = cache.get(key)?.let {
        Sourced(it.body, Instant.fromEpochMilliseconds(it.fetchedAt), stale = it.lastError != null, error = it.lastError)
    }

    /**
     * Stores a value derived from a fetched one (e.g. parsed ICS events). [fetchedAt] is when its source was fetched, so a
     * value re-derived from an old download keeps the old time instead of looking fresh; [error] keeps the source's last error.
     */
    suspend fun store(key: String, body: String, fetchedAt: Instant = clock.now(), error: String? = null) {
        cache.put(CacheEntryEntity(key = key, body = body, fetchedAt = fetchedAt.toEpochMilliseconds(), lastError = error))
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    companion object {
        /** Well above any forecast, product or school list the app reads; feeds pass their own limit. */
        const val DEFAULT_MAX_BYTES = 10L * 1024 * 1024
    }
}
