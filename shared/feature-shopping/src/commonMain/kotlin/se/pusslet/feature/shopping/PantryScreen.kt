package se.pusslet.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.shopping.BestBefore
import se.pusslet.core.shopping.PantryRules
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** What the pantry screen can do; every callback has a harmless default for previews and screenshot tests. */
data class PantryActions(
    val onAdd: (String) -> Unit = {},
    val onEdit: (PantryItem) -> Unit = {},
    /** "Slut" (false) / "Finns igen" (true). */
    val onInStock: (PantryItem, Boolean) -> Unit = { _, _ -> },
    val onUsePantry: (Boolean) -> Unit = {},
    val onScan: () -> Unit = {},
)

@Composable
fun PantryRoute(onBack: () -> Unit, viewModel: PantryViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val platform = LocalPlatformActions.current
    var editing by remember { mutableStateOf<PantryItem?>(null) }
    var unknownBarcode by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                when (message) {
                    is PantryMessage.Removed -> {
                        val text = getString(Res.string.shopping_removed, message.item.name)
                        if (snackbar.showUndo(text, getString(Res.string.action_undo))) viewModel.restore(message.item)
                    }
                    is PantryMessage.Added -> snackbar.showSnackbar(getString(Res.string.shopping_added, message.name))
                    is PantryMessage.UnknownProduct -> unknownBarcode = message.barcode
                    PantryMessage.Failed -> snackbar.showSnackbar(getString(Res.string.error_generic))
                }
            }
        }
    }

    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.pantry_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        PantryScreen(
            state,
            padding,
            PantryActions(
                onAdd = viewModel::add,
                onEdit = { editing = it },
                onInStock = viewModel::setInStock,
                onUsePantry = viewModel::setUsePantry,
                onScan = { platform.scanCode(viewModel::scanned) },
            ),
        )
    }

    editing?.let { item ->
        PantryEditorSheet(
            item = item,
            today = state.today,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDelete = {
                viewModel.remove(item)
                editing = null
            },
        )
    }
    unknownBarcode?.let { code ->
        UnknownProductDialog(
            barcode = code,
            onDismiss = { unknownBarcode = null },
            onAdd = { name ->
                viewModel.addNamedProduct(code, name)
                unknownBarcode = null
            },
        )
    }
}

/**
 * "Det vi har hemma": the switch, the add field (with the barcode scanner) and what is at home per place (Skafferi, Kyl,
 * Frys). A row says the amount or "Finns hemma", and quietly the best-before date; "Går ut snart" is in terracotta, a
 * reminder and never a problem. "Slut" keeps the row (last in its place) so it is one tap to have it again.
 */
@Composable
fun PantryScreen(state: PantryUiState, padding: PaddingValues = PaddingValues(), actions: PantryActions = PantryActions()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.listBottomInset,
        ),
    ) {
        item(key = "intro") {
            Text(
                stringResource(Res.string.pantry_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
        }
        item(key = "use") {
            SwitchRow(
                title = stringResource(Res.string.pantry_use),
                checked = state.usePantry,
                onCheckedChange = actions.onUsePantry,
                enabled = state.canManage,
            )
        }
        if (state.canManage) {
            item(key = "add") { PantryAddField(onAdd = actions.onAdd, onScan = actions.onScan) }
        }
        if (state.isEmpty) {
            item(key = "empty") { EmptyState(message = stringResource(Res.string.pantry_empty)) }
        }
        state.groups.forEach { group ->
            item(key = "place-${group.location.name}") {
                SectionHeader(
                    stringResource(locationLabel(group.location)),
                    Modifier.padding(top = Spacing.sectionGap, bottom = Spacing.xs),
                )
            }
            items(group.items, key = { it.id }) { item ->
                PantryRow(item, state.today, state.canManage, actions)
            }
        }
    }
}

@Composable
private fun PantryAddField(onAdd: (String) -> Unit, onScan: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        placeholder = { Text(stringResource(Res.string.pantry_add_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            Row {
                IconButton(onClick = onScan) {
                    Icon(PussletIcons.Barcode, contentDescription = stringResource(Res.string.shopping_scan))
                }
                IconButton(onClick = ::submit, enabled = text.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.action_add))
                }
            }
        },
    )
}

/** "Mjölk" over "2 l · Bäst före 24 okt"; "Slut"/"Finns igen" to the right. Tapping the row opens the editor. */
@Composable
private fun PantryRow(item: PantryItem, today: LocalDate?, canManage: Boolean, actions: PantryActions) {
    val colors = MaterialTheme.colorScheme
    val status = today?.let { PantryRules.bestBefore(item, it) }
    val reminder = status == BestBefore.Soon || status == BestBefore.Passed
    val amount = when {
        !item.inStock -> stringResource(Res.string.pantry_out)
        item.quantity != null -> Units.format(Quantity(item.quantity ?: 0.0, item.unit))
        else -> stringResource(Res.string.pantry_at_home)
    }
    val date = item.bestBefore?.takeIf { item.inStock }?.let { day ->
        val shown = formatDateShort(day)
        when (status) {
            BestBefore.Soon -> stringResource(Res.string.pantry_best_before_soon, shown)
            BestBefore.Passed -> stringResource(Res.string.pantry_best_before_passed, shown)
            else -> stringResource(Res.string.pantry_best_before, shown)
        }
    }
    val supporting = buildAnnotatedString {
        append(amount)
        if (date != null) {
            append(" · ")
            if (reminder) withStyle(SpanStyle(color = colors.tertiary)) { append(date) } else append(date)
        }
    }
    val editLabel = stringResource(Res.string.pantry_edit_open)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.primaryTouchTarget)
            .then(if (canManage) Modifier.clickable(onClickLabel = editLabel, role = Role.Button) { actions.onEdit(item) } else Modifier)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.inStock) colors.onSurface else colors.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (reminder) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = colors.tertiary,
                        modifier = Modifier.size(ReminderIconSize),
                    )
                }
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (canManage) {
            val label = if (item.inStock) Res.string.pantry_mark_out else Res.string.pantry_mark_back
            val description = if (item.inStock) {
                stringResource(Res.string.pantry_mark_out_description, item.name)
            } else {
                stringResource(Res.string.pantry_mark_back_description, item.name)
            }
            TextButton(
                onClick = { actions.onInStock(item, !item.inStock) },
                modifier = Modifier.semantics { contentDescription = description },
            ) { Text(stringResource(label)) }
        }
    }
}

internal fun locationLabel(location: PantryLocation): StringResource = when (location) {
    PantryLocation.Pantry -> Res.string.pantry_location_pantry
    PantryLocation.Fridge -> Res.string.pantry_location_fridge
    PantryLocation.Freezer -> Res.string.pantry_location_freezer
}

private val ReminderIconSize = 16.dp
