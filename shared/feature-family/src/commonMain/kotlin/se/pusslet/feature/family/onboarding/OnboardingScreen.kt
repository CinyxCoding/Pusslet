package se.pusslet.feature.family.onboarding

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.AppConfig
import se.pusslet.core.model.SwedishPlaces
import se.pusslet.feature.family.school.SchoolPickerDialog
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
    /** The family already exists (e.g. the app was restarted on the last step): show "Klart!", never the welcome. */
    familyReady: Boolean = false,
    /** Called right before the family is created, so the caller keeps onboarding open for its last step. */
    onCreating: () -> Unit = {},
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val defaultName = stringResource(Res.string.onboarding_default_family_name, state.myName.trim())
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val step = if (familyReady && state.step == OnboardingStep.Welcome) OnboardingStep.Done else state.step
        when (step) {
            OnboardingStep.Welcome -> Welcome(
                onCreate = { viewModel.go(OnboardingStep.Family) },
                onJoin = onJoin,
                onDemo = { viewModel.startDemo(onFinished) },
                busy = state.saving,
            )
            OnboardingStep.Done -> Done(onFinished)
            else -> StepScaffold(
                step = state.step.ordinal,
                total = 3,
                canContinue = state.canContinue && !state.saving,
                onBack = viewModel::back,
                onNext = { viewModel.next(defaultName, onCreating) },
                onSkip = if (state.step != OnboardingStep.Family) ({ viewModel.next(defaultName, onCreating) }) else null,
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
private fun FamilyStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    Text(
        stringResource(Res.string.onboarding_family_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics {
            heading()
        },
    )
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
}

@Composable
private fun ChildrenStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    var pickingSchoolFor by remember { mutableStateOf<String?>(null) }
    Text(
        stringResource(Res.string.onboarding_children_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics {
            heading()
        },
    )
    Text(
        stringResource(Res.string.onboarding_children_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.children.forEach { child ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = child.name,
                        onValueChange = { v -> vm.updateChild(child.id) { it.copy(name = v) } },
                        label = { Text(stringResource(Res.string.onboarding_child_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    IconButton(onClick = {
                        vm.removeChild(child.id)
                    }) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete)) }
                }
                OutlinedTextField(
                    value = child.birthYear,
                    onValueChange = { v -> vm.updateChild(child.id) { it.copy(birthYear = v.filter(Char::isDigit).take(4)) } },
                    label = {
                        Text(stringResource(Res.string.onboarding_child_birth_year) + " (" + stringResource(Res.string.optional) + ")")
                    },
                    singleLine = true,
                    modifier = Modifier.width(220.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = { pickingSchoolFor = child.id }) {
                    Text(child.school?.name ?: (stringResource(Res.string.child_school) + " (" + stringResource(Res.string.optional) + ")"))
                }
            }
        }
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
private fun PlaceStep(state: OnboardingUiState, vm: OnboardingViewModel, onUseLocation: () -> Unit) {
    Text(
        stringResource(Res.string.onboarding_place_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics {
            heading()
        },
    )
    Text(
        stringResource(Res.string.onboarding_place_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onUseLocation, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.LocationOn, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(state.place?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.settings_weather_place))
    }
    OutlinedTextField(
        value = state.placeQuery,
        onValueChange = vm::setPlaceQuery,
        label = { Text(stringResource(Res.string.onboarding_place_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SwedishPlaces.search(state.placeQuery).take(MAX_PLACES).forEach { place ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { vm.choosePlace(place) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.place?.name == place.name, onClick = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Done(onFinished: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🧩", style = MaterialTheme.typography.displayMedium)
        Text(
            stringResource(Res.string.onboarding_done_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics {
                heading()
            },
        )
        Text(stringResource(Res.string.onboarding_done_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.onboarding_start))
        }
    }
}

private const val MAX_PLACES = 12
