package com.itsluminous.cleartravel.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.itsluminous.cleartravel.core.data.di.DatabaseModule
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.dao.AttachmentDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistPresetDao
import com.itsluminous.cleartravel.core.database.dao.FlightDao
import com.itsluminous.cleartravel.core.database.dao.ItineraryDao
import com.itsluminous.cleartravel.core.database.dao.TrainDao
import com.itsluminous.cleartravel.core.database.dao.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Hermetic database for the e2e suite: replaces [DatabaseModule] with an in-memory
 * Room instance, so every test process starts empty and nothing touches disk. The
 * built-in preset seeding mirrors production (`onCreate` callback, async) — the
 * checklist happy path exercises real presets; tests await their appearance.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        presetRepository: Provider<ChecklistPresetRepository>,
    ): ClearTravelDatabase =
        Room
            .inMemoryDatabaseBuilder(context, ClearTravelDatabase::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            presetRepository.get().seedBuiltInPresets()
                        }
                    }
                },
            ).build()

    @Provides
    fun provideTripDao(database: ClearTravelDatabase): TripDao = database.tripDao()

    @Provides
    fun provideItineraryDao(database: ClearTravelDatabase): ItineraryDao = database.itineraryDao()

    @Provides
    fun provideChecklistDao(database: ClearTravelDatabase): ChecklistDao = database.checklistDao()

    @Provides
    fun provideChecklistPresetDao(database: ClearTravelDatabase): ChecklistPresetDao = database.checklistPresetDao()

    @Provides
    fun provideTrainDao(database: ClearTravelDatabase): TrainDao = database.trainDao()

    @Provides
    fun provideFlightDao(database: ClearTravelDatabase): FlightDao = database.flightDao()

    @Provides
    fun provideAttachmentDao(database: ClearTravelDatabase): AttachmentDao = database.attachmentDao()
}
