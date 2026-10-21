package se.pusslet.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.QuantityParser
import se.pusslet.core.units.Units
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.OutlinedValueField
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class ListDialog { None, Create, Rename, Delete, PickStore }

@Composable
internal fun ListDialogs(
    dialog: ListDialog,
    state: ShoppingUiState,
    onDismiss: () -> Unit,
    onCreate: (String, ListKind) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPickStore: (String?) -> Unit,
    onOpenStores: () -> Unit,
) {
    when (dialog) {
        ListDialog.None -> Unit
        ListDialog.Create -> NewListDialog(onDismiss = onDismiss, onCreate = { name, kind ->
            onCreate(name, kind)
            onDismiss()
        })
        ListDialog.Rename -> NameDialog(
            title = stringResource(Res.string.shopping_rename_list),
            initial = state.list?.name.orEmpty(),
            onDismiss = onDismiss,
            onSave = {
                onRename(it)
                onDismiss()
            },
        )
        ListDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.shopping_delete_list)) },
            text = { Text(stringResource(Res.string.shopping_delete_list_confirm, state.list?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    onDismiss()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        )
        ListDialog.PickStore -> StorePickerDialog(state, onDismiss, onPickStore, onOpenStores)
    }
}

@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreate: (String, ListKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ListKind.Grocery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_new_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.shopping_list_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ListKind.entries.forEachIndexed { index, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(index, ListKind.entries.size),
                            colors = SelectionColors.segmentedButton(),
                            label = { Text(stringResource(Labels.listKind(k)), maxLines = 1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, kind) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, label: String? = null) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = if (label != null) {
                    { Text(label) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun StorePickerDialog(state: ShoppingUiState, onDismiss: () -> Unit, onPick: (String?) -> Unit, onOpenStores: () -> Unit) {
    val current = state.list?.storeId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_menu_choose_store)) },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioRow(stringResource(Res.string.shopping_store_none), selected = current == null) {
                    onPick(null)
                    onDismiss()
                }
                state.stores.forEach { store ->
                    RadioRow(store.name, selected = current == store.id) {
                        onPick(store.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenStores()
            }) { Text(stringResource(Res.string.shopping_stores)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
internal fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun UnknownProductDialog(barcode: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_unknown_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(Res.string.shopping_unknown_body, barcode))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.shopping_item_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.shopping_off_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * Edit one item (UX plan, Handla 6): name, amount with a unit picker ("Ingen enhet"), the aisle picker ("Sparas till
 * nästa gång": a corrected aisle is remembered for the family) and a note. The own price and who added it sit under
 * "Fler detaljer".
 */
@Composable
internal fun ItemEditorSheet(
    item: ShoppingItem,
    people: Map<String, FamilyPerson>,
    onDismiss: () -> Unit,
    onSave: (ShoppingItem) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var amount by rememberSaveable(item.id) { mutableStateOf(item.quantity?.let(Units::formatNumber).orEmpty()) }
    var unit by remember(item.id) { mutableStateOf(item.unit) }
    var aisle by remember(item.id) { mutableStateOf(item.aisle) }
    var note by rememberSaveable(item.id) { mutableStateOf(item.note) }
    var price by rememberSaveable(item.id) { mutableStateOf(item.price?.let(::formatPrice).orEmpty()) }
    var moreDetails by rememberSaveable(item.id) { mutableStateOf(item.price != null) }
    val parsedAmount = amount.trim().takeIf { it.isNotEmpty() }?.let { QuantityParser.parsePrefix(it).quantity?.value }
    val amountInvalid = amount.isNotBlank() && parsedAmount == null
    val parsedPrice = price.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val priceInvalid = price.isNotBlank() && parsedPrice == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.shopping_edit_item), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.shopping_item_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(Res.string.shopping_amount)) },
                    singleLine = true,
                    isError = amountInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                UnitPicker(unit, onPick = { unit = it }, modifier = Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AislePicker(aisle, onPick = { aisle = it })
                Text(
                    stringResource(Res.string.shopping_aisle_remembered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.lg),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(Res.string.shopping_note)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            // An invalid price keeps "Fler detaljer" open, so the reason Spara is off stays in sight.
            val showMore = moreDetails || priceInvalid
            MoreDetailsToggle(expanded = showMore, onToggle = { moreDetails = !showMore })
            if (showMore) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text(stringResource(Res.string.shopping_price)) },
                    singleLine = true,
                    isError = priceInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                item.addedBy?.let(people::get)?.let { person ->
                    Text(
                        stringResource(Res.string.shopping_added_by, person.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_delete)) }
                Button(
                    onClick = {
                        onSave(
                            item.copy(
                                name = name.trim(),
                                quantity = parsedAmount,
                                unit = if (parsedAmount == null) null else unit,
                                aisle = aisle,
                                note = note.trim(),
                                price = parsedPrice,
                            ),
                        )
                    },
                    enabled = name.isNotBlank() && !amountInvalid && !priceInvalid,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.action_save)) }
            }
        }
    }
}

/** "Fler detaljer ⌄": shows or hides the rarely used fields. */
@Composable
private fun MoreDetailsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.shopping_more_details), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
}

/** The units people actually shop in, most common first. */
private val shoppingUnits = listOf(
    MeasureUnit.St,
    MeasureUnit.Kg,
    MeasureUnit.G,
    MeasureUnit.Hg,
    MeasureUnit.L,
    MeasureUnit.Dl,
    MeasureUnit.Forp,
    MeasureUnit.Pkt,
    MeasureUnit.Burk,
    MeasureUnit.Flaska,
    MeasureUnit.Pase,
    MeasureUnit.Knippe,
    MeasureUnit.Kruka,
)

/** "Enhet ▾": "Ingen enhet" instead of an empty or "–" value. */
@Composable
internal fun UnitPicker(unit: MeasureUnit?, onPick: (MeasureUnit?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(Res.string.shopping_unit_none)
    Box(modifier) {
        OutlinedValueField(stringResource(Res.string.shopping_unit), unit?.symbol, onClick = { open = true }, placeholder = none)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = {
                onPick(null)
                open = false
            })
            (shoppingUnits + listOfNotNull(unit).filter { it !in shoppingUnits }).forEach { u ->
                DropdownMenuItem(text = { Text(u.symbol) }, onClick = {
                    onPick(u)
                    open = false
                })
            }
        }
    }
}

/** "Avdelning ▾" with every aisle in the usual walking order. */
@Composable
private fun AislePicker(aisle: Aisle, onPick: (Aisle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedValueField(stringResource(Res.string.shopping_aisle), stringResource(Labels.aisle(aisle)), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Aisle.defaultOrder.forEach { a ->
                DropdownMenuItem(text = { Text(stringResource(Labels.aisle(a))) }, onClick = {
                    onPick(a)
                    open = false
                })
            }
        }
    }
}

/** "12,5" – plain decimals for prices (no ½ like kitchen amounts). */
internal fun formatPrice(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    val text = if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    return text.replace('.', ',')
}
