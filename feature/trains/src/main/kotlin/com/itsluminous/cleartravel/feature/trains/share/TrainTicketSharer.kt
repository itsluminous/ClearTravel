package com.itsluminous.cleartravel.feature.trains.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.feature.trains.R
import com.itsluminous.cleartravel.feature.trains.list.TrainTicketCard
import java.io.File
import java.time.Instant

/** Width of the rendered share image; tall enough text stays crisp in chat apps. */
private const val SHARE_IMAGE_WIDTH_PX = 1080

private const val SHARE_DIR = "share"
private const val MIME_PNG = "image/png"
private const val MIME_TEXT = "text/plain"

/**
 * Shares a ticket like the reference app: a PNG rendering of [ShareTicketCard] plus a
 * caption with the PNR deep link (`trains_share_text`). The image goes to
 * `cacheDir/share/` and is exposed through the app's `FileProvider` (authority
 * `<applicationId>.fileprovider`, declared in the app manifest). If rendering or
 * file I/O fails, degrades to a text-only share of the same caption so the user is
 * never left with a dead button.
 */
class TrainTicketSharer(
    private val context: Context,
) {
    /** True when the last [share] had to fall back to text-only (for a snackbar). */
    var lastShareWasTextOnly: Boolean = false
        private set

    fun share(card: TrainTicketCard) {
        val ticket = card.ticket
        val text = TicketShareLinks.buildShareText(ticket.pnr, context.getString(R.string.trains_share_text))
        val imageUri = runCatching { renderImage(card) }.getOrNull()
        lastShareWasTextOnly = imageUri == null
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
        val chooser =
            Intent.createChooser(send, context.getString(R.string.trains_share_chooser_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(chooser)
    }

    private fun renderImage(card: TrainTicketCard): Uri? {
        val activity = context.findActivity() ?: return null
        val now = Instant.now()
        val bitmap =
            ShareImageRenderer.render(activity, SHARE_IMAGE_WIDTH_PX) {
                // Always the light scheme: the image lands in other apps, where a dark
                // card on a light chat bubble reads poorly (reference is light too).
                ClearTravelTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        ShareTicketCard(
                            ticket = card.ticket,
                            passengers = card.passengers,
                            departureTime = card.departureTime,
                            now = now,
                        )
                    }
                }
            }
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, "ticket-${card.ticket.pnr}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

/** Remembers a [TrainTicketSharer] bound to the composition's (activity) context. */
@Composable
internal fun rememberTrainTicketSharer(): TrainTicketSharer {
    val context = LocalContext.current
    return remember(context) { TrainTicketSharer(context) }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
