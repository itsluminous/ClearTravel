package com.itsluminous.cleartravel.feature.trains.di

import com.itsluminous.cleartravel.core.data.provider.TrainStatusProvider
import com.itsluminous.cleartravel.feature.trains.prefill.OcrTrainPrefillSource
import com.itsluminous.cleartravel.feature.trains.prefill.TrainPrefillSource
import com.itsluminous.cleartravel.feature.trains.provider.ManualTrainStatusProvider
import com.itsluminous.cleartravel.feature.trains.seatmap.AssetSeatLayoutSource
import com.itsluminous.cleartravel.feature.trains.seatmap.SeatLayoutSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bindings owned by `feature:trains`: the always-available manual status provider
 * (keeps the ADR-005/ADR-011 provider seam alive — the interactive WebView refresh
 * bypasses the interface by design), the OCR-backed prefill seam and the seat-layout
 * asset source (ADR-022).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TrainsModule {
    @Binds
    abstract fun bindTrainStatusProvider(impl: ManualTrainStatusProvider): TrainStatusProvider

    @Binds
    abstract fun bindTrainPrefillSource(impl: OcrTrainPrefillSource): TrainPrefillSource

    @Binds
    abstract fun bindSeatLayoutSource(impl: AssetSeatLayoutSource): SeatLayoutSource
}
