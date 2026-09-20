package com.itsluminous.cleartravel.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem

/**
 * Preset editor. User presets: rename + add/remove/reorder items. Built-in presets
 * open read-only with a duplicate-to-customize banner (spec: editing a preset never
 * mutates existing checklists — appends are copies, ADR-006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PresetEditViewModel = hiltViewModel(),
) {
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val editable = preset?.builtIn == false

    var name by rememberSaveable { mutableStateOf("") }
    var nameInitialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preset?.id) {
        if (!nameInitialised && preset != null) {
            name = preset?.name.orEmpty()
            nameInitialised = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editable) R.string.menu_preset_edit_title else R.string.menu_preset_view_title,
                        ),
                    )
                },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = {
                            if (editable) viewModel.rename(name)
                            onBack()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (preset?.builtIn == true) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.menu_preset_built_in_readonly),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Text(
                    text = preset?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.menu_preset_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.menu_preset_no_items_title),
                    message = stringResource(R.string.menu_preset_no_items_message),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp)) {
                    items(items, key = { it.id }) { item ->
                        PresetItemRow(
                            item = item,
                            editable = editable,
                            isFirst = item.id == items.first().id,
                            isLast = item.id == items.last().id,
                            onMoveUp = { viewModel.moveItem(item.id, up = true) },
                            onMoveDown = { viewModel.moveItem(item.id, up = false) },
                            onRemove = { viewModel.removeItem(item.id) },
                        )
                    }
                }
            }
            if (editable) {
                AddPresetItemField(onAdd = viewModel::addItem)
            }
        }
    }
}

@Composable
private fun PresetItemRow(
    item: ChecklistPresetItem,
    editable: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            if (!isFirst) {
                ExplainableIcon(
                    icon = Icons.Filled.KeyboardArrowUp,
                    explanationRes = R.string.menu_preset_item_move_up,
                    targetSize = 40.dp,
                    iconSize = 20.dp,
                    onClick = onMoveUp,
                )
            }
            if (!isLast) {
                ExplainableIcon(
                    icon = Icons.Filled.KeyboardArrowDown,
                    explanationRes = R.string.menu_preset_item_move_down,
                    targetSize = 40.dp,
                    iconSize = 20.dp,
                    onClick = onMoveDown,
                )
            }
            ExplainableIcon(
                icon = Icons.Filled.Close,
                explanationRes = R.string.menu_preset_item_remove,
                targetSize = 40.dp,
                iconSize = 20.dp,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun AddPresetItemField(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.menu_preset_add_item_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ExplainableIcon(
            icon = Icons.Filled.Add,
            explanationRes = R.string.menu_preset_add_item_action,
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            },
        )
    }
}
