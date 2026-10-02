package com.itsluminous.cleartravel.ui

import androidx.lifecycle.ViewModel
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Where an incoming PNR link (`cleartravel://pnr/<pnr>`, its https twin — ADR-020 —
 * and the ADR-044 reminder's tap) sends the user. Pure so it is unit-tested without
 * Hilt; [PnrLinkViewModel] feeds it the repository lookup.
 */
sealed interface PnrLinkRoute {
    /** A live, non-archived ticket already carries this PNR: open ITS status check (the captcha WebView). */
    data class CheckExisting(
        val ticketId: String,
    ) : PnrLinkRoute

    /** Nobody has it yet (or only an archived copy): the add form carrying the PNR, as before ADR-044. */
    data class AddNew(
        val pnr: String,
    ) : PnrLinkRoute

    companion object {
        fun resolve(
            pnr: String,
            existing: TrainTicket?,
        ): PnrLinkRoute =
            if (existing != null && existing.deletedAt == null && !existing.archived) {
                CheckExisting(existing.id)
            } else {
                AddNew(pnr)
            }

        /** The Journeys landing for [CheckExisting]: Trains segment, PNR check for the ticket (ADR-023 landing). */
        fun landing(route: CheckExisting): JourneysDeepLink =
            JourneysDeepLink(
                target = DeepLinkContract.TARGET_TRAIN,
                entityId = route.ticketId,
                trainsAction = TrainsLandingAction.OPEN_PNR_CHECK,
            )
    }
}

/**
 * ADR-044 §7: resolves a parked PNR link against the (encrypted) ticket store. The
 * shell calls [resolve] from INSIDE the lock gate — the intent may arrive while the
 * vault is still locked, and Room must not be touched before the gate opens.
 */
@HiltViewModel
class PnrLinkViewModel
    @Inject
    constructor(
        private val trainRepository: TrainRepository,
    ) : ViewModel() {
        suspend fun resolve(pnr: String): PnrLinkRoute = PnrLinkRoute.resolve(pnr, trainRepository.findByPnr(pnr))
    }
