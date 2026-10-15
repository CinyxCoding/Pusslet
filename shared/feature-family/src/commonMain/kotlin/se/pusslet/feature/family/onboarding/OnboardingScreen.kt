package se.pusslet.feature.family.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.AppConfig
import se.pusslet.feature.family.school.SchoolPickerDialog
import se.pusslet.ui.components.OutlinedValueField
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * First run. [onJoin] opens the join flow; [onFinished] is called when the family is ready (created or demo).
 */
@Composable
fun OnboardingRoute(
    onJoin: () -> Unit,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val defaultName = stringResource(Res.string.onboarding_default_family_name, state.myName.trim())
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        when (state.step) {
            OnboardingStep.Welcome -> Welcome(
                onCreate = { viewModel.go(OnboardingStep.Family) },
                onJoin = onJoin,
                onDemo = { viewModel.startDemo(onFinished) },
                busy = state.saving,
            )
            OnboardingStep.Done -> Done(state.doneMessage, busy = state.saving, onShowMyDay = { viewModel.showMyDay(onFinished) })
            else -> StepScaffold(
                step = state.step.ordinal,
                total = 3,
                canContinue = state.canContinue && !state.saving,
                onBack = viewModel::back,
                onNext = { viewModel.next(defaultName) },
                onSkip = if (state.step != OnboardingStep.Family) ({ viewModel.next(defaultName) }) else null,
            ) {
                when (state.step) {
                    OnboardingStep.Family -> FamilyStep(state, viewModel)
                    OnboardingStep.Children -> ChildrenStep(state, viewModel)
                    OnboardingStep.Place -> PlaceStep(state, viewModel, onUseLocation = {
                        platform.requestPermission(AppPermission.CoarseLocation) { granted ->
                            if (granted) platform.coarseLocation { loc -> loc?.let { viewModel.useLocation(it.latitude, it.longitude) } }
                        }
                    })
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onJoin: () -> Unit, onDemo: () -> Unit, busy: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(PussletIcons.Family, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(Res.string.onboarding_welcome_title, AppConfig.APP_NAME),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.size(Spacing.lg))
        Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.onboarding_create))
        }
        OutlinedButton(onClick = onJoin, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.onboarding_join))
        }
        TextButton(onClick = onDemo, enabled = !busy) { Text(stringResource(Res.string.onboarding_demo)) }
    }
}

