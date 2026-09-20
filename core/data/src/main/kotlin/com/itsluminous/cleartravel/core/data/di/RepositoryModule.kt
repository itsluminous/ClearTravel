package com.itsluminous.cleartravel.core.data.di

import com.itsluminous.cleartravel.core.data.preset.AssetBuiltInPresetSource
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetSource
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.data.repository.offline.DefaultSettingsRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineAttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineFlightRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTrainRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTripRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds repository contracts to their Room-backed offline-first implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTripRepository(impl: OfflineTripRepository): TripRepository

    @Binds
    @Singleton
    abstract fun bindItineraryRepository(impl: OfflineItineraryRepository): ItineraryRepository

    @Binds
    @Singleton
    abstract fun bindChecklistRepository(impl: OfflineChecklistRepository): ChecklistRepository

    @Binds
    @Singleton
    abstract fun bindChecklistPresetRepository(impl: OfflineChecklistPresetRepository): ChecklistPresetRepository

    @Binds
    @Singleton
    abstract fun bindTrainRepository(impl: OfflineTrainRepository): TrainRepository

    @Binds
    @Singleton
    abstract fun bindFlightRepository(impl: OfflineFlightRepository): FlightRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentRepository(impl: OfflineAttachmentRepository): AttachmentRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DefaultSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBuiltInPresetSource(impl: AssetBuiltInPresetSource): BuiltInPresetSource
}
