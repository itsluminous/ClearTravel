package com.itsluminous.cleartravel.feature.trains.share

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.cleartravel.core.designsystem.component.CardShare
import com.itsluminous.cleartravel.core.designsystem.component.CardShare.findActivity
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.feature.trains.R
import com.itsluminous.cleartravel.feature.trains.list.TrainTicketCard
import java.time.Instant

/**
 * Shares a ticket like the reference app: a PNG rendering of [ShareTicketCard] plus a
 * caption with the PNR deep link (`trains_share_text`). Rendering, the cache file and
 * the `ACTION_SEND` intent are the shared [CardShare] plumbing (ADR-036/039). If
 * rendering or file I/O fails, degrades to a text-only share of the same caption so
 * the user is never left with a dead button.
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
        CardShare.send(
            context = context,
            text = text,
            chooserTitle = context.getString(R.string.trains_share_chooser_title),
            imageUri = imageUri,
        )
    }

    private fun renderImage(card: TrainTicketCard): Uri? {
        val activity = context.findActivity() ?: return null
        val now = Instant.now()
        val bitmap =
            CardShare.render(activity) {
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
        return CardShare.writeShareImage(context, bitmap, "ticket-${card.ticket.pnr}.png")
    }
}

/** Remembers a [TrainTicketSharer] bound to the composition's (activity) context. */
@Composable
internal fun rememberTrainTicketSharer(): TrainTicketSharer {
    val context = LocalContext.current
    return remember(context) { TrainTicketSharer(context) }
}
