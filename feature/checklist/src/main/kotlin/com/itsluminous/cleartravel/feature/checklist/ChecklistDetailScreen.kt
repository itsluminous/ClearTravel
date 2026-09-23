package com.itsluminous.cleartravel.feature.checklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.CardShare
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.ReorderHandle
import com.itsluminous.cleartravel.core.designsystem.component.ReorderableListState
import com.itsluminous.cleartravel.core.designsystem.component.TextEditDialog
import com.itsluminous.cleartravel.core.designsystem.component.rememberReorderableListState
import com.itsluminous.cleartravel.core.designsystem.component.reorderableItem
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset

/**
 * Full-screen checklist detail (not a bottom sheet: packing lists get long, and the
 * add-item field needs stable room). Progress header, tap-to-toggle rows with a
 * drag handle (hold-and-drag to reorder, ADR-021) on the left and edit/delete icons on
 * the right, add field at the bottom, and the append-preset action supporting
 * cumulative multi-append (ADR-006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChecklistDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChecklistDetailViewModel = hiltViewModel(),
) {
    val checklist by viewModel.checklist.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAppendDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val reorderState =
        rememberReorderableListState(
            items = items,
            key = { it.id },
            onDrop = viewModel::moveItem,
        )

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChecklistDetailEvent.PresetAppended -> {
                    val message =
                        if (event.addedCount == 0) {
                            context.getString(R.string.checklist_items_appended_none)
                        } else {
                            context.resources.getQuantityString(
                                R.plurals.checklist_items_appended,
                                event.addedCount,
                                event.addedCount,
                            )
                        }
                    snackbarHostState.showSnackbar(message)
                }
                ChecklistDetailEvent.Deleted -> onBack()
                is ChecklistDetailEvent.ShareReady ->
                    CardShare.send(
                        context = context,
                        text = context.getString(R.string.checklist_share_text, event.checklistName, event.url),
                        chooserTitle = context.getString(R.string.checklist_share_chooser_title),
                    )
                ChecklistDetailEvent.ShareTooLong ->
                    snackbarHostState.showSnackbar(context.getString(R.string.checklist_share_too_long))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(checklist?.name.orEmpty()) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.checklist_back,
                        onClick = onBack,
                    )
                },
                actions = {
                    ExplainableIcon(
                        icon = Icons.Filled.Share,
                        explanationRes = R.string.checklist_share,
                        onClick = viewModel::share,
                    )
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        explanationRes = R.string.checklist_append_preset,
                        onClick = { showAppendDialog = true },
                    )
                    ExplainableIcon(
                        icon = Icons.Filled.Delete,
                        explanationRes = R.string.checklist_delete,
                        onClick = { showDeleteDialog = true },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProgressHeader(items = items)
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.checklist_no_items_title),
                    message = stringResource(R.string.checklist_no_items_message),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f), state = reorderState.lazyListState) {
                    items(reorderState.items, key = { it.id }) { item ->
                        ChecklistItemRow(
                            item = item,
                            reorderState = reorderState,
                            onToggle = { viewModel.setItemChecked(item.id, !item.checked) },
                            onEdit = { editingItemId = item.id },
                            onDelete = { viewModel.removeItem(item.id) },
                            modifier = Modifier.reorderableItem(reorderState, item.id, this),
                        )
                    }
                }
            }
            AddItemField(onAdd = viewModel::addItem)
        }
    }

    if (showAppendDialog) {
        AppendPresetDialog(
            presets = presets,
            onDismiss = { showAppendDialog = false },
            onAppend = { presetId ->
                showAppendDialog = false
                viewModel.appendPreset(presetId)
            },
        )
    }

    val editingItem = items.firstOrNull { it.id == editingItemId }
    if (editingItem != null) {
        TextEditDialog(
            title = stringResource(R.string.checklist_edit_item_title),
            label = stringResource(R.string.checklist_edit_item_label),
            initialValue = editingItem.text,
            confirmText = stringResource(R.string.checklist_save),
            dismissText = stringResource(R.string.checklist_cancel),
            onConfirm = { text ->
                viewModel.renameItem(editingItem.id, text)
                editingItemId = null
            },
            onDismiss = { editingItemId = null },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.checklist_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.checklist_delete_confirm_message, checklist?.name.orEmpty()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteChecklist()
                    },
                ) {
                    Text(stringResource(R.string.checklist_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.checklist_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProgressHeader(
    items: List<ChecklistItem>,
    modifier: Modifier = Modifier,
) {
    val done = items.count { it.checked }
    val total = items.size
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.checklist_progress, done, total),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val progress = if (total == 0) 0f else done.toFloat() / total
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun ChecklistItemRow(
    item: ChecklistItem,
    reorderState: ReorderableListState<ChecklistItem>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReorderHandle(state = reorderState, itemKey = item.id)
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        ExplainableIcon(
            icon = Icons.Filled.Edit,
            explanationRes = R.string.checklist_item_edit,
            targetSize = 40.dp,
            iconSize = 20.dp,
            onClick = onEdit,
        )
        ExplainableIcon(
            icon = Icons.Filled.Delete,
            explanationRes = R.string.checklist_item_delete,
            targetSize = 40.dp,
            iconSize = 20.dp,
            onClick = onDelete,
        )
    }
}

@Composable
private fun AddItemField(
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
            label = { Text(stringResource(R.string.checklist_add_item_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ExplainableIcon(
            icon = Icons.Filled.Add,
            explanationRes = R.string.checklist_add_item_action,
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            },
        )
    }
}

/** Picker for the append-preset action — usable repeatedly to combine presets. */
@Composable
private fun AppendPresetDialog(
    presets: List<ChecklistPreset>,
    onDismiss: () -> Unit,
    onAppend: (presetId: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checklist_append_preset_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.checklist_append_preset_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.checklist_append_preset_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        presets.forEach { preset ->
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onAppend(preset.id) }
                                        .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.checklist_cancel))
            }
        },
    )
}
