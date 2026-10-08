package se.pusslet.feature.meals.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.recipes.ImportWarning
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.Stepper
import se.pusslet.ui.format.formatQuantity
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** New, edit, import from a link (shared or pasted) or split pasted text – all end in the same form. */
@Composable
fun RecipeEditorRoute(
    recipeId: String?,
    link: Boolean,
    url: String?,
    paste: Boolean,
    onBack: () -> Unit,
    onSaved: (id: String, isNew: Boolean) -> Unit,
    viewModel: RecipeEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(recipeId, link, url, paste) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, state.isNew) } }
    RecipeEditorScreen(
        state = state,
        onBack = onBack,
        onEdit = viewModel::edit,
        onToggleTag = viewModel::toggleTag,
        onUrl = viewModel::setUrl,
        onFetch = viewModel::fetch,
        onShowPaste = viewModel::showPaste,
        onPasteText = viewModel::setPasteText,
        onSplit = viewModel::split,
        onSave = viewModel::save,
    )
}

@Composable
fun RecipeEditorScreen(
    state: RecipeEditorState,
    onBack: () -> Unit,
    onEdit: ((RecipeDraft) -> RecipeDraft) -> Unit,
    onToggleTag: (RecipeTag) -> Unit,
    onUrl: (String) -> Unit,
    onFetch: () -> Unit,
    onShowPaste: () -> Unit,
    onPasteText: (String) -> Unit,
    onSplit: () -> Unit,
    onSave: () -> Unit,
) {
    val title = when (state.stage) {
        EditorStage.Link -> stringResource(Res.string.import_title)
        EditorStage.Paste -> stringResource(Res.string.recipe_paste_title)
        EditorStage.Form -> stringResource(if (state.isNew) Res.string.recipe_new_title else Res.string.recipe_edit_title)
    }
    Scaffold(
        topBar = {
            PussletTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    if (state.stage == EditorStage.Form) {
                        TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(Res.string.action_save)) }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state.stage) {
                EditorStage.Link -> LinkStage(state, onUrl, onFetch, onShowPaste)
                EditorStage.Paste -> PasteStage(state, onPasteText, onSplit)
                EditorStage.Form -> Form(state, onEdit, onToggleTag, onSave)
            }
        }
    }
}

