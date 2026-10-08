package se.pusslet.feature.family.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Child
import se.pusslet.core.model.ClothingCategory
import se.pusslet.core.model.ClothingSize
import se.pusslet.core.model.Contact
import se.pusslet.core.model.Diet
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.MemberColor
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.feature.family.school.SchoolPickerDialog
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.ValueField
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class ChildEditorState(
    val child: Child? = null,
    val isNew: Boolean = true,
    val diet: DietProfile? = null,
    val dietEnabled: Boolean = false,
    val today: LocalDate? = null,
    val sizeDrafts: Map<ClothingCategory, String> = emptyMap(),
    val saved: Boolean = false,
)

class ChildEditorViewModel(private val family: FamilyRepository, private val clock: Clock, private val zone: TimeZone) : ViewModel() {
    private val _state = MutableStateFlow(ChildEditorState())
    val state: StateFlow<ChildEditorState> = _state.asStateFlow()

    fun load(childId: String?) {
        if (_state.value.child != null) return
        viewModelScope.launch {
            val existing = childId?.let { id -> family.children.first().firstOrNull { it.id == id } }
            val used = family.people.first().map { it.color }
            val child = existing ?: Child(family.newChildId(), "", MemberColor.nextFree(used))
            val diet = family.diets.first().firstOrNull { it.personId == child.id }
            _state.value = ChildEditorState(
                child = child,
                isNew = existing == null,
                diet = diet,
                dietEnabled = diet != null,
                today = clock.todayIn(zone),
                sizeDrafts = ClothingCategory.entries.associateWith { cat ->
                    child.sizes.lastOrNull { it.category == cat }?.size.orEmpty()
                },
            )
        }
    }

    fun edit(transform: (Child) -> Child) = _state.update { s -> s.copy(child = s.child?.let(transform)) }

    fun setSize(category: ClothingCategory, size: String) = _state.update { it.copy(sizeDrafts = it.sizeDrafts + (category to size)) }

    fun setDietEnabled(enabled: Boolean) = _state.update { s ->
        val id = s.child?.id ?: return@update s
        s.copy(dietEnabled = enabled, diet = if (enabled) (s.diet ?: DietProfile(id, consentedAt = clock.now())) else s.diet)
    }

    fun toggleAllergen(a: Allergen) = _state.update { s ->
        s.copy(diet = s.diet?.let { d -> d.copy(allergies = if (a in d.allergies) d.allergies - a else d.allergies + a) })
    }

    fun toggleDiet(diet: Diet) = _state.update { s ->
        s.copy(diet = s.diet?.let { d -> d.copy(diets = if (diet in d.diets) d.diets - diet else d.diets + diet) })
    }

