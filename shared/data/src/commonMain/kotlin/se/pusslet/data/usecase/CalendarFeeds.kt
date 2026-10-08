package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import se.pusslet.core.model.Role
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.BackendUnavailableException
import se.pusslet.data.remote.CloudFunctions
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A link this phone created. [memberId] null = the whole family. The token itself is the secret. */
@Serializable
data class FeedLink(val token: String, val memberId: String?, val createdAt: Instant)

enum class FeedAvailability { Ready, NeedsSignIn, NoBackend, ParentsOnly }

/**
 * "Prenumerera på familjekalendern i jobbets Outlook/Google": secret, revocable ICS links served by the calendarFeed
 * function. Links are remembered on the phone that made them; any parent can revoke all of the family's links.
 */
class CalendarFeeds(
    private val remote: FamilyRemote,
    private val functions: CloudFunctions,
    private val auth: AuthRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val config: BackendConfig,
    private val clock: Clock,
    /** Secrets come from a cryptographically secure source, never from the (test-replaceable) id generator. */
    private val newToken: () -> String = { Uuid.random().toHexString() + Uuid.random().toHexString() },
) {
    val links: Flow<List<FeedLink>> = prefs.observe(Prefs.CalendarFeeds)

    val availability: Flow<FeedAvailability> = combine(sessions.session, auth.currentUser) { s, user ->
        when {
            !remote.available -> FeedAvailability.NoBackend
            s.role != Role.Parent -> FeedAvailability.ParentsOnly
            user == null || s.familyId == null -> FeedAvailability.NeedsSignIn
            else -> FeedAvailability.Ready
        }
    }

    fun url(link: FeedLink): String = if (config.mode == BackendMode.Emulator) {
        "http://${config.emulatorHost}:$HOSTING_EMULATOR_PORT/feed/${link.token}.ics"
    } else {
        "https://${config.linkHost}/feed/${link.token}.ics"
    }

    /** Creates a link for the whole family, or only for my own events when [mine] is true. */
    suspend fun create(mine: Boolean): FeedLink {
        val session = sessions.session.value
        val familyId = session.familyId ?: throw BackendUnavailableException("No family")
        val uid = auth.currentUser.value?.uid ?: throw BackendUnavailableException("Not signed in")
        // 64 hex characters: unguessable, URL-safe and well over the 24 the security rules require.
        val token = newToken()
        val link = FeedLink(token, if (mine) session.memberId else null, clock.now())
        remote.createCalendarFeed(token, familyId, link.memberId, uid)
        prefs.update(Prefs.CalendarFeeds) { it + link }
        return link
    }

    suspend fun revoke(link: FeedLink) {
        remote.revokeCalendarFeed(link.token)
        prefs.update(Prefs.CalendarFeeds) { list -> list.filter { it.token != link.token } }
    }

    /** Revokes every link of the family, also ones made on other phones. */
    suspend fun revokeAll(): Int {
        val familyId = sessions.session.value.familyId ?: return 0
        val count = functions.revokeCalendarFeeds(familyId)
        prefs.set(Prefs.CalendarFeeds, emptyList())
        return count
    }

    private companion object {
        const val HOSTING_EMULATOR_PORT = 5000
    }
}
