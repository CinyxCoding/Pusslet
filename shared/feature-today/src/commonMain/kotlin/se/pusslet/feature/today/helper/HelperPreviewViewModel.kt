package se.pusslet.feature.today.helper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.helper.CaregiverVisibility
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayouts
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.TaskRepository
import kotlin.time.Clock

/** "Så ser Bengt appen": his home screen built from this phone's data, and how much of this week he does not see. */
data class HelperPreviewUiState(
    val loading: Boolean = true,
    /** The caregiver the preview is for; null when that member is not in the family (any more). */
    val member: FamilyPerson? = null,
    /** His home screen as his phone draws it before he has chosen anything (the caregiver's default sections). */
    val home: HelperUiState = HelperUiState(),
    /** "Bengt ser inte 2 händelser den här veckan.": family events Monday to Sunday that are not shared with caregivers. */
    val hiddenThisWeek: Int = 0,
    /** "Bjud in Bengt" next to the preview: he has no account yet and this family has a backend to invite to. */
    val canInvite: Boolean = false,
)

/**
 * The parents' preview of the helper view (UX plan Steg 3, "Förhandsvisning för föräldrar"). It reads all of the family's
 * data the way [HelperViewModel] does and lets [se.pusslet.core.helper.HelperPlanner] apply [CaregiverVisibility], so it
 * shows what his phone would show, even before he has joined. It only reads: there is nothing here that writes, and his
 * first-time hint is neither shown nor dismissed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelperPreviewViewModel(
    family: FamilyRepository,
    calendar: CalendarRepository,
    packing: PackingRepository,
    tasks: TaskRepository,
    private val auth: AuthRepository,
    clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val memberId = MutableStateFlow<String?>(null)

    private val now: Flow<LocalDateTime> = HelperPlans.minutes(clock, zone)
    private val today = now.map { it.date }.distinctUntilChanged()
    private val data: Flow<HelperFamilyData> = HelperPlans.familyData(family, calendar, packing, tasks, today, zone)

    /** This ISO week, Monday to Sunday. */
    private val hiddenThisWeek: Flow<Int> = today.map { it.startOfWeek() }.distinctUntilChanged().flatMapLatest { monday ->
        calendar.allOccurrences(monday, monday.plusDays(DAYS_IN_WEEK - 1), zone).map(CaregiverVisibility::hiddenCount)
    }

    val state: StateFlow<HelperPreviewUiState> = combine(memberId.filterNotNull(), now, data, hiddenThisWeek, family.members, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HelperPreviewUiState())

    fun load(memberId: String) {
        this.memberId.value = memberId
    }

    private fun build(id: String, n: LocalDateTime, data: HelperFamilyData, hidden: Int, members: List<Member>): HelperPreviewUiState {
        val member = data.people.firstOrNull { it.id == id && !it.isChild }
        val plan = HelperPlans.plan(id, n, zone, data)
        // His own choices live on his phone (Prefs.TodayLayout); the preview shows the caregiver's default.
        val layout = TodayLayouts.defaultFor(Role.Caregiver)
        val home = HelperUiState(
            loading = false,
            plan = plan,
            me = member,
            parents = data.people.filter { !it.isChild && it.role == Role.Parent },
            layout = layout,
            sections = HelperViewModel.sections(layout, plan),
            // No forecast is fetched for a preview, so there is no weather line.
            showWeather = layout.isOn(TodayBlock.Weather),
            now = n,
        )
        val joined = members.firstOrNull { it.id == id }?.let { it.uid != null || it.joinedAt != null } ?: true
        return HelperPreviewUiState(
            loading = false,
            member = member,
            home = home,
            hiddenThisWeek = hidden,
            canInvite = member != null && !joined && auth.available,
        )
    }

    private companion object {
        const val DAYS_IN_WEEK = 7
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
