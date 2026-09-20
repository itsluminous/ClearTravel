package com.itsluminous.cleartravel.ui

import android.content.Intent
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract

/**
 * A resolved notification deep link (ADR-013's `DeepLinkContract` extras): open the
 * Journeys tab on [target]'s segment and expand [entityId]'s detail sheet. [nonce]
 * makes consecutive links to the same entity distinct so effects re-fire.
 */
data class JourneysDeepLink(
    val target: String,
    val entityId: String,
    val nonce: Long = System.nanoTime(),
) {
    companion object {
        /** Parses the contract extras from [intent]; null when absent/incomplete. */
        fun fromIntent(intent: Intent): JourneysDeepLink? {
            val target = intent.getStringExtra(DeepLinkContract.EXTRA_TARGET) ?: return null
            val entityId = intent.getStringExtra(DeepLinkContract.EXTRA_ENTITY_ID) ?: return null
            if (target != DeepLinkContract.TARGET_TRAIN && target != DeepLinkContract.TARGET_FLIGHT) return null
            return JourneysDeepLink(target = target, entityId = entityId)
        }
    }
}
