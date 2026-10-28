package se.pusslet.feature.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateShort
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
    /** Not signed in: everything lives only on this phone ("Bara på den här telefonen · Logga in"). */
    val signedIn: Boolean = true,
    /** Child id -> wishes nobody has bought yet (ADR-054). */
    val openWishes: Map<String, Int> = emptyMap(),
) {
    /** "Så ser Bengt appen": parents only, on the rows of other adults with the caregiver role (UX plan Steg 3). */
    fun canPreviewHelper(member: Member): Boolean = myRole == Role.Parent && member.id != me && member.role == Role.Caregiver
}

class FamilyViewModel(
    family: FamilyRepository,
    sessions: SessionProvider,
    wishes: WishRepository,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val custodyAndWishes = combine(family.custody, wishes.wishes) { cu, w -> cu to w }

    val state: StateFlow<FamilyUiState> = combine(family.family, family.members, family.children, custodyAndWishes, sessions.session) {
        f,
        m,
        c,
        (cu, w),
        s,
        ->
        val open = w.filter { !it.taken }.groupingBy { it.childId }.eachCount()
        FamilyUiState(f?.name.orEmpty(), m, c, cu, s.memberId, s.role, clock.todayIn(zone), signedIn = s.uid != null, openWishes = open)
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
    onSignIn: () -> Unit = {},
    onPreviewHelper: (memberId: String) -> Unit = {},
    onWishlist: (childId: String) -> Unit = {},
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
            if (!state.signedIn) {
                item(key = "local") {
                    Banner(
                        stringResource(Res.string.family_local_only),
                        actions = { TextButton(onClick = onSignIn) { Text(stringResource(Res.string.family_sign_in)) } },
                    )
                }
            }
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
                        // An adult who has not joined yet can be invited with one tap (UX plan Steg 2, Mer 2).
                        if (parent && m.uid == null && m.id != state.me) {
                            FilledTonalButton(onClick = { onInvite(m.id) }) {
                                Text(stringResource(Res.string.family_invite_named, m.displayName))
                            }
                        }
                        if (state.canPreviewHelper(m)) {
                            NavRow(stringResource(Res.string.helper_preview_title, m.displayName), onClick = { onPreviewHelper(m.id) })
                        }
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
                item(key = "wishlists") {
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
        }
    }
}