@Composable
private fun LinkStage(state: RecipeEditorState, onUrl: (String) -> Unit, onFetch: () -> Unit, onShowPaste: () -> Unit) {
    Text(stringResource(Res.string.import_explain), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.url,
        onValueChange = onUrl,
        label = { Text(stringResource(Res.string.import_url)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onFetch() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.problem?.let { p ->
        val text = when (p) {
            ImportProblem.InvalidUrl -> Res.string.import_invalid
            ImportProblem.NotReachable -> Res.string.import_unreachable
            ImportProblem.NoRecipeData -> Res.string.import_no_data
        }
        Banner(
            stringResource(text),
            kind = if (p == ImportProblem.NoRecipeData) BannerKind.Info else BannerKind.Warning,
            actions = if (p == ImportProblem.NoRecipeData) {
                { TextButton(onClick = onShowPaste) { Text(stringResource(Res.string.recipes_paste)) } }
            } else {
                null
            },
        )
    }
    Button(
        onClick = onFetch,
        enabled = !state.fetching && state.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
    ) { Text(stringResource(Res.string.import_fetch)) }
    OutlinedButton(onClick = onShowPaste, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
        Text(stringResource(Res.string.recipes_paste))
    }
}

@Composable
private fun PasteStage(state: RecipeEditorState, onPasteText: (String) -> Unit, onSplit: () -> Unit) {
    Text(stringResource(Res.string.recipe_paste_body), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.pasteText,
        onValueChange = onPasteText,
        minLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSplit,
        enabled = state.pasteText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
    ) { Text(stringResource(Res.string.recipe_paste_split)) }
}

@Composable
private fun Form(
    state: RecipeEditorState,
    onEdit: ((RecipeDraft) -> RecipeDraft) -> Unit,
    onToggleTag: (RecipeTag) -> Unit,
    onSave: () -> Unit,
) {
    val d = state.draft
    state.warnings.forEach { w ->
        val text = when (w) {
            ImportWarning.ServingsGuessed -> Res.string.import_warning_servings
            ImportWarning.NoIngredients -> Res.string.import_warning_ingredients
            ImportWarning.NoSteps -> Res.string.import_warning_steps
        }
        Banner(stringResource(text), kind = BannerKind.Warning)
    }
    state.pasteResult?.let { (ingredients, steps) ->
        Banner(stringResource(Res.string.recipe_paste_result, ingredients, steps))
    }
    OutlinedTextField(
        value = d.title,
        onValueChange = { v -> onEdit { it.copy(title = v) } },
        label = { Text(stringResource(Res.string.recipe_name)) },
        isError = RecipeError.NameRequired in state.errors,
        supportingText = if (RecipeError.NameRequired in state.errors) {
            { Text(stringResource(Res.string.recipe_name_required)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.meals_servings), style = MaterialTheme.typography.labelLarge)
            Stepper(
                value = d.servings,
                onValueChange = { v -> onEdit { it.copy(servings = v) } },
                decreaseLabel = stringResource(Res.string.meals_servings_decrease),
                increaseLabel = stringResource(Res.string.meals_servings_increase),
            )
        }
        OutlinedTextField(
            value = d.minutes,
            onValueChange = { v -> onEdit { it.copy(minutes = v.filter(Char::isDigit).take(MAX_MINUTE_DIGITS)) } },
            label = { Text(stringResource(Res.string.recipe_total_time)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = d.ingredientsText,
        onValueChange = { v -> onEdit { it.copy(ingredientsText = v) } },
        label = { Text(stringResource(Res.string.recipe_ingredients_field)) },
        minLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    val parsed = d.ingredients
    if (parsed.isNotEmpty()) {
        SectionCard(stringResource(Res.string.recipe_preview)) {
            parsed.forEach { ing ->
                val amount = ing.amount?.let { formatQuantity(it) }
                Text(
                    listOfNotNull(amount, ing.name, ing.note.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ing.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    OutlinedTextField(
        value = d.stepsText,
        onValueChange = { v -> onEdit { it.copy(stepsText = v) } },
        label = { Text(stringResource(Res.string.recipe_steps_field)) },
        minLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    SectionCard(stringResource(Res.string.recipe_tags)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RecipeTag.entries.forEach { tag ->
                FilterChip(selected = tag in d.tags, onClick = {
                    onToggleTag(tag)
                }, label = { Text(stringResource(Labels.recipeTag(tag))) })
            }
        }
    }
    SectionCard(stringResource(Res.string.recipe_component)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = d.mainComponent == null,
                onClick = { onEdit { it.copy(mainComponent = null) } },
                label = { Text(stringResource(Res.string.recipe_component_none)) },
            )
            MainComponent.entries.forEach { c ->
                FilterChip(
                    selected = d.mainComponent == c,
                    onClick = { onEdit { it.copy(mainComponent = c) } },
                    label = { Text(stringResource(Labels.component(c))) },
                )
            }
        }
    }
    OutlinedTextField(
        value = d.notes,
        onValueChange = { v -> onEdit { it.copy(notes = v) } },
        label = { Text(stringResource(Res.string.recipe_notes)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = d.sourceUrl,
        onValueChange = { v -> onEdit { it.copy(sourceUrl = v.trim()) } },
        label = { Text(stringResource(Res.string.recipe_source_field)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (d.sourceUrl.isNotBlank()) {
        Text(
            stringResource(Res.string.import_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
        Text(stringResource(if (state.isNew && d.sourceUrl.isNotBlank()) Res.string.import_save else Res.string.action_save))
    }
}

private const val MAX_MINUTE_DIGITS = 4
