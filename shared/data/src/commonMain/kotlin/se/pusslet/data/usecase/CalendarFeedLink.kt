package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import se.pusslet.core.model.Role
import se.pusslet.data.BackendConfig
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.CalendarFeedToken
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import kotlin.uuid.Uuid

/**
 * The family's own calendar feed (BRIEF §3, ADR-041): a secret link to the iCalendar the familyCalendarFeed function
 * serves, for Google Calendar, Apple Calendar or Outlook to subscribe to. A parent makes it; the token (two random UUIDs)
 * is kept on this phone, since the server never lets a client read it back, so "Sluta dela" works from the phone that made
 * it. The link belongs to one family: another family on this phone shows none.
 */
class CalendarFeedLink(
    private val remote: FamilyRemote,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val config: BackendConfig,
) {
    private val log = Logger.withTag("CalendarFeed")

    /** Whether this build has a backend to serve a feed at all. */
    val available: Boolean get() = config.calendarFeedBase != null

    /** The link made on this phone for the current family, or null. */
    val link: Flow<String?> = combine(prefs.observe(Prefs.CalendarFeed), sessions.session) { feed, session ->
        feed.token.takeIf { it.isNotEmpty() && feed.familyId == session.familyId }?.let(::urlFor)
    }

    /** Makes the link (parents only); false when it could not be saved, e.g. without network. */
    suspend fun create(): Boolean {
        val session = sessions.session.value
        val familyId = session.familyId ?: return false
        val uid = session.uid ?: return false
        if (session.role != Role.Parent || !available) return false
        val token = Uuid.random().toHexString() + Uuid.random().toHexString()
        return attempt {
            remote.createCalendarFeed(token, familyId, memberId = null, uid = uid)
            prefs.set(Prefs.CalendarFeed, CalendarFeedToken(familyId, token))
        }
    }

    /** "Sluta dela": the link stops working for every calendar that subscribed to it. */
    suspend fun revoke(): Boolean {
        val feed = prefs.get(Prefs.CalendarFeed)
        if (feed.token.isEmpty()) return true
        return attempt {
            remote.revokeCalendarFeed(feed.token)
            prefs.set(Prefs.CalendarFeed, CalendarFeedToken())
        }
    }

    fun urlFor(token: String): String = "${config.calendarFeedBase}/$token.ics"

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No token in the log.
        log.w { "Calendar feed not changed (${e::class.simpleName})" }
        false
    }
}
