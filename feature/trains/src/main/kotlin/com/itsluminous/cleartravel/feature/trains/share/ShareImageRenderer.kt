package com.itsluminous.cleartravel.feature.trains.share

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Renders a composable OFF-SCREEN into a [Bitmap] (used for the share image).
 *
 * Approach (chosen over `GraphicsLayer.toImageBitmap()` because it needs no
 * placement inside the live composition and works on every Compose version the
 * project pins): a throw-away [ComposeView] is attached INVISIBLE to the activity's
 * decor view — attachment is what gives it a window recomposer + lifecycle owner —
 * then measured with an exact width, laid out, drawn onto a software canvas and
 * removed again, all synchronously on the main thread inside one call, so it never
 * reaches a frame the user could see. Must be called on the UI thread.
 */
internal object ShareImageRenderer {
    fun render(
        activity: Activity,
        widthPx: Int,
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
            // onMeasure creates the composition synchronously; layout then gives us
            // the intrinsic height of the card.
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
}
