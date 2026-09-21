package com.itsluminous.cleartravel.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.DatabaseConstants
import com.itsluminous.cleartravel.core.database.DatabaseMigrations
import com.itsluminous.cleartravel.core.database.dao.AttachmentDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistPresetDao
import com.itsluminous.cleartravel.core.database.dao.FlightDao
import com.itsluminous.cleartravel.core.database.dao.ItineraryDao
import com.itsluminous.cleartravel.core.database.dao.TrainDao
import com.itsluminous.cleartravel.core.database.dao.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Provides the Room database, its DAOs, and the shared [Clock] every repository uses
 * to bump `updatedAt` (ADR-002). Built-in checklist presets are seeded from the
 * `onCreate` callback on first database creation (ADR-006) — the seeder itself is
 * additionally idempotent, so restores/merges never duplicate presets.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
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
            .databaseBuilder(context, ClearTravelDatabase::class.java, DatabaseConstants.DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Async on purpose: onCreate fires inside the first db open;
                        // the seed queries run after the open completes.
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
