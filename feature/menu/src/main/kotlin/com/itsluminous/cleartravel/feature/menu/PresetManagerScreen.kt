package com.itsluminous.cleartravel.feature.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.ChecklistPreset

/**
 * Menu → Manage presets. Built-in presets carry a badge and are protected from
 * edit/delete; duplicating one creates an editable user copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetManagerScreen(
    onBack: () -> Unit,
    onOpenPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PresetManagerViewModel = hiltViewModel(),
) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var presetPendingDelete by remember { mutableStateOf<ChecklistPreset?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PresetManagerEvent.Created -> onOpenPreset(event.presetId)
                PresetManagerEvent.Duplicated ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_preset_duplicated))
                PresetManagerEvent.Deleted ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_preset_deleted))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_presets_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = onBack,
                    )
                },
            )
        },
        floatingActionButton = {
            ClearTravelFab(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.menu_preset_add_fab),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val current = presets
        when {
            current == null -> Unit
            current.isEmpty() ->
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.FactCheck,
                    title = stringResource(R.string.menu_presets_empty_title),
                    message = stringResource(R.string.menu_presets_empty_message),
                    modifier = Modifier.padding(padding),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.id }) { preset ->
                        val copyName = stringResource(R.string.menu_preset_copy_name, preset.name)
                        PresetCard(
                            preset = preset,
                            onClick = { onOpenPreset(preset.id) },
                            onDuplicate = { viewModel.duplicatePreset(preset.id, copyName) },
                            onDelete = { presetPendingDelete = preset },
                        )
                    }
                }
        }
    }

    if (showCreateDialog) {
        CreatePresetDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                viewModel.createPreset(name)
            },
        )
    }

    presetPendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetPendingDelete = null },
            title = { Text(stringResource(R.string.menu_preset_delete_confirm_title)) },
            text = { Text(stringResource(R.string.menu_preset_delete_confirm_message, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        presetPendingDelete = null
                        viewModel.deletePreset(preset)
                    },
                ) {
                    Text(stringResource(R.string.menu_preset_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetPendingDelete = null }) {
                    Text(stringResource(R.string.menu_cancel))
                }
            },
        )
    }
}

@Composable
private fun PresetCard(
    preset: ChecklistPreset,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClearTravelCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (preset.builtIn) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(stringResource(R.string.menu_preset_built_in)) },
                )
            }
            ExplainableIcon(
                icon = Icons.Filled.ContentCopy,
                explanationRes = R.string.menu_preset_duplicate,
                targetSize = 40.dp,
                iconSize = 20.dp,
                onClick = onDuplicate,
            )
            if (!preset.builtIn) {
                ExplainableIcon(
                    icon = Icons.Filled.Delete,
                    explanationRes = R.string.menu_preset_delete,
                    targetSize = 40.dp,
                    iconSize = 20.dp,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun CreatePresetDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_preset_create_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.menu_preset_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.menu_preset_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.menu_cancel))
            }
        },
    )
}
