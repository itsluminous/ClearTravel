package com.itsluminous.cleartravel.core.designsystem.component

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared "share a card" plumbing (ADR-020 trains, ADR-039 flights/trips/checklists):
 * off-screen rendering of a composable into a bitmap, and the `ACTION_SEND` intents
 * for text-only and image-plus-caption shares. Lives here because two features need
 * it (ADR-036); it knows nothing about the payloads.
 */
object CardShare {
    /** Width of rendered share images; text stays crisp in chat apps at this size. */
    const val IMAGE_WIDTH_PX = 1080

    private const val SHARE_DIR = "share"
    private const val MIME_PNG = "image/png"
    private const val MIME_TEXT = "text/plain"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /**
     * Renders [content] OFF-SCREEN into a [Bitmap].
     *
     * Approach (chosen over `GraphicsLayer.toImageBitmap()` because it needs no
     * placement inside the live composition and works on every Compose version the
     * project pins): a throw-away [ComposeView] is attached INVISIBLE to the
     * activity's decor view — attachment is what gives it a window recomposer +
     * lifecycle owner — then measured with an exact width, laid out, drawn onto a
     * software canvas and removed again, all synchronously on the main thread inside
     * one call, so it never reaches a frame the user could see. UI thread only.
     */
    fun render(
        activity: Activity,
        widthPx: Int = IMAGE_WIDTH_PX,
        content: @Composable () -> Unit,
    ): Bitmap {
        val root = activity.window.decorView as ViewGroup
        val view =
            ComposeView(activity).apply {
                visibility = View.INVISIBLE
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent(content)
            }
        root.addView(view, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        try {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val height = view.measuredHeight.coerceAtLeast(1)
            view.layout(0, 0, widthPx, height)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        } finally {
            root.removeView(view)
        }
    }

    /**
     * Writes [bitmap] as `cacheDir/share/<fileName>` and returns its `FileProvider`
     * URI (authority `<applicationId>.fileprovider`, root `cache/share` in the app's
     * `file_paths.xml`).
     */
    fun writeShareImage(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    }

    /** Opens the share sheet with [text] (and the image at [imageUri] when non-null). */
    fun send(
        context: Context,
        text: String,
        chooserTitle: String,
        imageUri: Uri? = null,
    ) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                if (imageUri != null) {
                    type = MIME_PNG
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    clipData = ClipData.newRawUri(null, imageUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = MIME_TEXT
                }
            }
        val chooser = Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(chooser)
    }

    /** The hosting [Activity] of a Compose `LocalContext`, or null outside one. */
    tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
}
