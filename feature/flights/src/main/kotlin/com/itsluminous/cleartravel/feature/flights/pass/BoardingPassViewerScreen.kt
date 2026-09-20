package com.itsluminous.cleartravel.feature.flights.pass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.feature.flights.R
import java.io.File

/**
 * Offline boarding-pass display for the gate (spec feature 2): renders the stored
 * image (or the first page of a stored PDF) at FULL SCREEN BRIGHTNESS — restored on
 * exit — over a white background so barcodes scan reliably.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardingPassViewerScreen(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Viewer reuse (ADR-017): booking confirmations pass their own title. */
    titleRes: Int = R.string.flights_pass_viewer_title,
) {
    val context = LocalContext.current

    // Full brightness while this screen shows; restored when it leaves composition.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose {
            window?.attributes =
                window?.attributes?.apply {
                    screenBrightness =
                        previous ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
        }
    }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(path) {
        bitmap = loadPassBitmap(path)
        onDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.flights_icon_close,
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
                    .background(androidx.compose.ui.graphics.Color.White)
                    .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = stringResource(R.string.flights_pass_image_description),
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.flights_pass_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}

/** Image decoded as-is; PDFs render their FIRST page white-backed at display width. */
private fun loadPassBitmap(path: String): Bitmap? =
    runCatching {
        val file = File(path)
        if (!file.exists()) return null
        if (path.endsWith(".pdf", ignoreCase = true)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val scale = TARGET_WIDTH_PX.toFloat() / page.width
                        val target =
                            Bitmap.createBitmap(
                                TARGET_WIDTH_PX,
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        target.eraseColor(Color.WHITE)
                        page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        target
                    }
                }
            }
        } else {
            BitmapFactory.decodeFile(path)
        }
    }.getOrNull()

private const val TARGET_WIDTH_PX = 1080

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
