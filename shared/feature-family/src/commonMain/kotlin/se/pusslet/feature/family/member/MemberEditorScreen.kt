package se.pusslet.feature.family.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.ColorPicker
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import androidx.compose.ui.semantics.Role as SemanticsRole

data class MemberEditorState(
    val member: Member? = null,
    val isNew: Boolean = true,
    val isMe: Boolean = false,
    val iAmParent: Boolean = true,
    val done: Boolean = false,
)

/** Adults: a parent adds e.g. "Morfar Bengt" before he has the app, and can invite him to claim the profile later. */
class MemberEditorViewModel(private val family: FamilyRepository, private val sessions: SessionProvider) : ViewModel() {
    private val _state = MutableStateFlow(MemberEditorState())
    val state: StateFlow<MemberEditorState> = _state.asStateFlow()

    fun load(memberId: String?) {
        if (_state.value.member != null) return
        viewModelScope.launch {
            val session = sessions.session.value
            val existing = memberId?.let { id -> family.members.first().firstOrNull { it.id == id } }
            val used = family.people.first().map { it.color }
            _state.value = MemberEditorState(
                member = existing ?: Member(family.newId(), "", Role.Caregiver, MemberColor.nextFree(used)),
                isNew = existing == null,
                isMe = existing?.id == session.memberId,
                iAmParent = session.role == Role.Parent,
            )
        }
    }

    fun edit(transform: (Member) -> Member) = _state.update { s -> s.copy(member = s.member?.let(transform)) }

    fun save() {
        val m = _state.value.member ?: return
        if (m.displayName.isBlank()) return
        viewModelScope.launch {
            family.saveMember(m.copy(displayName = m.displayName.trim(), nickname = m.nickname?.trim()?.ifEmpty { null }))
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val m = _state.value.member ?: return
        if (_state.value.isMe) return
        viewModelScope.launch {
            family.deleteMember(m.id)
            _state.update { it.copy(done = true) }
        }
    }
}

@Composable
fun MemberEditorRoute(
    memberId: String?,
    onDone: () -> Unit,
    onInvite: (String) -> Unit,
    viewModel: MemberEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(memberId) { viewModel.load(memberId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    if (state.done) return
    val m = state.member ?: return
    Scaffold(topBar = {
        PussletTopBar(m.displayName.ifBlank { stringResource(Res.string.family_add_adult) }, onBack = onDone)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = m.displayName,
                onValueChange = { v -> viewModel.edit { it.copy(displayName = v) } },
                label = { Text(stringResource(Res.string.contact_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            OutlinedTextField(
                value = m.nickname.orEmpty(),
                onValueChange = { v -> viewModel.edit { it.copy(nickname = v) } },
                label = { Text(stringResource(Res.string.member_nickname)) },
                placeholder = { Text(stringResource(Res.string.member_nickname_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            ColorPicker(m.color) { c -> viewModel.edit { it.copy(color = c) } }
            if (state.iAmParent && !state.isMe) {
                listOf(Role.Parent, Role.Caregiver).forEach { role ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(
                            min = 48.dp,
                        ).selectable(selected = m.role == role, role = SemanticsRole.RadioButton) {
                            viewModel.edit { it.copy(role = role) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.role == role, onClick = null)
                        Text(stringResource(Labels.role(role)), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
            if (!state.isNew && m.uid == null && state.iAmParent) {
                OutlinedButton(onClick = { onInvite(m.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.invite_person, m.displayName))
                }
            }
            if (!state.isNew && !state.isMe && state.iAmParent) {
                OutlinedButton(onClick = viewModel::delete, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_delete))
                }
            }
            Button(
                onClick = viewModel::save,
                enabled = m.displayName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) {
                Text(stringResource(Res.string.action_save))
            }
            Text(
                stringResource(Res.string.invite_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
