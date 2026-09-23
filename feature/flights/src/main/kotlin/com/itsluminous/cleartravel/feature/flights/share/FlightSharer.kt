package com.itsluminous.cleartravel.feature.flights.share

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.designsystem.component.CardShare
import com.itsluminous.cleartravel.core.designsystem.component.CardShare.findActivity
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.R
import com.itsluminous.cleartravel.feature.flights.list.FlightCardBody

/** The self-contained card rendered OFF-SCREEN into the share image (no actions, no freshness line). */
@Composable
internal fun ShareFlightCard(
    flight: FlightJourney,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        FlightCardBody(flight = flight, showFreshness = false, modifier = Modifier.padding(16.dp))
    }
}

/** How a flight share ended (for the list's snackbar). */
enum class FlightShareOutcome {
    /** Image + caption handed to the share sheet. */
    SHARED,

    /** Rendering failed; the caption alone was shared. */
    SHARED_TEXT_ONLY,

    /** The link could not be built (cannot happen for a flight payload; kept for symmetry with trips). */
    FAILED,
}

/**
 * Shares a flight like a train ticket (ADR-020): a PNG of [ShareFlightCard] plus a
 * caption whose link carries the flight's ADD data (ADR-039 part A). Rendering, the
 * cache file and the intent are the shared [CardShare] plumbing; a render failure
 * degrades to a text-only share so the button never dies.
 */
class FlightSharer(
    private val context: Context,
) {
    fun share(flight: FlightJourney): FlightShareOutcome {
        val url =
            when (val result = FlightShareText.shareUrl(flight)) {
                is ShareUrlResult.Ok -> result.url
                is ShareUrlResult.TooLong -> return FlightShareOutcome.FAILED
            }
        val text =
            FlightShareText.buildShareText(
                flight = flight,
                url = url,
                template = context.getString(R.string.flights_share_text),
                routeSeparator = context.getString(R.string.flights_card_route_separator),
            )
        val imageUri = runCatching { renderImage(flight) }.getOrNull()
        CardShare.send(
            context = context,
            text = text,
            chooserTitle = context.getString(R.string.flights_share_chooser_title),
            imageUri = imageUri,
        )
        return if (imageUri == null) FlightShareOutcome.SHARED_TEXT_ONLY else FlightShareOutcome.SHARED
    }

    private fun renderImage(flight: FlightJourney): Uri? {
        val activity = context.findActivity() ?: return null
        val bitmap =
            CardShare.render(activity) {
                // Light scheme on purpose: the image lands in other apps' chat bubbles.
                ClearTravelTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        ShareFlightCard(flight = flight)
                    }
                }
            }
        return CardShare.writeShareImage(context, bitmap, "flight-${flight.airlineIata}${flight.flightNumber}.png")
    }
}

/** Remembers a [FlightSharer] bound to the composition's (activity) context. */
@Composable
internal fun rememberFlightSharer(): FlightSharer {
    val context = LocalContext.current
    return remember(context) { FlightSharer(context) }
}
