package com.itsluminous.cleartravel.feature.trains.provider

import com.itsluminous.cleartravel.core.data.provider.TrainStatusProvider
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The always-available "manual" implementation of the [TrainStatusProvider] seam
 * (ADR-011). The DEFAULT refresh path for trains is NOT this interface: the Indian
 * Railways page has a live captcha, so the refresh is an interactive, user-visible
 * WebView flow ([com.itsluminous.cleartravel.feature.trains.pnr] package) that writes
 * results via `TrainRepository.applyStatusResult` directly. This stub keeps the
 * Hilt-bound provider seam alive so an API-backed provider (user-supplied key) can
 * slot in later without UI changes; it always fails, telling callers to use the
 * interactive check.
 */
@Singleton
class ManualTrainStatusProvider
    @Inject
    constructor() : TrainStatusProvider {
        override val providerId: String = PROVIDER_ID

        override suspend fun fetchPnrStatus(pnr: String): Result<TrainStatusResult> = Result.failure(InteractiveCheckRequiredException())

        companion object {
            const val PROVIDER_ID = "manual"
        }
    }

/**
 * Signals that no non-interactive provider is configured — the caller should route
 * the user to the interactive WebView PNR check instead. Never surfaced verbatim in
 * the UI (screens use string resources).
 */
class InteractiveCheckRequiredException : UnsupportedOperationException("interactive PNR check required")