@Composable
private fun StepScaffold(
    step: Int,
    total: Int,
    canContinue: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(Res.string.onboarding_step, step, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            content()
        }
        Row(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.navigate_back)) }
            Spacer(Modifier.weight(1f))
            if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(Res.string.action_skip)) }
            Button(onClick = onNext, enabled = canContinue, modifier = Modifier.heightIn(min = Spacing.primaryTouchTarget)) {
                Text(stringResource(Res.string.action_next))
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun StepBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FamilyStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    StepTitle(stringResource(Res.string.onboarding_family_title))
    OutlinedTextField(
        value = state.myName,
        onValueChange = vm::setMyName,
        label = { Text(stringResource(Res.string.onboarding_your_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    OutlinedTextField(
        value = state.familyName,
        onValueChange = vm::setFamilyName,
        label = { Text(stringResource(Res.string.onboarding_family_name)) },
        placeholder = { Text(stringResource(Res.string.onboarding_family_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    OtherAdults(state, vm)
}

/** Optional (UX plan 4.1): names only. Each one can be chosen as the one who drops off or picks up. */
@Composable
private fun OtherAdults(state: OnboardingUiState, vm: OnboardingViewModel) {
    Text(
        stringResource(Res.string.onboarding_adults_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
    )
    state.adults.forEach { adult ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.md))
            Text(adult.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.removeAdult(adult.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.onboarding_remove_person, adult.name))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = state.adultName,
            onValueChange = vm::setAdultName,
            label = { Text(stringResource(Res.string.contact_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.addAdult() }),
        )
        OutlinedButton(
            onClick = vm::addAdult,
            enabled = state.adultName.isNotBlank(),
            modifier = Modifier.heightIn(min = Spacing.primaryTouchTarget),
        ) { Text(stringResource(Res.string.action_add)) }
    }
}

@Composable
private fun ChildrenStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    var pickingSchoolFor by remember { mutableStateOf<String?>(null) }
    // The empty row the step starts with (or the one just added) has the focus, so typing the name comes first.
    val focus = remember { FocusRequester() }
    val focusId = state.children.lastOrNull()?.takeIf { it.name.isEmpty() }?.id
    LaunchedEffect(focusId) { if (focusId != null) focus.requestFocus() }
    StepTitle(stringResource(Res.string.onboarding_children_title))
    StepBody(stringResource(Res.string.onboarding_children_body))
    state.children.forEachIndexed { index, child ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
        ChildRow(
            child = child,
            vm = vm,
            nameModifier = if (child.id == focusId) Modifier.focusRequester(focus) else Modifier,
            onPickSchool = { pickingSchoolFor = child.id },
        )
    }
    OutlinedButton(onClick = vm::addChild, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.onboarding_add_child)) }
    pickingSchoolFor?.let { id ->
        SchoolPickerDialog(onDismiss = { pickingSchoolFor = null }, onPicked = { school ->
            vm.updateChild(id) { it.copy(school = school) }
            pickingSchoolFor = null
        })
    }
}

@Composable
private fun ChildRow(child: ChildDraft, vm: OnboardingViewModel, nameModifier: Modifier, onPickSchool: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = child.name,
                onValueChange = { v -> vm.updateChild(child.id) { it.copy(name = v) } },
                label = { Text(stringResource(Res.string.onboarding_child_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f).then(nameModifier),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            IconButton(onClick = { vm.removeChild(child.id) }) {
                val description = child.name.trim().takeIf { it.isNotEmpty() }
                    ?.let { stringResource(Res.string.onboarding_remove_person, it) }
                    ?: stringResource(Res.string.action_delete)
                Icon(Icons.Filled.Close, contentDescription = description)
            }
        }
        OutlinedTextField(
            value = child.birthYear,
            onValueChange = { v -> vm.updateChild(child.id) { it.copy(birthYear = v.filter(Char::isDigit).take(4)) } },
            label = { Text(stringResource(Res.string.onboarding_child_birth_year_optional)) },
            singleLine = true,
            modifier = Modifier.width(220.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        OutlinedValueField(
            label = stringResource(Res.string.child_school_optional),
            value = child.school?.name,
            onClick = onPickSchool,
            placeholder = stringResource(Res.string.child_school_none),
        )
    }
}

/** Search first (UX plan 4.2): the list only shows what matches the typed text; the location is the other way. */
@Composable
private fun PlaceStep(state: OnboardingUiState, vm: OnboardingViewModel, onUseLocation: () -> Unit) {
    StepTitle(stringResource(Res.string.onboarding_place_title))
    StepBody(stringResource(Res.string.onboarding_place_body))
    OutlinedTextField(
        value = state.placeQuery,
        onValueChange = vm::setPlaceQuery,
        label = { Text(stringResource(Res.string.onboarding_place_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
    )
    state.placeSuggestions.forEach { place ->
        val selected = state.place?.name == place.name
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = { vm.choosePlace(place) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
    OutlinedButton(onClick = onUseLocation, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
        Icon(Icons.Filled.LocationOn, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(Res.string.onboarding_use_location))
    }
}

@Composable
private fun Done(message: DoneMessage, busy: Boolean, onShowMyDay: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🧩", style = MaterialTheme.typography.displayMedium)
        Text(
            stringResource(Res.string.onboarding_done_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        val body = when (message) {
            DoneMessage.AddWithPlus -> Res.string.onboarding_done_body
            DoneMessage.SeeHandoffs -> Res.string.onboarding_done_body_caregiver
        }
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onShowMyDay, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.onboarding_start))
        }
    }
}
