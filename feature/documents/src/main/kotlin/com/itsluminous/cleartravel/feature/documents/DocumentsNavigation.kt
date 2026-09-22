package com.itsluminous.cleartravel.feature.documents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.designsystem.component.DocumentViewerScreen
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.model.TravelDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Route of the Documents tab root. */
const val DOCUMENTS_ROUTE = "documents"

internal const val DOCUMENT_ID_ARG = "documentId"
private const val LIST_ROUTE = "documents_list"
private const val VIEWER_ROUTE = "documents_viewer/{$DOCUMENT_ID_ARG}"

private fun viewerRoute(documentId: String) = "documents_viewer/$documentId"

/**
 * Documents tab graph (ADR-027). Like Checklist, the tab hosts its own nested NavHost
 * (list → full-screen viewer) so the bottom bar stays highlighted on Documents and
 * the app module needs no per-feature wiring.
 */
fun NavGraphBuilder.documentsGraph() {
    composable(DOCUMENTS_ROUTE) {
        DocumentsTabHost()
    }
}

@Composable
internal fun DocumentsTabHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = LIST_ROUTE,
        modifier = modifier,
    ) {
        composable(LIST_ROUTE) {
            DocumentListScreen(
                onOpenDocument = { documentId -> navController.navigate(viewerRoute(documentId)) },
            )
        }
        composable(
            route = VIEWER_ROUTE,
            arguments = listOf(navArgument(DOCUMENT_ID_ARG) { type = NavType.StringType }),
        ) {
            DocumentViewerRoute(onClose = { navController.popBackStack() })
        }
    }
}

/** Resolves the route's document from Room and shows the shared full-brightness viewer. */
@Composable
internal fun DocumentViewerRoute(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ViewerState.Loading -> Unit
        ViewerState.Missing ->
            EmptyState(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.documents_viewer_missing),
                message = stringResource(R.string.documents_viewer_missing_message),
                modifier = modifier,
            )
        is ViewerState.Ready ->
            DocumentViewerScreen(
                path = current.document.filePath,
                title = current.document.name,
                onClose = onClose,
                modifier = modifier,
            )
    }
}

/** Viewer state: Room has not answered yet, the row is gone (deleted), or ready. */
sealed interface ViewerState {
    data object Loading : ViewerState

    data object Missing : ViewerState

    data class Ready(
        val document: TravelDocument,
    ) : ViewerState
}

@HiltViewModel
class DocumentViewerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        repository: TravelDocumentRepository,
    ) : ViewModel() {
        private val documentId: String = checkNotNull(savedStateHandle[DOCUMENT_ID_ARG])

        val state: StateFlow<ViewerState> =
            repository
                .observeDocument(documentId)
                .map<TravelDocument?, ViewerState> { document ->
                    if (document ==
                        null
                    ) {
                        ViewerState.Missing
                    } else {
                        ViewerState.Ready(document)
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, ViewerState.Loading)
    }
