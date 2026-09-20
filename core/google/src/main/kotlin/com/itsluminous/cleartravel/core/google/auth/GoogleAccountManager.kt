package com.itsluminous.cleartravel.core.google.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.itsluminous.cleartravel.core.google.GoogleScopes
import com.itsluminous.cleartravel.core.google.GoogleServicesConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Current state of the Google account link. */
sealed interface GoogleLinkState {
    /** No web client id configured — render the explanatory disabled state, never crash. */
    data object NotConfigured : GoogleLinkState

    /** Configured but no account linked yet. */
    data object NotLinked : GoogleLinkState

    /** An account is linked; [email] is shown in Settings. */
    data class Linked(
        val email: String,
        val grantedScopes: Set<String>,
    ) : GoogleLinkState
}

/** Failure modes the Settings UI must handle. */
sealed class GoogleLinkException(
    message: String,
) : Exception(message) {
    /** The web client id is blank — Google features are off (docs/google-setup.md). */
    class NotConfigured : GoogleLinkException("google web client id is not configured")

    /** No account linked — link first. */
    class NotLinked : GoogleLinkException("no google account linked")

    /** The user must approve the incremental scope; launch [pendingIntent] then call [GoogleAccountManager.completeScopeConsent]. */
    class NeedsScopeConsent(
        val pendingIntent: PendingIntent,
    ) : GoogleLinkException("user consent required for requested scopes")

    /** User dismissed the account picker / consent sheet. */
    class Cancelled : GoogleLinkException("user cancelled the flow")

    class Failed(
        cause: Throwable,
    ) : GoogleLinkException(cause.message ?: "google operation failed") {
        init {
            initCause(cause)
        }
    }
}

/** The three Google feature toggles, as shown in Settings. */
data class GoogleFeatureSettings(
    val calendarSyncEnabled: Boolean = false,
    val driveUploadsEnabled: Boolean = false,
    val driveBackupEnabled: Boolean = false,
)

/** Which feature a toggle/scope request belongs to. */
enum class GoogleFeature {
    CALENDAR_SYNC,
    DRIVE_UPLOADS,
    DRIVE_BACKUP,
}

/**
 * Facade of the whole Google integration (spec feature 5): account linking via
 * Credential Manager, INCREMENTAL scope requests (a feature's scope is requested only
 * when its toggle is turned on), the three feature toggles, and disconnect. All state
 * is device-local ([GoogleLinkStore]); the app stays fully functional signed out.
 */
interface GoogleAccountManager {
    val linkState: Flow<GoogleLinkState>

    val featureSettings: Flow<GoogleFeatureSettings>

    /**
     * Runs the account-picker flow. [activityContext] MUST be an Activity context.
     * Linking alone grants NO scopes — each toggle requests its own incrementally.
     */
    suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked>

    /**
     * Disconnects: clears the stored link, scopes, cached ids and toggles. With
     * [deleteCalendar] the app-created "ClearTravel" calendar (and its events) is
     * removed best-effort in a background worker before local state is cleared.
     */
    suspend fun unlink(deleteCalendar: Boolean)

    /**
     * Turns [feature] on/off. Enabling requests the feature's missing scope first
     * (incremental consent); may fail with [GoogleLinkException.NeedsScopeConsent] —
     * launch its intent sender and pass the activity result to
     * [completeScopeConsent]. Enabling also schedules the feature's first sync pass.
     */
    suspend fun setFeatureEnabled(
        feature: GoogleFeature,
        enabled: Boolean,
        activityContext: Context?,
    ): Result<Unit>

    /** Completes a pending [setFeatureEnabled] after the consent UI returned. */
    suspend fun completeScopeConsent(resultIntent: Intent?): Result<Unit>
}

/**
 * WorkManager scheduling seam used by [DefaultGoogleAccountManager] — an interface so
 * manager tests never touch WorkManager. Implemented by [com.itsluminous.cleartravel.core.google.work.WorkManagerGoogleSyncScheduler].
 */
