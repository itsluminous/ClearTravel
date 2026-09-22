package com.itsluminous.cleartravel.core.google.di

import android.content.Context
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.data.security.AppFileLayout
import com.itsluminous.cleartravel.core.google.auth.DataStoreGoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.DefaultGoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleAuthorizer
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.auth.LinkedAccountAccessTokenProvider
import com.itsluminous.cleartravel.core.google.auth.PlayServicesGoogleAuthorizer
import com.itsluminous.cleartravel.core.google.backup.DefaultDriveBackupService
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.FreshInstallDetector
import com.itsluminous.cleartravel.core.google.backup.RepositoryFreshInstallDetector
import com.itsluminous.cleartravel.core.google.calendar.CalendarClient
import com.itsluminous.cleartravel.core.google.calendar.CalendarEventStringsFactory
import com.itsluminous.cleartravel.core.google.calendar.CalendarSyncEngine
import com.itsluminous.cleartravel.core.google.calendar.CalendarSyncStateStore
import com.itsluminous.cleartravel.core.google.calendar.PreferencesCalendarSyncStateStore
import com.itsluminous.cleartravel.core.google.calendar.RestCalendarClient
import com.itsluminous.cleartravel.core.google.drive.AttachmentFileResolver
import com.itsluminous.cleartravel.core.google.drive.DriveClient
import com.itsluminous.cleartravel.core.google.drive.DriveFolderResolver
import com.itsluminous.cleartravel.core.google.drive.DriveUploadEngine
import com.itsluminous.cleartravel.core.google.drive.RestDriveClient
import com.itsluminous.cleartravel.core.google.work.ScheduledBackupScheduler
import com.itsluminous.cleartravel.core.google.work.WorkManagerGoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.work.WorkManagerScheduledBackupScheduler
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Hilt wiring of `core:google`: real clients bound behind their fake-able seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleModule {
    @Binds abstract fun bindGoogleAuthorizer(impl: PlayServicesGoogleAuthorizer): GoogleAuthorizer

    @Binds abstract fun bindGoogleLinkStore(impl: DataStoreGoogleLinkStore): GoogleLinkStore

    @Binds abstract fun bindAccessTokenProvider(impl: LinkedAccountAccessTokenProvider): GoogleAccessTokenProvider

    @Binds abstract fun bindCalendarClient(impl: RestCalendarClient): CalendarClient

    @Binds abstract fun bindDriveClient(impl: RestDriveClient): DriveClient

    @Binds abstract fun bindCalendarSyncStateStore(impl: PreferencesCalendarSyncStateStore): CalendarSyncStateStore

    @Binds abstract fun bindGoogleSyncScheduler(impl: WorkManagerGoogleSyncScheduler): GoogleSyncScheduler

    /** ADR-037: the automatic-backup periodic job. */
    @Binds abstract fun bindScheduledBackupScheduler(impl: WorkManagerScheduledBackupScheduler): ScheduledBackupScheduler

    companion object {
        @Provides
        @Singleton
        fun provideGoogleAccountManager(
            authorizer: GoogleAuthorizer,
            linkStore: GoogleLinkStore,
            scheduler: GoogleSyncScheduler,
        ): GoogleAccountManager = DefaultGoogleAccountManager(authorizer, linkStore, scheduler)

        @Provides
        @Singleton
        fun provideCalendarSyncEngine(
            linkStore: GoogleLinkStore,
            calendarClient: CalendarClient,
            stateStore: CalendarSyncStateStore,
            tripRepository: TripRepository,
            itineraryRepository: ItineraryRepository,
            trainRepository: TrainRepository,
            flightRepository: FlightRepository,
            stringsFactory: CalendarEventStringsFactory,
        ): CalendarSyncEngine =
            CalendarSyncEngine(
                linkStore = linkStore,
                calendarClient = calendarClient,
                stateStore = stateStore,
                tripRepository = tripRepository,
                itineraryRepository = itineraryRepository,
                trainRepository = trainRepository,
                flightRepository = flightRepository,
                strings = stringsFactory.create(),
                calendarName = stringsFactory.calendarName(),
            )

        @Provides
        @Singleton
        fun provideDriveFolderResolver(
            linkStore: GoogleLinkStore,
            driveClient: DriveClient,
            stringsFactory: CalendarEventStringsFactory,
        ): DriveFolderResolver = DriveFolderResolver(linkStore, driveClient, stringsFactory.driveFolderName())

        @Provides
        @Singleton
        fun provideDriveUploadEngine(
            @ApplicationContext context: Context,
            linkStore: GoogleLinkStore,
            attachmentRepository: AttachmentRepository,
            flightRepository: FlightRepository,
            driveClient: DriveClient,
            folderResolver: DriveFolderResolver,
            keyVault: KeyVault,
            fileCipher: LocalFileCipher,
        ): DriveUploadEngine =
            DriveUploadEngine(
                linkStore = linkStore,
                attachmentRepository = attachmentRepository,
                flightRepository = flightRepository,
                driveClient = driveClient,
                folderResolver = folderResolver,
                keyVault = keyVault,
                fileCipher = fileCipher,
                scratchDir = File(context.cacheDir, "drive-uploads"),
            )

        @Provides
        @Singleton
        fun provideAttachmentFileResolver(
            @ApplicationContext context: Context,
            attachmentRepository: AttachmentRepository,
            driveClient: DriveClient,
            keyVault: KeyVault,
            fileCipher: LocalFileCipher,
        ): AttachmentFileResolver =
            AttachmentFileResolver(
                attachmentRepository = attachmentRepository,
                driveClient = driveClient,
                attachmentsDir = AppFileLayout.attachments(context.filesDir),
                keyVault = keyVault,
                fileCipher = fileCipher,
            )

        @Provides
        @Singleton
        fun provideDriveBackupService(
            @ApplicationContext context: Context,
            linkStore: GoogleLinkStore,
            backupManager: BackupManager,
            driveClient: DriveClient,
            folderResolver: DriveFolderResolver,
            stringsFactory: CalendarEventStringsFactory,
        ): DriveBackupService =
            DefaultDriveBackupService(
                linkStore = linkStore,
                backupManager = backupManager,
                driveClient = driveClient,
                folderResolver = folderResolver,
                backupsDir = AppFileLayout.backups(context.filesDir),
                downloadDir = File(context.cacheDir, "drive-backups"),
                driveFolderName = stringsFactory.driveFolderName(),
            )

        @Provides
        @Singleton
        fun provideFreshInstallDetector(
            tripRepository: TripRepository,
            trainRepository: TrainRepository,
            flightRepository: FlightRepository,
            checklistRepository: ChecklistRepository,
        ): FreshInstallDetector = RepositoryFreshInstallDetector(tripRepository, trainRepository, flightRepository, checklistRepository)
    }
}
