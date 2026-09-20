package com.itsluminous.cleartravel.core.google

/**
 * Link-state contract stub (skeleton). The Google milestone replaces this with the
 * real Credential Manager flow; until then everything reports [NotLinked] and every
 * feature must behave fully offline/signed-out.
 */
sealed interface GoogleLinkState {
    /** No Google account is linked — the default, fully supported state. */
    data object NotLinked : GoogleLinkState

    /** A Google account is linked; [email] identifies it in Settings. */
    data class Linked(
        val email: String,
    ) : GoogleLinkState
}
