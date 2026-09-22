package com.itsluminous.cleartravel.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.R
import java.io.File

/**
 * Shared offline document viewer (ADR-017 flights viewer hoisted in ADR-027): renders
 * a stored image, or the FIRST page of a stored PDF, at FULL SCREEN BRIGHTNESS —
 * restored when the screen leaves composition — over a white background so barcodes
 * and stamps read reliably at a counter. [title] is already resolved so callers keep
 * ownership of their strings; the close/missing/image-description strings are the
 * design system's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    path: String,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(path) {
        bitmap = loadDocumentBitmap(path)
        onDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.designsystem_viewer_close,
                        onClick = onClose,
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.White)
                    .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = stringResource(R.string.designsystem_viewer_image_description),
                    modifier = Modifier.padding(8.dp),
                )
            } else {
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

/** Image decoded as-is; PDFs render their FIRST page white-backed at display width. */
fun loadDocumentBitmap(path: String): Bitmap? =
    runCatching {
        val file = File(path)
        if (!file.exists()) return null
        if (path.endsWith(".pdf", ignoreCase = true)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val scale = VIEWER_TARGET_WIDTH_PX.toFloat() / page.width
                        val target =
                            Bitmap.createBitmap(
                                VIEWER_TARGET_WIDTH_PX,
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        target.eraseColor(android.graphics.Color.WHITE)
                        page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        target
                    }
                }
            }
        } else {
            BitmapFactory.decodeFile(path)
        }
    }.getOrNull()

private const val VIEWER_TARGET_WIDTH_PX = 1080

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
