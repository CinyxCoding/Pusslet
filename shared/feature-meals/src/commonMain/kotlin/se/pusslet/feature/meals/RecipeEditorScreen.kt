package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.RecipeTag
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.UnsavedChangesBackHandler
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * New or edited recipe (UX plan, Mat 7). Name, portions, ingredients and steps come first; time, tags, main ingredient,
 * notes, the source and "Klistra in recept" wait under "Mer (valfritt)". Ingredients and steps are plain text, one per
 * line, so a recipe can be typed or pasted fast; "Klistra in recept" splits pasted text into the fields (rule based, the
 * user checks the result). [startPasting] opens the paste dialog right away ("Klistra in text" under the round + on
 * Recept). Back with unsaved changes asks "Släng ändringarna?".
 */
@Composable
fun RecipeEditorRoute(
    recipeId: String?,
    sourceUrl: String?,
    onDone: (savedId: String?) -> Unit,
    startPasting: Boolean = false,
    viewModel: RecipeEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(recipeId, sourceUrl) { viewModel.load(recipeId, sourceUrl) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pasting by rememberSaveable { mutableStateOf(recipeId == null && (startPasting || sourceUrl != null)) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.savedId) { state.savedId?.let { onDone(it) } }
    val back: () -> Unit = {
        if (state.hasChanges) {
            confirmDiscard = true
        } else {
            onDone(null)
        }
    }
    UnsavedChangesBackHandler(enabled = state.hasChanges && !confirmDiscard) { confirmDiscard = true }

    Scaffold(
        topBar = {
            PussletTopBar(
                title = stringResource(if (state.isNew) Res.string.recipe_new else Res.string.recipe_edit),
                onBack = back,
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.form.valid) { Text(stringResource(Res.string.action_save)) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        RecipeEditorForm(state.form, padding, onChange = viewModel::update, onPaste = { pasting = true })
    }
    if (pasting) {
        PasteDialog(onDismiss = { pasting = false }, onPaste = {
            viewModel.paste(it)
            pasting = false
        })
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(Res.string.recipe_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone(null)
                }) { Text(stringResource(Res.string.recipe_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RecipeEditorForm(
    form: RecipeForm,
    padding: PaddingValues,
    onChange: ((RecipeForm) -> RecipeForm) -> Unit,
    onPaste: () -> Unit,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { v -> onChange { it.copy(title = v) } },
            label = { Text(stringResource(Res.string.recipe_title)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            NumberField(
                label = Res.string.recipe_servings,
                value = form.servings,
                isError = form.servingsValue == null,
                modifier = Modifier.weight(1f),
            ) { v -> onChange { it.copy(servings = v) } }
            Spacer(Modifier.weight(1f))
        }
        OutlinedTextField(
            value = form.ingredients,
            onValueChange = { v -> onChange { it.copy(ingredients = v) } },
            label = { Text(stringResource(Res.string.recipe_ingredients)) },
            supportingText = { Text(stringResource(Res.string.recipe_ingredients_hint)) },
            minLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.steps,
            onValueChange = { v -> onChange { it.copy(steps = v) } },
            label = { Text(stringResource(Res.string.recipe_steps)) },
            supportingText = { Text(stringResource(Res.string.recipe_steps_hint)) },
            minLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        MoreToggle(open = more, onToggle = { more = !more })
        if (more) OptionalFields(form, onChange, onPaste)
    }
}

/** "Mer (valfritt) ⌄". */
@Composable
private fun MoreToggle(open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.recipe_more_optional),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptionalFields(form: RecipeForm, onChange: ((RecipeForm) -> RecipeForm) -> Unit, onPaste: () -> Unit) {
    NumberField(Res.string.recipe_total_minutes, form.totalMinutes, isError = false, modifier = Modifier.fillMaxWidth(0.5f)) { v ->
        onChange { it.copy(totalMinutes = v) }
    }
    Text(stringResource(Res.string.recipe_tags), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RecipeTag.entries.forEach { tag ->
            FilterChip(
                selected = tag in form.tags,
                onClick = { onChange { f -> f.copy(tags = if (tag in f.tags) f.tags - tag else f.tags + tag) } },
                label = { Text(stringResource(tagLabel(tag))) },
                colors = SelectionColors.filterChip(),
            )
        }
    }
    Text(stringResource(Res.string.recipe_main_component), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(Res.string.recipe_main_component_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MainComponent.entries.forEach { c ->
            FilterChip(
                selected = form.mainComponent == c,
                onClick = { onChange { f -> f.copy(mainComponent = if (f.mainComponent == c) null else c) } },
                label = { Text(stringResource(componentLabel(c))) },
                colors = SelectionColors.filterChip(),
            )
        }
    }
    OutlinedTextField(
        value = form.notes,
        onValueChange = { v -> onChange { it.copy(notes = v) } },
        label = { Text(stringResource(Res.string.recipe_notes)) },
        minLines = 2,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    form.sourceUrl?.let { url ->
        Text(
            stringResource(Res.string.recipe_source, form.sourceName ?: url),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = onPaste) { Text(stringResource(Res.string.recipe_paste)) }
}

@Composable
private fun NumberField(label: StringResource, value: String, isError: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(MAX_DIGITS)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onPaste: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.recipe_paste)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.recipe_paste_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPaste(text) }, enabled = text.isNotBlank()) { Text(stringResource(Res.string.recipe_paste_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** "Importera från länk": paste (or share) a recipe page; the recipe is saved with its source. */
@Composable
fun RecipeImportRoute(
    initialUrl: String?,
    onBack: () -> Unit,
    onImported: (String) -> Unit,
    onPasteInstead: (String) -> Unit,
    viewModel: RecipeImportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    LaunchedEffect(initialUrl) { if (!initialUrl.isNullOrBlank()) viewModel.import(initialUrl) }
    LaunchedEffect(state.savedId) { state.savedId?.let(onImported) }

    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.recipe_import), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = padding.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.recipe_import_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    if (state.status != ImportStatus.Idle && state.status != ImportStatus.Loading) viewModel.reset()
                },
                label = { Text(stringResource(Res.string.recipe_import_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.import(url) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Button(onClick = { viewModel.import(url) }, enabled = url.isNotBlank() && state.status != ImportStatus.Loading) {
                    Text(stringResource(Res.string.recipe_import_action))
                }
                if (state.status == ImportStatus.Loading) CircularProgressIndicator()
            }
            when (val status = state.status) {
                ImportStatus.Idle, ImportStatus.Loading -> Unit
                ImportStatus.InvalidUrl -> Banner(stringResource(Res.string.recipe_import_invalid), kind = BannerKind.Warning)
                ImportStatus.NotReachable -> Banner(stringResource(Res.string.recipe_import_unreachable), kind = BannerKind.Warning)
                is ImportStatus.NoRecipeData -> Banner(
                    stringResource(Res.string.recipe_import_no_data),
                    kind = BannerKind.Info,
                    actions = { TextButton(onClick = { onPasteInstead(status.url) }) { Text(stringResource(Res.string.recipe_paste)) } },
                )
            }
            Text(
                stringResource(Res.string.recipe_import_personal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun componentLabel(c: MainComponent): StringResource = when (c) {
    MainComponent.Fish -> Res.string.component_fish
    MainComponent.Chicken -> Res.string.component_chicken
    MainComponent.Beef -> Res.string.component_beef
    MainComponent.Pork -> Res.string.component_pork
    MainComponent.MincedMeat -> Res.string.component_minced
    MainComponent.Sausage -> Res.string.component_sausage
    MainComponent.Vegetarian -> Res.string.component_vegetarian
    MainComponent.Egg -> Res.string.component_egg
    MainComponent.Pasta -> Res.string.component_pasta
    MainComponent.Soup -> Res.string.component_soup
    MainComponent.Other -> Res.string.component_other
}

private const val MAX_DIGITS = 3
