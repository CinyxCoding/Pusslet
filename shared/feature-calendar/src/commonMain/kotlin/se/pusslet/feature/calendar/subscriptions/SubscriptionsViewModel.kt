package se.pusslet.feature.calendar.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.pusslet.core.entitlements.Entitlements
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.FeedStatus
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.SubscriptionSync

data class SubscriptionRow(val subscription: CalendarSubscription, val status: FeedStatus, val refreshing: Boolean)

/** The add/edit dialog. [id] null = new. */
data class SubscriptionDraft(
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val personIds: Set<String> = emptySet(),
    val color: MemberColor = MemberColor.Slate,
    val invalidUrl: Boolean = false,
)

data class SubscriptionsUiState(
    val loading: Boolean = true,
    val rows: List<SubscriptionRow> = emptyList(),
    val people: List<FamilyPerson> = emptyList(),
    val draft: SubscriptionDraft? = null,
    val canEdit: Boolean = true,
    /** Shown instead of the add dialog when the free version's limit is reached (only when premium is switched on). */
    val limitReached: Int? = null,
)

/** ICS subscriptions (school platform, sports club, TimeEdit …): read-only calendars refreshed every six hours. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModel(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val sync: SubscriptionSync,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val entitlements: Entitlements,
) : ViewModel() {
    private val refreshing = MutableStateFlow(emptySet<String>())
    private val draft = MutableStateFlow<SubscriptionDraft?>(null)
    private val limitReached = MutableStateFlow<Int?>(null)

    private val rows: Flow<List<SubscriptionRow>> = combine(calendar.subscriptions, refreshing) { subs, busy -> subs to busy }
        .flatMapLatest { (subs, busy) ->
            if (subs.isEmpty()) {
                flowOf(emptyList())
            } else {
                val perSubscription = subs.sortedBy { it.name.lowercase() }.map { s ->
                    calendar.subscriptionStatus(s.id).map { st -> SubscriptionRow(s, st, s.id in busy) }
                }
                combine(perSubscription) { it.toList() }
            }
        }

    val state: StateFlow<SubscriptionsUiState> = combine(rows, family.people, draft, limitReached) { r, p, d, limit ->
        SubscriptionsUiState(false, r, p, d, sessions.session.value.role != Role.Caregiver, limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState())

    fun startNew(prefilledUrl: String? = null) {
        viewModelScope.launch {
            val limit = entitlements.current.value.subscriptionLimit
            if (limit != null && calendar.subscriptions.first().size >= limit) {
                limitReached.value = limit
            } else {
                draft.value = SubscriptionDraft(url = prefilledUrl.orEmpty())
            }
        }
    }

    fun dismissLimit() {
        limitReached.value = null
    }

    fun startEdit(sub: CalendarSubscription) {
        draft.value = SubscriptionDraft(sub.id, sub.name, sub.url, sub.personIds.toSet(), sub.color)
    }

    fun editDraft(transform: (SubscriptionDraft) -> SubscriptionDraft) = draft.update { it?.let(transform)?.copy(invalidUrl = false) }

    fun cancelDraft() {
        draft.value = null
    }

    /** Saves and fetches right away, so the user sees at once whether the link works. */
    fun saveDraft() {
        val d = draft.value ?: return
        val url = SubscriptionSync.normalizeUrl(d.url)
        if (url == null) {
            draft.update { it?.copy(invalidUrl = true) }
            return
        }
        draft.value = null
        viewModelScope.launch {
            val existing = d.id?.let { id -> calendar.subscriptions.first().firstOrNull { it.id == id } }
            val sub = (existing ?: CalendarSubscription(ids.newId(), "", url)).copy(
                name = d.name.trim().ifEmpty { hostOf(url) },
                url = d.url.trim(),
                personIds = d.personIds.toList(),
                color = d.color,
            )
            calendar.saveSubscription(sub)
            refresh(sub)
        }
    }

    fun setEnabled(sub: CalendarSubscription, on: Boolean) {
        viewModelScope.launch { calendar.saveSubscription(sub.copy(enabled = on)) }
    }

    fun refreshNow(sub: CalendarSubscription) {
        viewModelScope.launch { refresh(sub) }
    }

    fun delete(sub: CalendarSubscription) {
        viewModelScope.launch { calendar.deleteSubscription(sub.id) }
    }

    private suspend fun refresh(sub: CalendarSubscription) {
        refreshing.update { it + sub.id }
        try {
            sync.refresh(sub, force = true)
        } finally {
            refreshing.update { it - sub.id }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** "skolplattformen.stockholm.se" when the user gave no name. */
        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }
}
