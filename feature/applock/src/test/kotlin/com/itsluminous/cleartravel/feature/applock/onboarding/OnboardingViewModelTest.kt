package com.itsluminous.cleartravel.feature.applock.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.backup.BackupException
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.ExportResult
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.data.backup.LocalBackupInfo
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleFeature
import com.itsluminous.cleartravel.core.google.auth.GoogleFeatureSettings
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkException
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.DriveBackupUploadResult
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.applock.FakeSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Link store + authorizer stand-in: linking and the Drive-backup scope are scripted. */
private class FakeGoogleAccountManager(
    configured: Boolean = true,
) : GoogleAccountManager {
    val link = MutableStateFlow<GoogleLinkState>(if (configured) GoogleLinkState.NotLinked else GoogleLinkState.NotConfigured)
    val features = MutableStateFlow(GoogleFeatureSettings())
    var linkResult: Result<GoogleLinkState.Linked> = Result.success(GoogleLinkState.Linked("me@example.com", emptySet()))
    var scopeResult: Result<Unit> = Result.success(Unit)
    val featureCalls = mutableListOf<Pair<GoogleFeature, Boolean>>()
    var consentCompleted = 0

    override val linkState: Flow<GoogleLinkState> = link
    override val featureSettings: Flow<GoogleFeatureSettings> = features

    override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> = linkResult.onSuccess { link.value = it }

    override suspend fun unlink(deleteCalendar: Boolean) {
        link.value = GoogleLinkState.NotLinked
    }

    override suspend fun setFeatureEnabled(
        feature: GoogleFeature,
        enabled: Boolean,
        activityContext: Context?,
    ): Result<Unit> {
        featureCalls += feature to enabled
        return scopeResult.onSuccess { features.update { it.copy(driveBackupEnabled = enabled) } }
    }

    override suspend fun completeScopeConsent(resultIntent: Intent?): Result<Unit> {
        consentCompleted++
        features.update { it.copy(driveBackupEnabled = true) }
        return Result.success(Unit)
    }
}

private class FakeDriveBackupService(
    private val downloadDir: File,
) : DriveBackupService {
    var backups: List<DriveBackupInfo> = emptyList()
    var listCalls = 0
    var downloadFails = false
    val downloaded = mutableListOf<DriveBackupInfo>()

    override suspend fun uploadLatestBackup(): DriveBackupUploadResult = DriveBackupUploadResult.Skipped

    override suspend fun listBackups(): List<DriveBackupInfo> {
        listCalls++
        return backups
    }

    override suspend fun downloadBackup(backup: DriveBackupInfo): File {
        if (downloadFails) throw IOException("offline")
        downloaded += backup
        return File(downloadDir.apply { mkdirs() }, backup.fileName).apply { writeText("x") }
    }
}

/** Import-only [BackupManager] with the ADR-031 password gate scripted per test. */
private class FakeBackupManager : BackupManager {
    /** Non-null: a foreign v2 envelope that only this password opens. */
    var requiredPassword: String? = null
    var applyError: BackupException? = null
    val applied = mutableListOf<Pair<Uri, String?>>()
    val summary = MergeSummary(inserted = 7, updated = 0, skipped = 0)

    override suspend fun exportToUri(uri: Uri): ExportResult = error("not used")

    override suspend fun exportLatestToAppStorage(): ExportResult = error("not used")

    override suspend fun importPreview(
        uri: Uri,
        sourcePassword: CharArray?,
    ): ImportPreview = error("the wizard applies directly")

    override suspend fun importApply(
        uri: Uri,
        sourcePassword: CharArray?,
    ): MergeSummary {
        applyError?.let { throw it }
        applied += uri to sourcePassword?.concatToString()
        val required = requiredPassword
        if (required != null) {
            when {
                sourcePassword == null -> throw BackupException.PasswordRequired()
                sourcePassword.concatToString() != required -> throw BackupException.WrongPassword()
                else -> requiredPassword = null
            }
        }
        return summary
    }

    override suspend fun latestLocalBackup(): LocalBackupInfo? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings = FakeSettingsRepository().apply { onboarding.value = true }
    private val backups = FakeBackupManager()
    private val drive = FakeDriveBackupService(File(context.cacheDir, "drive"))
    private val driveBackup = DriveBackupInfo("id-1", "cleartravel-backup-20260921-1200.zip", Fixtures.NOW, 4_096L)
    private val fileUri: Uri = Uri.parse("content://downloads/cleartravel-backup-old.zip")