interface GoogleSyncScheduler {
    /** One-shot calendar reconciliation + the periodic catch-up. */
    fun scheduleCalendarSync()

    fun cancelCalendarSync()

    /** One-shot Drive upload-queue drain + the periodic catch-up. */
    fun scheduleDriveUploads()

    fun cancelDriveUploads()

    /** One-shot upload of the newest app-storage backup to Drive. */
    fun scheduleBackupUpload()

    /** Best-effort deletion of the app-created calendar on disconnect. */
    fun scheduleDisconnectCleanup(calendarId: String)
}

/**
 * [GoogleAccountManager] over [GoogleAuthorizer] + [GoogleLinkStore]. The linking
 * state machine (NotConfigured → NotLinked → Linked) and the scope gating logic are
 * unit-tested against fake authorizer/store/scheduler — never live APIs. Constructed
 * via a `@Provides` (not constructor injection) so [configured] can be pinned in tests.
 */
@Singleton
class DefaultGoogleAccountManager(
    private val authorizer: GoogleAuthorizer,
    private val linkStore: GoogleLinkStore,
    private val scheduler: GoogleSyncScheduler,
    private val configured: Boolean = GoogleServicesConfig.isConfigured,
) : GoogleAccountManager {
    /** A toggle waiting for the user's consent-sheet result. */
    private var pendingConsent: GoogleFeature? = null

    override val linkState: Flow<GoogleLinkState> =
        linkStore.snapshot.map { snapshot ->
            when {
                !configured -> GoogleLinkState.NotConfigured
                snapshot.email == null -> GoogleLinkState.NotLinked
                else -> GoogleLinkState.Linked(snapshot.email, snapshot.grantedScopes)
            }
        }

    override val featureSettings: Flow<GoogleFeatureSettings> =
        linkStore.snapshot.map { snapshot ->
            GoogleFeatureSettings(
                calendarSyncEnabled = snapshot.calendarSyncEnabled,
                driveUploadsEnabled = snapshot.driveUploadsEnabled,
                driveBackupEnabled = snapshot.driveBackupEnabled,
            )
        }

    override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> {
        if (!configured) return Result.failure(GoogleLinkException.NotConfigured())
        val email = authorizer.pickAccount(activityContext).getOrElse { return Result.failure(it) }
        val previousScopes =
            linkStore
                .current()
                .takeIf { it.email == email }
                ?.grantedScopes
                .orEmpty()
        linkStore.setLinked(email = email, grantedScopes = previousScopes)
        return Result.success(GoogleLinkState.Linked(email, previousScopes))
    }

    override suspend fun unlink(deleteCalendar: Boolean) {
        val snapshot = linkStore.current()
        if (deleteCalendar) {
            snapshot.calendarId?.let(scheduler::scheduleDisconnectCleanup)
        }
        scheduler.cancelCalendarSync()
        scheduler.cancelDriveUploads()
        linkStore.clear()
        authorizer.clearCredentialState()
    }

    override suspend fun setFeatureEnabled(
        feature: GoogleFeature,
        enabled: Boolean,
        activityContext: Context?,
    ): Result<Unit> {
        if (!enabled) {
            persistToggle(feature, enabled = false)
            when (feature) {
                GoogleFeature.CALENDAR_SYNC -> scheduler.cancelCalendarSync()
                GoogleFeature.DRIVE_UPLOADS -> scheduler.cancelDriveUploads()
                GoogleFeature.DRIVE_BACKUP -> Unit
            }
            return Result.success(Unit)
        }
        if (!configured) return Result.failure(GoogleLinkException.NotConfigured())
        val snapshot = linkStore.current()
        if (!snapshot.isLinked) return Result.failure(GoogleLinkException.NotLinked())

        val missing = GoogleScopes.missing(snapshot.grantedScopes, scopesFor(feature))
        if (missing.isEmpty()) {
            enableFeature(feature)
            return Result.success(Unit)
        }
        if (activityContext == null) return Result.failure(GoogleLinkException.NotLinked())
        return when (val result = authorizer.requestScopes(activityContext, (snapshot.grantedScopes + missing).toList())) {
            is ScopeRequestResult.Granted -> {
                linkStore.addGrantedScopes(result.grantedScopes.ifEmpty { missing })
                enableFeature(feature)
                Result.success(Unit)
            }
            is ScopeRequestResult.NeedsConsent -> {
                pendingConsent = feature
                Result.failure(GoogleLinkException.NeedsScopeConsent(result.pendingIntent))
            }
            ScopeRequestResult.Cancelled -> Result.failure(GoogleLinkException.Cancelled())
            is ScopeRequestResult.Failed -> Result.failure(GoogleLinkException.Failed(result.cause))
        }
    }

    override suspend fun completeScopeConsent(resultIntent: Intent?): Result<Unit> {
        val feature = pendingConsent ?: return Result.failure(GoogleLinkException.Cancelled())
        pendingConsent = null
        val scopes = authorizer.scopesFromConsentResult(resultIntent).getOrElse { return Result.failure(it) }
        linkStore.addGrantedScopes(scopes.ifEmpty { scopesFor(feature) })
        enableFeature(feature)
        return Result.success(Unit)
    }

    private suspend fun enableFeature(feature: GoogleFeature) {
        persistToggle(feature, enabled = true)
        when (feature) {
            GoogleFeature.CALENDAR_SYNC -> scheduler.scheduleCalendarSync()
            GoogleFeature.DRIVE_UPLOADS -> scheduler.scheduleDriveUploads()
            GoogleFeature.DRIVE_BACKUP -> scheduler.scheduleBackupUpload()
        }
    }

    private suspend fun persistToggle(
        feature: GoogleFeature,
        enabled: Boolean,
    ) {
        when (feature) {
            GoogleFeature.CALENDAR_SYNC -> linkStore.setCalendarSyncEnabled(enabled)
            GoogleFeature.DRIVE_UPLOADS -> linkStore.setDriveUploadsEnabled(enabled)
            GoogleFeature.DRIVE_BACKUP -> linkStore.setDriveBackupEnabled(enabled)
        }
    }

    private fun scopesFor(feature: GoogleFeature): List<String> =
        when (feature) {
            GoogleFeature.CALENDAR_SYNC -> GoogleScopes.requiredFor(calendarSync = true, driveUploads = false, driveBackup = false)
            GoogleFeature.DRIVE_UPLOADS -> GoogleScopes.requiredFor(calendarSync = false, driveUploads = true, driveBackup = false)
            GoogleFeature.DRIVE_BACKUP -> GoogleScopes.requiredFor(calendarSync = false, driveUploads = false, driveBackup = true)
        }
}

/**
 * Supplies short-lived OAuth access tokens for the Calendar/Drive REST calls made by
 * background workers: a silent authorization for the ALREADY-granted scopes. Returns
 * null when not configured, not linked, or the grant needs (re-)consent — workers
 * treat null as "retry later", never as an error to surface.
 */
interface GoogleAccessTokenProvider {
    suspend fun accessToken(): String?
}

/** [GoogleAccessTokenProvider] from the persisted grant + a silent authorization. */
@Singleton
class LinkedAccountAccessTokenProvider
    @Inject
    constructor(
        private val authorizer: GoogleAuthorizer,
        private val linkStore: GoogleLinkStore,
    ) : GoogleAccessTokenProvider {
        override suspend fun accessToken(): String? {
            val snapshot = linkStore.current()
            if (!snapshot.isLinked || snapshot.grantedScopes.isEmpty()) return null
            return authorizer.silentAccessToken(snapshot.grantedScopes.toList())
        }
    }

/** Signals a Google call could not run because no token/link is available right now. */
class GoogleNotAvailableException(
    message: String = "google account not available",
) : Exception(message)
