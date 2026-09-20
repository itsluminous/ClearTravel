package com.itsluminous.cleartravel.feature.checklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.model.ChecklistPreset

/** The checklist list: every checklist as a progress card, FAB to create a new one. */
@Composable
internal fun ChecklistListScreen(
    onOpenChecklist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChecklistListViewModel = hiltViewModel(),
) {
    val rows by viewModel.checklists.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChecklistListEvent.Created -> onOpenChecklist(event.checklistId)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ClearTravelFab(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.checklist_add_fab),
                )
            }
        },
    ) { padding ->
        val current = rows
        when {
            current == null -> Unit
            current.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.Checklist,
                    title = stringResource(R.string.checklist_empty_title),
                    message = stringResource(R.string.checklist_empty_message),
                    modifier = Modifier.padding(padding),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.checklist.id }) { row ->
                        ChecklistCard(row = row, onClick = { onOpenChecklist(row.checklist.id) })
                    }
                }
        }
    }

    if (showCreateDialog) {
        CreateChecklistDialog(
            presets = presets,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, presetId ->
                showCreateDialog = false
                viewModel.createChecklist(name, presetId)
            },
        )
    }
}

@Composable
private fun ChecklistCard(
    row: ChecklistRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClearTravelCard(modifier = modifier.clickable(onClick = onClick)) {
        Text(text = row.checklist.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.checklist_progress, row.doneCount, row.totalCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        val progress = if (row.totalCount == 0) 0f else row.doneCount.toFloat() / row.totalCount
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** Name + optional start-from-preset picker; "Blank" is the default. */
@Composable
private fun CreateChecklistDialog(
    presets: List<ChecklistPreset>,
    onDismiss: () -> Unit,
    onCreate: (name: String, presetId: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedPresetId by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checklist_create_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.checklist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.checklist_start_from_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    PresetChoiceRow(
                        label = stringResource(R.string.checklist_start_blank),
                        selected = selectedPresetId == null,
                        onSelect = { selectedPresetId = null },
                    )
                    presets.forEach { preset ->
                        PresetChoiceRow(
                            label = preset.name,
                            selected = selectedPresetId == preset.id,
                            onSelect = { selectedPresetId = preset.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, selectedPresetId) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.checklist_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.checklist_cancel))
            }
        },
    )
}

@Composable
private fun PresetChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { role = Role.RadioButton }
                .clickable(onClick = onSelect)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