    fun save() {
        val s = _state.value
        val child = s.child ?: return
        if (child.firstName.isBlank()) return
        val today = s.today ?: clock.todayIn(zone)
        // Keep the size history: a new entry only when the size actually changed.
        var sizes = child.sizes
        s.sizeDrafts.forEach { (cat, size) ->
            val current = sizes.lastOrNull { it.category == cat }?.size
            if (size.isNotBlank() && size.trim() != current) sizes = sizes + ClothingSize(cat, size.trim(), today)
        }
        viewModelScope.launch {
            family.saveChild(child.copy(firstName = child.firstName.trim(), sizes = sizes))
            if (s.dietEnabled &&
                s.diet != null
            ) {
                family.saveDiet(s.diet)
            } else if (!s.dietEnabled && s.diet != null) {
                family.deleteDiet(child.id)
            }
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val child = _state.value.child ?: return
        viewModelScope.launch {
            family.deleteDiet(child.id)
            family.deleteChild(child.id)
            _state.update { it.copy(saved = true) }
        }
    }
}

private val avatars = listOf("🦊", "🦖", "🐻", "🐰", "🦁", "🐸", "🐼", "🦄", "🐝", "🐳", "🌻", "⚽", "🚀", "🎨")

@Composable
fun ChildEditorRoute(childId: String?, onDone: () -> Unit, viewModel: ChildEditorViewModel = koinViewModel()) {
    LaunchedEffect(childId) { viewModel.load(childId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    if (state.saved) return
    val child = state.child ?: return
    var pickSchool by remember { mutableStateOf(false) }
    var pickBirth by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            PussletTopBar(
                if (state.isNew) stringResource(Res.string.child_new) else stringResource(Res.string.child_edit, child.firstName),
                onBack = onDone,
                actions = {
                    TextButton(onClick = viewModel::save, enabled = child.firstName.isNotBlank()) {
                        Text(stringResource(Res.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = child.firstName,
                onValueChange = { v -> viewModel.edit { it.copy(firstName = v) } },
                label = { Text(stringResource(Res.string.child_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Text(stringResource(Res.string.child_avatar), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(selected = child.avatar == null, onClick = {
                    viewModel.edit { it.copy(avatar = null) }
                }, label = { Text(child.firstName.take(1).ifEmpty { "A" }) })
                avatars.forEach { a ->
                    FilterChip(selected = child.avatar == a, onClick = { viewModel.edit { it.copy(avatar = a) } }, label = { Text(a) })
                }
            }
            Text(stringResource(Res.string.child_color), style = MaterialTheme.typography.labelLarge)
            ColorPicker(child.color) { c -> viewModel.edit { it.copy(color = c) } }
            ValueField(
                stringResource(Res.string.child_birth_date) + " (" + stringResource(Res.string.optional) + ")",
                child.birthDate?.let { formatDateLong(it, referenceYear = -1) } ?: "–",
                onClick = { pickBirth = true },
            )
            ValueField(stringResource(Res.string.child_school), child.school?.name ?: "–", onClick = { pickSchool = true })
            OutlinedTextField(
                value = child.className.orEmpty(),
                onValueChange = { v -> viewModel.edit { it.copy(className = v.ifBlank { null }) } },
                label = { Text(stringResource(Res.string.child_class)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LunchSection(child, viewModel)
            SectionCard(stringResource(Res.string.child_sizes)) {
                ClothingCategory.entries.forEach { cat ->
                    val label = stringResource(
                        when (cat) {
                            ClothingCategory.Pants -> Res.string.size_pants
                            ClothingCategory.Top -> Res.string.size_top
                            ClothingCategory.Shoes -> Res.string.size_shoes
                            ClothingCategory.Outerwear -> Res.string.size_outerwear
                        },
                    )
                    OutlinedTextField(
                        value = state.sizeDrafts[cat].orEmpty(),
                        onValueChange = { viewModel.setSize(cat, it) },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = child.sizes.filter { it.category == cat }.takeLast(3).takeIf { it.size > 1 }?.let { history ->
                            { Text(history.joinToString(" → ") { it.size }) }
                        },
                    )
                }
            }
            ContactsSection(child, viewModel)
            OutlinedTextField(
                value = child.notes,
                onValueChange = { v -> viewModel.edit { it.copy(notes = v) } },
                label = { Text(stringResource(Res.string.child_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            DietSection(state, child, viewModel)
            if (!state.isNew) {
                OutlinedButton(onClick = {
                    confirmDelete = true
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.action_delete)) }
            }
            Button(
                onClick = viewModel::save,
                enabled = child.firstName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
    if (pickSchool) {
        SchoolPickerDialog(onDismiss = { pickSchool = false }, onPicked = { s ->
            viewModel.edit { it.copy(school = s) }
            pickSchool = false
        })
    }
    if (pickBirth) {
        DatePickerSheet(child.birthDate ?: LocalDate(2018, 1, 1), onDismiss = { pickBirth = false }, onPicked = { d ->
            viewModel.edit { it.copy(birthDate = d) }
        })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.child_delete_confirm, child.firstName)) },
            confirmButton = { TextButton(onClick = viewModel::delete) { Text(stringResource(Res.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
fun ColorPicker(selected: MemberColor, onPick: (MemberColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MemberColor.entries.forEachIndexed { i, c ->
            val label = stringResource(Res.string.color_label, i + 1)
            Row(
                Modifier.selectable(selected = c == selected, role = Role.RadioButton) { onPick(c) }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(c, if (c == selected) "✓" else "", label, size = 40.dp)
            }
        }
    }
}

@Composable
private fun LunchSection(child: Child, vm: ChildEditorViewModel) {
    SectionCard(stringResource(Res.string.child_lunch)) {
        val current = child.lunch?.provider
        listOf(
            null to Res.string.lunch_none,
            LunchProviderType.Skolmaten to Res.string.lunch_skolmaten,
            LunchProviderType.Rss to Res.string.lunch_rss,
            LunchProviderType.Manual to Res.string.lunch_manual,
        ).forEach { (type, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = current == type, role = Role.RadioButton) {
                    vm.edit { it.copy(lunch = type?.let { t -> LunchSource(t, it.lunch?.reference.orEmpty()) }) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == type, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        if (current == LunchProviderType.Skolmaten || current == LunchProviderType.Rss) {
            OutlinedTextField(
                value = child.lunch?.reference.orEmpty(),
                onValueChange = { v -> vm.edit { it.copy(lunch = it.lunch?.copy(reference = v)) } },
                label = { Text(stringResource(Res.string.lunch_reference_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
    }
}

@Composable
private fun ContactsSection(child: Child, vm: ChildEditorViewModel) {
    SectionCard(stringResource(Res.string.child_contacts)) {
        child.contacts.forEachIndexed { i, c ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = c.name,
                    onValueChange = { v ->
                        vm.edit { ch -> ch.copy(contacts = ch.contacts.toMutableList().also { it[i] = c.copy(name = v) }) }
                    },
                    label = { Text(stringResource(Res.string.contact_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = c.relation,
                        onValueChange = { v ->
                            vm.edit { ch ->
                                ch.copy(
                                    contacts = ch.contacts.toMutableList().also {
                                        it[i] =
                                            c.copy(relation = v)
                                    },
                                )
                            }
                        },
                        label = { Text(stringResource(Res.string.contact_relation)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = c.phone.orEmpty(),
                        onValueChange = { v ->
                            vm.edit { ch ->
                                ch.copy(
                                    contacts = ch.contacts.toMutableList().also {
                                        it[i] =
                                            c.copy(phone = v.ifBlank { null })
                                    },
                                )
                            }
                        },
                        label = { Text(stringResource(Res.string.contact_phone)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
                TextButton(onClick = { vm.edit { ch -> ch.copy(contacts = ch.contacts.filterIndexed { j, _ -> j != i }) } }) {
                    Text(stringResource(Res.string.action_delete))
                }
            }
        }
        OutlinedButton(onClick = {
            vm.edit { it.copy(contacts = it.contacts + Contact("")) }
        }) { Text(stringResource(Res.string.contact_add)) }
    }
}

@Composable
private fun DietSection(state: ChildEditorState, child: Child, vm: ChildEditorViewModel) {
    SectionCard(stringResource(Res.string.diet_title)) {
        Text(
            stringResource(Res.string.diet_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(Res.string.diet_consent, child.firstName.ifBlank { "…" }), state.dietEnabled, vm::setDietEnabled)
        val diet = state.diet
        if (state.dietEnabled && diet != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Allergen.entries.forEach { a ->
                    FilterChip(selected = a in diet.allergies, onClick = {
                        vm.toggleAllergen(a)
                    }, label = { Text(stringResource(Labels.allergen(a))) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Diet.entries.forEach { d ->
                    FilterChip(selected = d in diet.diets, onClick = { vm.toggleDiet(d) }, label = { Text(stringResource(Labels.diet(d))) })
                }
            }
            diet.consentedAt?.let {
                Text(formatDateShort(it.toLocalDateTime(TimeZone.currentSystemDefault()).date), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
