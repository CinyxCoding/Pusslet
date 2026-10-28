package se.pusslet.feature.calendar.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.util.SwedishOrder
import se.pusslet.data.premium.Entitlements
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.RemovedSubscription
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.PreviewResult
import se.pusslet.data.sources.SubscriptionError
import se.pusslet.data.sources.SubscriptionFeeds
import se.pusslet.data.sources.SubscriptionPreview
import se.pusslet.data.sources.SubscriptionStatus
import se.pusslet.data.sources.SubscriptionSync
import kotlin.time.Clock

enum class UrlProblem { Required, Invalid, TooLong }

data class SubscriptionDraft(
    val id: String,
    val url: String = "",
    val name: String = "",
    val personIds: Set<String> = emptySet(),
    val color: MemberColor = MemberColor.Slate,
    val enabled: Boolean = true,
)

/** "Hämta och visa" in the editor. */
sealed interface FeedTest {
    data object Idle : FeedTest

    data object Running : FeedTest

    data class Found(val preview: SubscriptionPreview) : FeedTest

    data class Failed(val error: SubscriptionError) : FeedTest
}

data class SubscriptionEditor(
    val draft: SubscriptionDraft,
    val isNew: Boolean,
    /** What the draft started as, to ask before unsaved changes are thrown away. */
    val original: SubscriptionDraft = draft,
    val test: FeedTest = FeedTest.Idle,
    val urlProblem: UrlProblem? = null,
    val nameMissing: Boolean = false,
    /** The name was typed by hand, so the calendar's own name from a test does not replace it. */
    val nameTyped: Boolean = !isNew,
) {
    val hasChanges: Boolean get() = draft != original
}

data class SubscriptionRow(
    val subscription: CalendarSubscription,
    val people: List<FamilyPerson>,
    val status: SubscriptionStatus?,
    /** When the events shown were downloaded, on this phone's clock. */
    val fetchedAt: LocalDateTime?,
)

/** A receipt to show once: "Skolan borttagen · Ångra". */
data class Removal(val token: Long, val name: String)

data class SubscriptionsUiState(
    val loading: Boolean = true,
    /** Parents only: caregivers cannot read or change icsSubscriptions (firestore.rules). */
    val canManage: Boolean = false,
    val rows: List<SubscriptionRow> = emptyList(),
    val people: List<FamilyPerson> = emptyList(),
    val today: LocalDate? = null,
    val refreshing: Boolean = false,
    /** The add/edit screen, when open. */
    val editor: SubscriptionEditor? = null,
    val removal: Removal? = null,
    /** The free version's number of subscriptions is reached (never today, see [Entitlements]): no new ones. */
    val atFreeLimit: Boolean = false,
)

/**
 * "Prenumerationer": the family's ICS subscriptions with their last refresh, an on/off switch per calendar,
 * "Uppdatera nu" and the add/edit screen with "Hämta och visa". Fetching on save is the app-wide
 * [se.pusslet.data.sources.SubscriptionRefresher]'s job (it sees a new, switched-on or re-linked subscription), so this
 * ViewModel runs no timers. Deleting can be undone, events included.
 */
