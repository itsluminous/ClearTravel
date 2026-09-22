package com.itsluminous.cleartravel.feature.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.itsluminous.cleartravel.core.model.TravelDocument
import java.time.LocalDate

/** Pickable document MIME types — images and PDFs (what the viewer renders). */
private val PICKER_MIME_TYPES = arrayOf("image/*", "application/pdf")

/**
 * The Documents tab (ADR-027): cards (type icon + name + expiry line), FAB → system
 * file picker → details dialog → copy + save; tap opens the full-brightness viewer;
 * long-press or the overflow icon offers edit/delete (delete confirms).
 */
@Composable
internal fun DocumentListScreen(
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pickedUri = uri.toString()
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message =
                when (event) {
                    is DocumentsEvent.Added -> context.getString(R.string.documents_snackbar_added)
                    DocumentsEvent.AddFailed -> context.getString(R.string.documents_snackbar_add_failed)
                    DocumentsEvent.Updated -> context.getString(R.string.documents_snackbar_updated)
                    is DocumentsEvent.Deleted -> context.getString(R.string.documents_snackbar_deleted, event.name)
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ClearTravelFab(onClick = { picker.launch(PICKER_MIME_TYPES) }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.documents_add_fab),
                )
            }
        },
    ) { padding ->
        val current = documents
        when {
            current == null -> Unit
            current.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.documents_empty_title),
                    message = stringResource(R.string.documents_empty_message),
                    modifier = Modifier.padding(padding),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.id }) { document ->
                        DocumentCard(
                            document = document,
                            onOpen = { onOpenDocument(document.id) },
                            onEdit = { editingId = document.id },
                            onDelete = { deletingId = document.id },
                        )
                    }
                }
        }
    }

    val picked = pickedUri
    if (picked != null) {
        DocumentDetailsDialog(
            initial = null,
            onDismiss = { pickedUri = null },
            onSave = { details ->
                pickedUri = null
                viewModel.addDocument(
                    uriString = picked,
                    type = details.type,
                    name = details.name,
                    fallbackName = context.getString(DocumentTypePresets.labelRes(details.type)),
                    expiryDate = details.expiryDate,
                    note = details.note,
                )
            },
        )
    }

    val editing = documents?.firstOrNull { it.id == editingId }
    if (editing != null) {
        DocumentDetailsDialog(
            initial = DocumentDetails(editing.type, editing.name, editing.expiryDate, editing.note),
            onDismiss = { editingId = null },
            onSave = { details ->
                editingId = null
                viewModel.updateDetails(
                    id = editing.id,
                    name = details.name.ifEmpty { context.getString(DocumentTypePresets.labelRes(details.type)) },
                    type = details.type,
                    expiryDate = details.expiryDate,
                    note = details.note,
                )
            },
        )
    }

    val deleting = documents?.firstOrNull { it.id == deletingId }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.documents_delete_title)) },
            text = { Text(stringResource(R.string.documents_delete_message, deleting.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingId = null
                        viewModel.delete(deleting.id)
                    },
                ) {
                    Text(stringResource(R.string.documents_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) {
                    Text(stringResource(R.string.documents_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    document: TravelDocument,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ClearTravelCard(
        modifier =
            modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplainableIcon(
                icon = DocumentTypePresets.icon(document.type),
                explanationRes = DocumentTypePresets.labelRes(document.type),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(text = document.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(DocumentTypePresets.labelRes(document.type)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpiryLine(document.expiryDate)
            }
            Box {
                ExplainableIcon(
                    icon = Icons.Filled.MoreVert,
                    explanationRes = R.string.documents_card_more,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.documents_action_open)) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.documents_action_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.documents_action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpiryLine(expiryDate: LocalDate?) {
    val today = remember { LocalDate.now() }
    val state = DocumentExpiry.stateOf(expiryDate, today)
    if (expiryDate == null || state == ExpiryState.NONE) return
    val formatted = DocumentExpiry.format(expiryDate)
    val (text, color) =
        when (state) {
            ExpiryState.EXPIRED -> stringResource(R.string.documents_card_expired, formatted) to MaterialTheme.colorScheme.error
            ExpiryState.EXPIRING_SOON ->
                stringResource(R.string.documents_card_expires_soon, formatted) to MaterialTheme.colorScheme.tertiary
            else -> stringResource(R.string.documents_card_expires, formatted) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(top = 2.dp))
}
