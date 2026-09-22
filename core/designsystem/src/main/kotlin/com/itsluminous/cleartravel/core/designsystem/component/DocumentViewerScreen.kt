package com.itsluminous.cleartravel.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.itsluminous.cleartravel.core.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared offline document viewer (ADR-017 → hoisted in ADR-027 → upgraded in ADR-030,
 * chrome made responsive in ADR-034) used by the Documents tab, boarding passes and
 * booking confirmations. Renders a stored image or one page of a stored PDF over a
 * white background so barcodes and stamps read reliably at a counter. Screen
 * brightness is left exactly as the user has it (ADR-034 — the earlier forced full
 * brightness was removed).
 *
 * Gestures: pinch to zoom (1×–6×), drag to pan when zoomed, double-tap to zoom in/out,
 * single tap to toggle FULLSCREEN (all chrome and the system bars hidden; Back also
 * leaves it). Chrome: in portrait a top bar (close, rotate, fullscreen, share, save a
 * copy) and, for multi-page PDFs, a bottom page bar; in LANDSCAPE the same controls
 * collapse into a slim vertical rail on the end side so the content gets the whole
 * short axis. Zoom resets per page. [title] is already resolved so callers keep
 * ownership of their strings; every other string is the design system's own.
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
    val reader = LocalDocumentFileReader.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SystemBarsHidden(viewerState.isFullscreen)
    HideShellChrome(viewerState.isFullscreen)
    BackHandler(enabled = viewerState.isFullscreen) { viewerState.exitFullscreen() }

    // ADR-031: PDFs are rendered from a plaintext copy in cache (PdfRenderer needs a
    // seekable file) — materialised once per document and removed on dispose.
    var pdfFile by remember(path) { mutableStateOf<File?>(null) }
    var pdfReady by remember(path) { mutableStateOf(!DocumentFiles.isPdf(path)) }
    if (DocumentFiles.isPdf(path)) {
        DisposableEffect(path) {
            val job =
                scope.launch {
                    pdfFile = withContext(Dispatchers.IO) { reader.materialize(path, viewerPdfCacheDir(context)) }
                    pdfReady = true
                }
            onDispose {
                job.cancel()
                pdfFile?.delete()
            }
        }
    }

    var page by remember(path) { mutableStateOf<DocumentPage?>(null) }
    var loadFailed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, viewerState.pageIndex, pdfReady, pdfFile) {
        if (!pdfReady) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadDocumentPage(reader, path, viewerState.pageIndex, pdfFile) }
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
                val ok = withContext(Dispatchers.IO) { copyDocumentTo(context, reader, path, uri) }
                snackbarHostState.showSnackbar(if (ok) saveDone else saveFailed)
            }
        }
    val chooserTitle = stringResource(R.string.designsystem_viewer_share_chooser)

    val actions =
        ViewerActions(
            onClose = onClose,
            onRotate = { viewerState.rotateClockwise() },
            onFullscreen = { viewerState.toggleFullscreen() },
            onShare = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { shareDocumentFile(context, reader, path, chooserTitle) }
                    if (!ok) snackbarHostState.showSnackbar(shareFailed)
                }
            },
            onSave = { saveLauncher.launch(DocumentFiles.suggestedFileName(title, path)) },
        )

    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        Box(
            modifier = contentModifier.background(Color.White).testTag(DOCUMENT_VIEWER_CONTENT_TEST_TAG),
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

    when {
        // ADR-034 fullscreen: content only, edge to edge (the system bars are hidden too).
        viewerState.isFullscreen ->
            Box(modifier = modifier.fillMaxSize()) {
                content(Modifier.fillMaxSize())
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        // ADR-034 landscape: a slim end-side rail instead of top + bottom bars.
        isLandscape ->
            Surface(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        content(Modifier.weight(1f).fillMaxHeight())
                        ViewerSideRail(viewerState, actions)
                    }
                    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        else ->
            Scaffold(
                modifier = modifier,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        modifier = Modifier.testTag(DOCUMENT_VIEWER_TOOLBAR_TEST_TAG),
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            ExplainableIcon(
                                icon = Icons.Filled.Close,
                                explanationRes = R.string.designsystem_viewer_close,
                                onClick = actions.onClose,
                            )
                        },
                        actions = { ViewerActionIcons(actions) },
                    )
                },
                bottomBar = {
                    if (viewerState.hasMultiplePages) {
                        PageBar(viewerState)
                    }
                },
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding))
            }
    }
}

