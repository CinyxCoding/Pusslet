package se.pusslet.feature.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.Store
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.QuantityParser
import se.pusslet.core.units.Units
import se.pusslet.ui.components.Labels
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** Edit one item: amount, unit, aisle (remembered for next time), note and an optional own price. */
@Composable
internal fun ItemSheet(item: ShoppingItem, onDismiss: () -> Unit, onSave: (ShoppingItem) -> Unit, onDelete: () -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var amount by rememberSaveable(item.id) { mutableStateOf(item.quantity?.let { Units.formatNumber(it) }.orEmpty()) }
    var unit by remember(item.id) { mutableStateOf(item.unit) }
    var aisle by remember(item.id) { mutableStateOf(item.aisle) }
    var note by rememberSaveable(item.id) { mutableStateOf(item.note) }
    var price by rememberSaveable(item.id) { mutableStateOf(item.price?.let { Units.formatNumber(it) }.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.item_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.item_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.take(MAX_NUMBER_CHARS) },
                    label = { Text(stringResource(Res.string.item_quantity)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                UnitPicker(unit, { unit = it }, Modifier.weight(1f))
            }
            AislePicker(aisle) { aisle = it }
            if (aisle != item.aisle) {
                Text(
                    stringResource(Res.string.item_aisle_learned),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(Res.string.item_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = it.take(MAX_NUMBER_CHARS) },
                label = { Text(stringResource(Res.string.item_price)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val quantity = amount.trim().takeIf { it.isNotEmpty() }?.let { QuantityParser.parseNumber(it) }
                    onSave(
                        item.copy(
                            name = name,
                            quantity = quantity,
                            unit = if (quantity == null) null else unit,
                            aisle = aisle,
                            note = note.trim(),
                            price = price.trim().replace(',', '.').toDoubleOrNull(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) { Text(stringResource(Res.string.action_save)) }
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.action_delete))
            }
        }
    }
}

private val pickableUnits = listOf(
    MeasureUnit.St,
    MeasureUnit.Forp,
    MeasureUnit.Kg,
    MeasureUnit.G,
    MeasureUnit.Hg,
    MeasureUnit.L,
    MeasureUnit.Dl,
    MeasureUnit.Burk,
    MeasureUnit.Flaska,
    MeasureUnit.Pase,
    MeasureUnit.Pkt,
    MeasureUnit.Knippe,
    MeasureUnit.Kruka,
)

@Composable
private fun UnitPicker(unit: MeasureUnit?, onPick: (MeasureUnit?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(unit?.let { stringResource(Labels.unit(it)) } ?: stringResource(Res.string.item_unit_none))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.item_unit_none)) }, onClick = {
                open = false
                onPick(null)
            })
            pickableUnits.forEach { u ->
                DropdownMenuItem(text = { Text(stringResource(Labels.unit(u))) }, onClick = {
                    open = false
                    onPick(u)
                })
            }
        }
    }
}

@Composable
private fun AislePicker(aisle: Aisle, onPick: (Aisle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(Res.string.item_aisle), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Labels.aisle(aisle)))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Aisle.defaultOrder.forEach { a ->
                    DropdownMenuItem(text = { Text(stringResource(Labels.aisle(a))) }, onClick = {
                        open = false
                        onPick(a)
                    })
                }
            }
        }
    }
}

@Composable
internal fun NewListDialog(onCreate: (String, ListKind) -> Unit, onDismiss: () -> Unit) {
    val default = stringResource(Res.string.shopping_list_default_name)
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ListKind.Grocery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_new_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.shopping_list_name)) },
                    placeholder = { Text(default) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KindChips(kind) { kind = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { default }, kind) }) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun KindChips(kind: ListKind, onPick: (ListKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterChip(
            selected = kind == ListKind.Grocery,
            onClick = { onPick(ListKind.Grocery) },
            label = { Text(stringResource(Res.string.shopping_list_kind_grocery)) },
        )
        FilterChip(
            selected = kind == ListKind.Other,
            onClick = { onPick(ListKind.Other) },
            label = { Text(stringResource(Res.string.shopping_list_kind_other)) },
        )
    }
}

@Composable
internal fun ListSettingsDialog(
    list: ShoppingList,
    stores: List<Store>,
    onSave: (ShoppingList) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(list.id) { mutableStateOf(list.name) }
    var kind by rememberSaveable(list.id) { mutableStateOf(list.kind) }
    var storeId by rememberSaveable(list.id) { mutableStateOf(list.storeId) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_list_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.shopping_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KindChips(kind) { kind = it }
                if (kind == ListKind.Grocery) {
                    Text(stringResource(Res.string.shopping_list_store), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(
                            selected = storeId == null,
                            onClick = { storeId = null },
                            label = { Text(stringResource(Res.string.shopping_store_default_order)) },
                        )
                        stores.forEach { s ->
                            FilterChip(selected = storeId == s.id, onClick = { storeId = s.id }, label = { Text(s.name) })
                        }
                    }
                }
                if (confirmDelete) {
                    Text(stringResource(Res.string.shopping_delete_list_confirm, list.name), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDelete) { Text(stringResource(Res.string.action_delete)) }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(Res.string.shopping_delete_list)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        list.copy(
                            name = name.ifBlank { list.name },
                            kind = kind,
                            storeId = storeId.takeIf {
                                kind ==
                                    ListKind.Grocery
                            },
                        ),
                    )
                },
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
internal fun UnknownBarcodeDialog(code: String, onName: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(code) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_scan_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.shopping_scan_not_found, code))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.item_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.shopping_scan_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onName(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

private const val MAX_NUMBER_CHARS = 8
