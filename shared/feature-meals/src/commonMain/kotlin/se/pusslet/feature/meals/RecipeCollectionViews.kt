package se.pusslet.feature.meals

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.meals.RecipeCollectionRules
import se.pusslet.core.model.RecipeCollection
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** What the library can do with collections; each returns false when a name is blank or already used. */
data class CollectionActions(
    val onToggle: (String) -> Unit = {},
    val onCreate: (String) -> Boolean = { false },
    val onRename: (id: String, name: String) -> Boolean = { _, _ -> false },
    val onDelete: (String) -> Unit = {},
)

private sealed interface CollectionDialog {
    data object None : CollectionDialog

    data object Create : CollectionDialog

    data class Rename(val collection: RecipeCollection) : CollectionDialog

    data class Delete(val collection: RecipeCollection) : CollectionDialog
}

/**
 * One chip per collection ("Kalas", "Matlådor"), then, for parents, "Ny samling". With a collection chosen, its name and ⋮
 * ("Byt namn", "Ta bort samlingen") follow on their own row, so the chips stay plain filters.
 */
@Composable
internal fun CollectionFilters(state: RecipeLibraryState, actions: CollectionActions) {
    var dialog by remember { mutableStateOf<CollectionDialog>(CollectionDialog.None) }
    if (state.collections.isNotEmpty() || state.canEdit) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            state.collections.forEach { chip ->
                FilterChip(
                    selected = state.collection?.id == chip.collection.id,
                    onClick = { actions.onToggle(chip.collection.id) },
                    label = { Text(chip.collection.name, maxLines = 1) },
                    colors = SelectionColors.filterChip(),
                )
            }
            if (state.canEdit) {
                AssistChip(
                    onClick = { dialog = CollectionDialog.Create },
                    label = { Text(stringResource(Res.string.recipe_collection_new)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        }
    }
    val chosen = state.collection
    if (chosen != null && state.canEdit) {
        CollectionHeader(
            chosen,
            onRename = { dialog = CollectionDialog.Rename(chosen) },
            onDelete = { dialog = CollectionDialog.Delete(chosen) },
        )
    }
    val existing = state.collections.map { it.collection }
    when (val d = dialog) {
        CollectionDialog.None -> Unit
        CollectionDialog.Create -> CollectionNameDialog(
            title = stringResource(Res.string.recipe_collection_new),
            initial = "",
            collections = existing,
            onDismiss = { dialog = CollectionDialog.None },
            onSave = { name -> actions.onCreate(name).also { if (it) dialog = CollectionDialog.None } },
        )
        is CollectionDialog.Rename -> CollectionNameDialog(
            title = stringResource(Res.string.recipe_collection_rename),
            initial = d.collection.name,
            collections = existing,
            exceptId = d.collection.id,
            onDismiss = { dialog = CollectionDialog.None },
            onSave = { name -> actions.onRename(d.collection.id, name).also { if (it) dialog = CollectionDialog.None } },
        )
        is CollectionDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = CollectionDialog.None },
            text = { Text(stringResource(Res.string.recipe_collection_delete_confirm, d.collection.name)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = CollectionDialog.None
                    actions.onDelete(d.collection.id)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = CollectionDialog.None }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** "Kalas ⋮": the chosen collection's name with "Byt namn" and "Ta bort samlingen". */
@Composable
private fun CollectionHeader(collection: RecipeCollection, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            collection.name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.recipe_collection_menu, collection.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.recipe_collection_rename)) }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text(stringResource(Res.string.recipe_collection_delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

/** The name of a new or renamed collection; a name already used says so under the field instead of saving. */
@Composable
internal fun CollectionNameDialog(
    title: String,
    initial: String,
    collections: List<RecipeCollection>,
    onDismiss: () -> Unit,
    onSave: (String) -> Boolean,
    exceptId: String? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val taken = RecipeCollectionRules.nameTaken(collections, name, exceptId)
    val canSave = RecipeCollectionRules.cleanName(name) != null && !taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= RecipeCollectionRules.MAX_NAME_LENGTH) name = it },
                label = { Text(stringResource(Res.string.recipe_collection_name)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) {
                    { Text(stringResource(Res.string.recipe_collection_name_taken)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = canSave) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * "Lägg i samling" from a recipe: a tick per collection (saved at once, so "Klar" only closes) and "Ny samling", which
 * makes one with this recipe in it.
 */
@Composable
internal fun AddToCollectionSheet(
    state: RecipeDetailsState,
    onToggle: (collectionId: String, inside: Boolean) -> Unit,
    onCreate: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    MealSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.recipe_collection_sheet_title))
        if (state.collections.isEmpty()) {
            Text(
                stringResource(Res.string.recipe_collections_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.collections.forEach { c ->
            val inside = state.inCollection(c)
            CheckRow(c.name, inside, onCheckedChange = { onToggle(c.id, it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(Res.string.recipe_collection_new), modifier = Modifier.padding(start = Spacing.xs))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) }
        }
    }
    if (creating) {
        CollectionNameDialog(
            title = stringResource(Res.string.recipe_collection_new),
            initial = "",
            collections = state.collections,
            onDismiss = { creating = false },
            onSave = { name -> onCreate(name).also { if (it) creating = false } },
        )
    }
}