/** Test tag on the portrait top bar (e2e: gone in fullscreen and in landscape). */
const val DOCUMENT_VIEWER_TOOLBAR_TEST_TAG = "document_viewer_toolbar"

/** Test tag on the landscape side rail (e2e: slim, and gone in fullscreen). */
const val DOCUMENT_VIEWER_RAIL_TEST_TAG = "document_viewer_rail"

/** Test tag on the content area (e2e: a single tap toggles fullscreen). */
const val DOCUMENT_VIEWER_CONTENT_TEST_TAG = "document_viewer_content"

/** Width of the landscape rail — one 48dp touch target plus a hair of breathing room. */
private val RAIL_WIDTH = 56.dp

private class ViewerActions(
    val onClose: () -> Unit,
    val onRotate: () -> Unit,
    val onFullscreen: () -> Unit,
    val onShare: () -> Unit,
    val onSave: () -> Unit,
)

/** The four toolbar actions, in the same order in the top bar and in the rail. */
@Composable
private fun ViewerActionIcons(actions: ViewerActions) {
    ExplainableIcon(icon = Icons.Filled.RotateRight, explanationRes = R.string.designsystem_viewer_rotate, onClick = actions.onRotate)
    ExplainableIcon(
        icon = Icons.Filled.Fullscreen,
        explanationRes = R.string.designsystem_viewer_fullscreen,
        onClick = actions.onFullscreen,
    )
    ExplainableIcon(icon = Icons.Filled.Share, explanationRes = R.string.designsystem_viewer_share, onClick = actions.onShare)
    ExplainableIcon(icon = Icons.Filled.Download, explanationRes = R.string.designsystem_viewer_save, onClick = actions.onSave)
}

/**
 * Landscape chrome (ADR-034): close on top, the actions below it, and — for multi-page
 * PDFs — the page controls at the bottom, all in one [RAIL_WIDTH] column so the
 * content keeps the full height of the short axis.
 */
@Composable
private fun ViewerSideRail(
    state: DocumentViewerState,
    actions: ViewerActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxHeight().width(RAIL_WIDTH).testTag(DOCUMENT_VIEWER_RAIL_TEST_TAG), tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExplainableIcon(icon = Icons.Filled.Close, explanationRes = R.string.designsystem_viewer_close, onClick = actions.onClose)
            Spacer(Modifier.weight(1f))
            ViewerActionIcons(actions)
            if (state.hasMultiplePages) {
                Spacer(Modifier.weight(1f))
                PageControls(state, vertical = true)
            }
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
            PageControls(state, vertical = false)
        }
    }
}

/** Previous / "n of N" / next — laid out by the caller's Row, or stacked in the rail. */
@Composable
private fun PageControls(
    state: DocumentViewerState,
    vertical: Boolean,
) {
    val previous: @Composable () -> Unit = {
        ExplainableIcon(
            icon = if (vertical) Icons.Filled.KeyboardArrowUp else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            explanationRes = R.string.designsystem_viewer_previous_page,
            tint = if (state.canGoPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
            onClick = { state.previousPage() },
        )
    }
    val indicator: @Composable () -> Unit = {
        Text(
            text =
                if (vertical) {
                    stringResource(R.string.designsystem_viewer_page_indicator_short, state.pageIndex + 1, state.pageCount)
                } else {
                    stringResource(R.string.designsystem_viewer_page_indicator, state.pageIndex + 1, state.pageCount)
                },
            style = if (vertical) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
        )
    }
    val next: @Composable () -> Unit = {
        ExplainableIcon(
            icon = if (vertical) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            explanationRes = R.string.designsystem_viewer_next_page,
            tint = if (state.canGoNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
            onClick = { state.nextPage() },
        )
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            previous()
            indicator()
            next()
        }
    } else {
        previous()
        indicator()
        next()
    }
}

/**
 * ADR-034: hides the status and navigation bars while [hidden] (swipe shows them
 * transiently) and restores them when the flag drops or the viewer leaves composition.
 */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars())
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
                    detectTapGestures(
                        onTap = { state.toggleFullscreen() },
                        onDoubleTap = { tap -> state.toggleDoubleTapZoom(tap, fitted, viewport) },
                    )
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

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Scratch directory for decrypted PDF copies the viewer renders from (`cache/viewer-pdf`). */
private fun viewerPdfCacheDir(context: android.content.Context): File = File(context.cacheDir, "viewer-pdf")