    private fun viewModel(google: FakeGoogleAccountManager = FakeGoogleAccountManager()) =
        OnboardingViewModel(google, drive, backups, settings)

    @Test
    fun startsAtGoogleStep_offlineSkipsToRestore_startFreshFinishes() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.GOOGLE)
            assertThat(viewModel.uiState.value.googleConfigured).isTrue()

            viewModel.continueToRestore()
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.NotLinked)
            assertThat(drive.listCalls).isEqualTo(0)

            viewModel.startFresh()
            assertThat(settings.onboarding.value).isFalse()
            assertThat(backups.applied).isEmpty()
        }

    @Test
    fun unconfiguredBuild_showsConnectDisabled_andOfflineStillContinues() =
        runTest {
            val viewModel = viewModel(FakeGoogleAccountManager(configured = false))
            assertThat(viewModel.uiState.value.googleConfigured).isFalse()
            assertThat(viewModel.uiState.value.googleLinked).isFalse()

            viewModel.continueToRestore()
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
        }

    @Test
    fun connectGoogle_linksAndEnablesDriveBackup_thenChecksDrive_none() =
        runTest {
            val google = FakeGoogleAccountManager()
            val viewModel = viewModel(google)

            viewModel.connectGoogle(context)

            assertThat(google.featureCalls).containsExactly(GoogleFeature.DRIVE_BACKUP to true)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.googleLinked).isTrue()
            assertThat(drive.listCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.None)
        }

    @Test
    fun connectGoogle_failure_staysOnStepWithError_cancelHasNoError() =
        runTest {
            val google = FakeGoogleAccountManager()
            google.linkResult = Result.failure(GoogleLinkException.Failed(IOException("no play services")))
            val viewModel = viewModel(google)

            viewModel.connectGoogle(context)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.GOOGLE)
            assertThat(viewModel.uiState.value.error).isEqualTo(OnboardingError.GOOGLE_LINK_FAILED)
            assertThat(viewModel.uiState.value.busy).isFalse()

            google.linkResult = Result.failure(GoogleLinkException.Cancelled())
            viewModel.connectGoogle(context)
            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.GOOGLE)
        }

    @Test
    fun connectGoogle_needsConsent_surfacesIntent_andConsentResultAdvances() =
        runTest {
            val google = FakeGoogleAccountManager()
            val pending = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            google.scopeResult = Result.failure(GoogleLinkException.NeedsScopeConsent(pending))
            val viewModel = viewModel(google)

            viewModel.connectGoogle(context)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.GOOGLE)
            assertThat(viewModel.uiState.value.consentIntent).isSameInstanceAs(pending)

            viewModel.consentLaunched()
            assertThat(viewModel.uiState.value.consentIntent).isNull()
            viewModel.onConsentResult(Intent())
            assertThat(google.consentCompleted).isEqualTo(1)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.None)
        }

    @Test
    fun linkedWithoutDriveScope_explainsNoAccess_andNeverLists() =
        runTest {
            val google = FakeGoogleAccountManager()
            google.scopeResult = Result.failure(GoogleLinkException.Failed(IOException("denied")))
            val viewModel = viewModel(google)

            viewModel.connectGoogle(context)

            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.NoAccess)
            assertThat(drive.listCalls).isEqualTo(0)
        }

    @Test
    fun resumeAfterDeath_alreadyLinked_startsAtGoogleWithContinue() =
        runTest {
            val google = FakeGoogleAccountManager()
            google.link.value = GoogleLinkState.Linked("me@example.com", emptySet())
            google.features.value = GoogleFeatureSettings(driveBackupEnabled = true)
            drive.backups = listOf(driveBackup)

            val viewModel = viewModel(google)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.GOOGLE)
            assertThat(viewModel.uiState.value.googleLinked).isTrue()

            viewModel.continueToRestore()
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.Found(listOf(driveBackup)))
        }

    @Test
    fun restoreFromDrive_downloadsThenAsksForTheBackupPassword() =
        runTest {
            val google = FakeGoogleAccountManager()
            drive.backups = listOf(driveBackup)
            backups.requiredPassword = "old-device-password"
            val viewModel = viewModel(google)
            viewModel.connectGoogle(context)
            assertThat(viewModel.uiState.value.drive).isEqualTo(DriveCheck.Found(listOf(driveBackup)))

            viewModel.restoreFromDrive(driveBackup)

            assertThat(drive.downloaded).containsExactly(driveBackup)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.BACKUP_PASSWORD)
            val source = viewModel.uiState.value.restoreSource
            assertThat(source).isInstanceOf(RestoreSource.Drive::class.java)
            assertThat((source as RestoreSource.Drive).backup).isEqualTo(driveBackup)
            assertThat(settings.onboarding.value).isTrue() // nothing finished yet
        }

    @Test
    fun restoreFromDrive_downloadFailure_isAnInlineErrorOnStep3() =
        runTest {
            val google = FakeGoogleAccountManager()
            drive.backups = listOf(driveBackup)
            drive.downloadFails = true
            val viewModel = viewModel(google)
            viewModel.connectGoogle(context)

            viewModel.restoreFromDrive(driveBackup)

            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.error).isEqualTo(OnboardingError.BACKUP_IO)
            assertThat(viewModel.uiState.value.busy).isFalse()
        }

    @Test
    fun restoreFromFile_foreignPassword_wrongThenRight_importsAndFinishes() =
        runTest {
            backups.requiredPassword = "Old-Install-Pass"
            val viewModel = viewModel()
            viewModel.continueToRestore()

            viewModel.restoreFromFile(fileUri)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.BACKUP_PASSWORD)
            assertThat(viewModel.uiState.value.restoreSource).isEqualTo(RestoreSource.LocalFile(fileUri))
            assertThat(viewModel.uiState.value.wrongBackupPassword).isFalse()

            viewModel.submitBackupPassword("New-Install-Pass")
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.BACKUP_PASSWORD)
            assertThat(viewModel.uiState.value.wrongBackupPassword).isTrue()
            assertThat(settings.onboarding.value).isTrue()

            viewModel.clearBackupPasswordError()
            assertThat(viewModel.uiState.value.wrongBackupPassword).isFalse()

            viewModel.submitBackupPassword("Old-Install-Pass")
            assertThat(backups.applied.map { it.second }).containsExactly(null, "New-Install-Pass", "Old-Install-Pass").inOrder()
            assertThat(viewModel.uiState.value.restored).isEqualTo(backups.summary)
            assertThat(settings.onboarding.value).isFalse()
        }

    @Test
    fun restoreFromFile_plainOrOwnBackup_needsNoPassword_finishesDirectly() =
        runTest {
            val viewModel = viewModel()
            viewModel.continueToRestore()

            viewModel.restoreFromFile(fileUri)

            assertThat(backups.applied).containsExactly(fileUri to null)
            assertThat(viewModel.uiState.value.restored).isEqualTo(backups.summary)
            assertThat(settings.onboarding.value).isFalse()
        }

    @Test
    fun restoreFromFile_unreadableOrNewer_isAnInlineErrorOnStep3() =
        runTest {
            val viewModel = viewModel()
            viewModel.continueToRestore()

            backups.applyError = BackupException.CorruptedBackup()
            viewModel.restoreFromFile(fileUri)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.error).isEqualTo(OnboardingError.BACKUP_UNREADABLE)

            backups.applyError = BackupException.UnsupportedSchemaVersion(found = 9)
            viewModel.restoreFromFile(fileUri)
            assertThat(viewModel.uiState.value.error).isEqualTo(OnboardingError.BACKUP_VERSION_TOO_NEW)
            assertThat(settings.onboarding.value).isTrue()
        }

    @Test
    fun cancelFromBackupPassword_returnsToRestore_forgetsTheSource() =
        runTest {
            backups.requiredPassword = "x-y-z-1234"
            val viewModel = viewModel()
            viewModel.continueToRestore()
            viewModel.restoreFromFile(fileUri)
            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.BACKUP_PASSWORD)

            viewModel.cancelRestore()

            assertThat(viewModel.uiState.value.step).isEqualTo(OnboardingStep.RESTORE)
            assertThat(viewModel.uiState.value.restoreSource).isNull()
            viewModel.submitBackupPassword("x-y-z-1234") // no source → ignored
            assertThat(backups.applied).hasSize(1)
        }
}