class SubscriptionsViewModel(
    private val calendar: CalendarRepository,
    family: FamilyRepository,
    private val feeds: SubscriptionFeeds,
    private val sessions: SessionProvider,
    private val entitlements: Entitlements,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val editor = MutableStateFlow<SubscriptionEditor?>(null)
    private val refreshing = MutableStateFlow(false)
    private val removal = MutableStateFlow<Removal?>(null)
    private var removed: RemovedSubscription? = null
    private var removals = 0L
    private var testJob: Job? = null

    private val canManage: Boolean get() = sessions.session.value.role == Role.Parent

    private data class Content(val canManage: Boolean, val rows: List<SubscriptionRow>, val people: List<FamilyPerson>)

    private val content =
        combine(calendar.subscriptions, calendar.subscriptionStatuses(), family.people, sessions.session) { subs, statuses, people, s ->
            val parent = s.role == Role.Parent
            val ordered = people.sortedBy { !it.isChild }
            val rows = if (!parent) {
                emptyList()
            } else {
                subs.sortedBy { SwedishOrder.key(it.name) }.map { sub ->
                    val status = statuses[sub.id]
                    SubscriptionRow(
                        subscription = sub,
                        people = ordered.filter { it.id in sub.personIds },
                        status = status,
                        fetchedAt = status?.fetchedAt?.toLocalDateTime(zone),
                    )
                }
            }
            Content(parent, rows, ordered)
        }

    val state: StateFlow<SubscriptionsUiState> = combine(content, editor, refreshing, removal, entitlements.current) { c, e, r, rm, ent ->
        SubscriptionsUiState(
            loading = false,
            canManage = c.canManage,
            rows = c.rows,
            people = c.people,
            today = clock.todayIn(zone),
            refreshing = r,
            editor = e.takeIf { c.canManage },
            removal = rm,
            atFreeLimit = !ent.canAddSubscription(c.rows.size),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState(canManage = canManage))

    /** The round +: a new subscription in a colour no person or other subscription has yet. */
    fun add() {
        if (!canManage || !entitlements.current.value.canAddSubscription(state.value.rows.size)) return
        val used = state.value.rows.map { it.subscription.color } + state.value.people.map { it.color }
        val draft = SubscriptionDraft(id = ids.newId(), color = MemberColor.nextFree(used))
        editor.value = SubscriptionEditor(draft, isNew = true)
    }

    fun edit(id: String) {
        if (!canManage) return
        viewModelScope.launch {
            val sub = calendar.subscription(id) ?: return@launch
            val draft = SubscriptionDraft(sub.id, sub.url, sub.name, sub.personIds.toSet(), sub.color, sub.enabled)
            editor.value = SubscriptionEditor(draft, isNew = false)
        }
    }

    fun closeEditor() {
        testJob?.cancel()
        editor.value = null
    }

    fun setUrl(url: String) = editor.update { e ->
        // A result belongs to the link it was fetched from.
        e?.copy(draft = e.draft.copy(url = url), urlProblem = null, test = FeedTest.Idle)
    }

    fun setName(name: String) = editor.update { e ->
        e?.copy(draft = e.draft.copy(name = name), nameMissing = false, nameTyped = name.isNotBlank())
    }

    fun togglePerson(id: String) = editor.update { e ->
        e?.copy(draft = e.draft.copy(personIds = if (id in e.draft.personIds) e.draft.personIds - id else e.draft.personIds + id))
    }

    fun setColor(color: MemberColor) = editor.update { e -> e?.copy(draft = e.draft.copy(color = color)) }

    /** "Hämta och visa": the number of events, the next three and the calendar's own name, or why it did not work. */
    fun testFetch() {
        val current = editor.value ?: return
        val url = current.draft.url.trim()
        val problem = urlProblem(url)
        if (problem != null) {
            editor.value = current.copy(urlProblem = problem)
            return
        }
        testJob?.cancel()
        editor.value = current.copy(test = FeedTest.Running)
        testJob = viewModelScope.launch {
            val result = feeds.preview(url)
            editor.update { e ->
                if (e == null || e.draft.url.trim() != url) {
                    e
                } else {
                    when (result) {
                        is PreviewResult.Failed -> e.copy(test = FeedTest.Failed(result.error))
                        is PreviewResult.Found -> {
                            val name = result.preview.name
                            val draft = if (!e.nameTyped && name != null) e.draft.copy(name = name) else e.draft
                            e.copy(
                                test = FeedTest.Found(result.preview),
                                draft = draft,
                                nameMissing = e.nameMissing && draft.name.isBlank(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun save() {
        val e = editor.value ?: return
        if (!canManage) return
        val url = e.draft.url.trim()
        val name = e.draft.name.trim()
        val problem = urlProblem(url)
        if (problem != null || name.isEmpty()) {
            editor.value = e.copy(urlProblem = problem, nameMissing = name.isEmpty())
            return
        }
        val people = state.value.people.map { it.id }
        val personIds = people.filter { it in e.draft.personIds } + e.draft.personIds.filter { it !in people }
        val sub = CalendarSubscription(
            id = e.draft.id,
            name = name.take(SubscriptionSync.MAX_NAME_LENGTH),
            url = url,
            personIds = personIds,
            color = e.draft.color,
            enabled = e.draft.enabled,
        )
        testJob?.cancel()
        viewModelScope.launch {
            calendar.saveSubscription(sub)
            editor.value = null
        }
    }

    /** "Ta bort" in the editor: gone at once, with "Ångra" in the list. */
    fun delete() {
        val e = editor.value ?: return
        if (!canManage || e.isNew) return
        testJob?.cancel()
        viewModelScope.launch {
            val gone = calendar.deleteSubscription(e.draft.id) ?: return@launch
            removed = gone
            editor.value = null
            removal.value = Removal(++removals, gone.subscription.name)
        }
    }

    fun undoRemove(token: Long) {
        val gone = removed ?: return
        if (removal.value?.token != token) return
        removed = null
        removal.value = null
        viewModelScope.launch { calendar.restoreSubscription(gone) }
    }

    /** The receipt was shown and not undone. */
    fun removalShown(token: Long) {
        if (removal.value?.token != token) return
        removal.value = null
        removed = null
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!canManage) return
        viewModelScope.launch {
            val sub = calendar.subscription(id) ?: return@launch
            if (sub.enabled != enabled) calendar.saveSubscription(sub.copy(enabled = enabled))
        }
    }

    /** "Uppdatera nu": downloads every switched-on subscription again. */
    fun refreshNow() {
        if (!canManage || refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                feeds.refreshAllNow()
            } finally {
                refreshing.value = false
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun urlProblem(url: String): UrlProblem? = when {
            url.isBlank() -> UrlProblem.Required
            url.length > SubscriptionSync.MAX_URL_LENGTH -> UrlProblem.TooLong
            SubscriptionSync.normalizeUrl(url) == null -> UrlProblem.Invalid
            else -> null
        }
    }
}
