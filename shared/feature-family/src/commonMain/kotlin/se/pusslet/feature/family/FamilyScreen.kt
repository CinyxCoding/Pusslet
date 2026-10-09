package se.pusslet.feature.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.weather.ClothingAdvisor
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.WishRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.OtherHomeSharing
import se.pusslet.data.usecase.SharedSpace
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class FamilyUiState(
    val familyName: String = "",
    val members: List<Member> = emptyList(),
    val children: List<Child> = emptyList(),
    val custody: List<CustodySchedule> = emptyList(),
    val me: String? = null,
    val myRole: Role = Role.Parent,
    val today: LocalDate? = null,
    /** Child id -> wishes nobody has bought yet. */
    val openWishes: Map<String, Int> = emptyMap(),
    val spaces: List<SharedSpace> = emptyList(),
)

class FamilyViewModel(
    family: FamilyRepository,
    sessions: SessionProvider,
    wishes: WishRepository,
    otherHome: OtherHomeSharing,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val custodyAndWishes = combine(family.custody, wishes.wishes, otherHome.spaces) { cu, w, sp -> Triple(cu, w, sp) }

    val state: StateFlow<FamilyUiState> = combine(family.family, family.members, family.children, custodyAndWishes, sessions.session) {
        f,
        m,
        c,
        (cu, w, sp),
        s,
        ->
        val open = w.filter { !it.taken }.groupingBy { it.childId }.eachCount()
        FamilyUiState(f?.name.orEmpty(), m, c, cu, s.memberId, s.role, clock.todayIn(zone), open, sp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUiState())
}

@Composable
fun FamilyRoute(
    onBack: () -> Unit,
    onEditChild: (String?) -> Unit,
    onEditMember: (String?) -> Unit,
    onInvite: (String?) -> Unit,
    onCustody: (String?) -> Unit,
    onHandover: (String) -> Unit,
    onWishlist: (String) -> Unit = {},
    onOtherHome: () -> Unit = {},
    viewModel: FamilyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val parent = state.myRole == Role.Parent
    Scaffold(topBar = { PussletTopBar(state.familyName.ifBlank { stringResource(Res.string.family_title) }, onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                SectionCard(
                    stringResource(Res.string.family_adults),
                    action = if (parent) {
                        (
                            {
                                SmallAction(stringResource(Res.string.family_invite)) { onInvite(null) }
                            }
                        )
                    } else {
                        null
                    },
                ) {
                    state.members.forEach { m ->
                        val person = FamilyPerson.of(m)
                        val role = stringResource(Labels.role(m.role))
                        ListRow(
                            title = m.displayName + (if (m.id == state.me) " (" + stringResource(Res.string.family_me) + ")" else ""),
                            supporting = listOfNotNull(role, m.nickname).joinToString(" · "),
                            leading = { PersonAvatar(person, size = 40.dp) },
                            modifier = Modifier.clickable(enabled = parent || m.id == state.me) { onEditMember(m.id) },
                        )
                    }
                    if (parent) OutlinedButton(onClick = { onEditMember(null) }) { Text(stringResource(Res.string.family_add_adult)) }
                }
            }
            item {
                SectionCard(stringResource(Res.string.family_children)) {
                    state.children.forEach { c ->
                        val age = state.today?.let { ClothingAdvisor.ageOn(c.birthDate, it) }
                        ListRow(
                            title = c.firstName,
                            supporting = listOfNotNull(
                                age?.let {
                                    stringResource(Res.string.person_age, it)
                                },
                                c.school?.name,
                            ).joinToString(" · ").ifEmpty { null },
                            leading = { PersonAvatar(FamilyPerson.of(c), size = 40.dp) },
                            modifier = Modifier.clickable(enabled = parent) { onEditChild(c.id) },
                        )
                    }
                    if (parent) OutlinedButton(onClick = { onEditChild(null) }) { Text(stringResource(Res.string.family_add_child)) }
                }
            }
            if (state.children.isNotEmpty()) {
                item {
                    SectionCard(stringResource(Res.string.family_wishlists)) {
                        state.children.forEach { c ->
                            val open = state.openWishes[c.id] ?: 0
                            ListRow(
                                title = c.firstName,
                                supporting = if (open > 0) {
                                    stringResource(Res.string.family_wishlist_count, open)
                                } else {
                                    stringResource(Res.string.family_wishlist_none)
                                },
                                leading = { PersonAvatar(FamilyPerson.of(c), size = 40.dp) },
                                modifier = Modifier.clickable { onWishlist(c.id) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(
                    stringResource(Res.string.family_custody),
                    action = if (parent) {
                        (
                            {
                                SmallAction(stringResource(Res.string.action_add)) { onCustody(null) }
                            }
                        )
                    } else {
                        null
                    },
                ) {
                    if (state.custody.isEmpty()) {
                        Text(
                            stringResource(Res.string.family_custody_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.custody.forEach { s ->
                        val today = state.today
                        val now = today?.let { CustodyCalculator.householdOn(s, it) }
                        val next = today?.let { CustodyCalculator.nextHandover(s, it) }
                        ListRow(
                            title = s.childIds.mapNotNull { id ->
                                state.children.firstOrNull { it.id == id }?.firstName
                            }.joinToString(", "),
                            supporting = listOfNotNull(
                                now?.let { stringResource(Res.string.today_custody, it.name) },
                                next?.let { stringResource(Res.string.today_custody_next, formatDateShort(it.date)) },
                            ).joinToString(" · "),
                            modifier = Modifier.clickable(enabled = parent) { onCustody(s.id) },
                            trailing = { SmallAction(stringResource(Res.string.handover_title)) { onHandover(s.id) } },
                        )
                    }
                }
            }
            if (parent) item { OtherHomeSection(state.spaces, onOtherHome) }
        }
    }
}

/** "Andra hemmet": what the family shares with the children's other home, or an invitation to start (ADR-030). */
@Composable
private fun OtherHomeSection(spaces: List<SharedSpace>, onOpen: () -> Unit) {
    SectionCard(stringResource(Res.string.family_other_home), action = {
        SmallAction(stringResource(Res.string.action_open)) { onOpen() }
    }) {
        if (spaces.isEmpty()) {
            Text(
                stringResource(Res.string.family_other_home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        spaces.forEach { space ->
            ListRow(
                title = joinNames(space.children.map { it.firstName }),
                supporting = if (space.otherAdults.isEmpty()) {
                    stringResource(Res.string.other_home_waiting)
                } else {
                    stringResource(Res.string.other_home_shared_with, joinNames(space.otherAdults.map { it.displayName }))
                },
                modifier = Modifier.clickable { onOpen() },
            )
        }
    }
}
