package com.itsluminous.cleartravel.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared offline document viewer (ADR-017 → hoisted in ADR-027 → upgraded in ADR-030)
 * used by the Documents tab, boarding passes and booking confirmations. Renders a
 * stored image or one page of a stored PDF at FULL SCREEN BRIGHTNESS — restored when
 * the screen leaves composition — over a white background so barcodes and stamps read
 * reliably at a counter.
 *
 * Gestures: pinch to zoom (1×–6×), drag to pan when zoomed, double-tap to zoom in/out.
 * Toolbar: rotate the view by 90°, share the file, save a copy through the system file
 * picker. Multi-page PDFs page through with a bottom bar; zoom resets per page.
 * [title] is already resolved so callers keep ownership of their strings; every other
 * string is the design system's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    path: String,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewerState: DocumentViewerState = rememberDocumentViewerState(path),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    FullBrightness(context)

    var page by remember(path) { mutableStateOf<DocumentPage?>(null) }
    var loadFailed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, viewerState.pageIndex) {
        val loaded = withContext(Dispatchers.IO) { loadDocumentPage(path, viewerState.pageIndex) }
        page = loaded
        loadFailed = loaded == null
        loaded?.let { viewerState.updatePageCount(it.pageCount) }
    }

    val shareFailed = stringResource(R.string.designsystem_viewer_share_failed)
    val saveDone = stringResource(R.string.designsystem_viewer_save_done)
    val saveFailed = stringResource(R.string.designsystem_viewer_save_failed)
    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocumentFiles.mimeTypeFor(path))) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { copyDocumentTo(context, path, uri) }
                snackbarHostState.showSnackbar(if (ok) saveDone else saveFailed)
            }
        }
    val chooserTitle = stringResource(R.string.designsystem_viewer_share_chooser)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.designsystem_viewer_close,
                        onClick = onClose,
                    )
                },
                actions = {
                    ExplainableIcon(
                        icon = Icons.Filled.RotateRight,
                        explanationRes = R.string.designsystem_viewer_rotate,
                        onClick = { viewerState.rotateClockwise() },
                    )
                    ExplainableIcon(
                        icon = Icons.Filled.Share,
                        explanationRes = R.string.designsystem_viewer_share,
                        onClick = {
                            if (!shareDocumentFile(context, path, chooserTitle)) {
                                scope.launch { snackbarHostState.showSnackbar(shareFailed) }
                            }
                        },
                    )
                    ExplainableIcon(
                        icon = Icons.Filled.Download,
                        explanationRes = R.string.designsystem_viewer_save,
                        onClick = { saveLauncher.launch(DocumentFiles.suggestedFileName(title, path)) },
                    )
                },
            )
        },
        bottomBar = {
            if (viewerState.hasMultiplePages) {
                PageBar(viewerState)
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val current = page
            when {
                current != null -> ZoomableDocumentPage(current, viewerState)
                loadFailed ->
                    Text(
                        text = stringResource(R.string.designsystem_viewer_missing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        modifier = Modifier.padding(32.dp),
                    )
            }
        }
    }
}

/**
 * The rendered page laid out at its fitted size (so its ROTATED bounds fit the
 * viewport) and transformed through `graphicsLayer` from [DocumentViewerState].
 */
@Composable
private fun ZoomableDocumentPage(
    page: DocumentPage,
    state: DocumentViewerState,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    val contentSize = Size(page.bitmap.width.toFloat(), page.bitmap.height.toFloat())
    val fitted = state.fittedContentSize(contentSize, viewport)
    val density = LocalDensity.current

    LaunchedEffect(fitted, viewport, state.rotationDegrees) {
        state.reclamp(fitted, viewport)
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(state, fitted, viewport) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        state.applyGesture(centroid, pan, zoom, fitted, viewport)
                    }
                }.pointerInput(state, fitted, viewport) {
                    detectTapGestures(onDoubleTap = { tap -> state.toggleDoubleTapZoom(tap, fitted, viewport) })
                },
        contentAlignment = Alignment.Center,
    ) {
        if (fitted != Size.Zero) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.designsystem_viewer_image_description),
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .size(
                            width = with(density) { fitted.width.toDp() },
                            height = with(density) { fitted.height.toDp() },
                        ).graphicsLayer {
                            scaleX = state.scale
                            scaleY = state.scale
                            rotationZ = state.rotationDegrees.toFloat()
                            translationX = state.offset.x
                            translationY = state.offset.y
                        },
            )
        }
    }
}

@Composable
private fun PageBar(
    state: DocumentViewerState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                explanationRes = R.string.designsystem_viewer_previous_page,
                tint = if (state.canGoPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                onClick = { state.previousPage() },
            )
            Text(
                text = stringResource(R.string.designsystem_viewer_page_indicator, state.pageIndex + 1, state.pageCount),
                style = MaterialTheme.typography.bodyLarge,
            )
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                explanationRes = R.string.designsystem_viewer_next_page,
                tint = if (state.canGoNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                onClick = { state.nextPage() },
            )
        }
    }
}

/** Forces full screen brightness while in composition and restores the prior value on dispose. */
@Composable
private fun FullBrightness(context: Context) {
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose {
            window?.attributes =
                window?.attributes?.apply {
                    screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
