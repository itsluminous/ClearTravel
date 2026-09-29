package com.itsluminous.cleartravel.ui.intake

import androidx.lifecycle.ViewModel
import com.itsluminous.cleartravel.core.data.intake.SharedTextClassifier
import com.itsluminous.cleartravel.core.data.intake.SharedTextKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a confirmed shared-text choice sends the text — consumed by `MainActivity`. */
sealed interface TextIntakeRoute {
    val text: String

    /** The existing IRCTC SMS/email path → train ticket form. */
    data class Train(
        override val text: String,
    ) : TextIntakeRoute

    /** The airline SMS/email path → flight form (ADR-042). */
    data class Flight(
        override val text: String,
    ) : TextIntakeRoute
}

data class SharedTextIntakeUiState(
    val text: String? = null,
    /** Classifier preselection (never null while active — a tie is TRAIN). */
    val suggested: SharedTextKind? = null,
    /** The user's current selection (starts as [suggested]). */
    val selected: SharedTextKind? = null,
    /** Set once the user confirms — the shell routes and then calls [SharedTextIntakeViewModel.reset]. */
    val route: TextIntakeRoute? = null,
) {
    val active: Boolean get() = text != null
}

/**
 * "What's this text?" intake for `text/plain` shares that are neither a Clear Travel
 * link nor a Maps link (ADR-042): the pure [SharedTextClassifier] PRESELECTS train or
 * flight, the user confirms or overrides, and the resulting [TextIntakeRoute] is
 * exposed for the shell. Mirrors [SharedFileIntakeViewModel] minus the async probe —
 * classification is synchronous, so there is no "detecting" phase.
 */
class SharedTextIntakeViewModel : ViewModel() {
    private val state = MutableStateFlow(SharedTextIntakeUiState())
    val uiState: StateFlow<SharedTextIntakeUiState> = state.asStateFlow()

    /** Starts intake for [text]: opens the dialog with the classifier's suggestion selected. */
    fun start(text: String) {
        val suggested = SharedTextClassifier.classify(text)
        state.value = SharedTextIntakeUiState(text = text, suggested = suggested, selected = suggested)
    }

    fun select(kind: SharedTextKind) = state.update { it.copy(selected = kind) }

    /** Confirms the current selection; no-op until something is selected. */
    fun confirm() {
        state.update { current ->
            val text = current.text ?: return@update current
            val kind = current.selected ?: return@update current
            current.copy(route = routeFor(kind, text))
        }
    }

    /** Cancels the dialog or clears a consumed route. */
    fun reset() {
        state.value = SharedTextIntakeUiState()
    }

    private fun routeFor(
        kind: SharedTextKind,
        text: String,
    ): TextIntakeRoute =
        when (kind) {
            SharedTextKind.TRAIN -> TextIntakeRoute.Train(text)
            SharedTextKind.FLIGHT -> TextIntakeRoute.Flight(text)
        }
}
