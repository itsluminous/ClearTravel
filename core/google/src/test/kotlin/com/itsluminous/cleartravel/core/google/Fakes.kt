package com.itsluminous.cleartravel.core.google

import android.content.Context
import android.content.Intent
import com.itsluminous.cleartravel.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.cleartravel.core.google.auth.GoogleAuthorizer
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkException
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.auth.ScopeRequestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [GoogleLinkStore] — the DataStore impl is a trivial persistence shell. */
class FakeGoogleLinkStore(
    initial: GoogleLinkSnapshot = GoogleLinkSnapshot(),
) : GoogleLinkStore {
    private val state = MutableStateFlow(initial)

    override val snapshot: Flow<GoogleLinkSnapshot> = state

    override suspend fun current(): GoogleLinkSnapshot = state.value

    override suspend fun setLinked(
        email: String,
        grantedScopes: Set<String>,
    ) {
        state.update {
            val sameAccount = it.email == email
            it.copy(
                email = email,
                grantedScopes = grantedScopes,
                calendarId = it.calendarId.takeIf { _ -> sameAccount },
                driveFolderId = it.driveFolderId.takeIf { _ -> sameAccount },
            )
        }
    }

    override suspend fun addGrantedScopes(scopes: Collection<String>) {
        state.update { it.copy(grantedScopes = it.grantedScopes + scopes) }
    }

    override suspend fun setCalendarId(id: String?) {
        state.update { it.copy(calendarId = id) }
    }

    override suspend fun setDriveFolderId(id: String?) {
        state.update { it.copy(driveFolderId = id) }
    }

    override suspend fun setCalendarSyncEnabled(enabled: Boolean) {
        state.update { it.copy(calendarSyncEnabled = enabled) }
    }

    override suspend fun setDriveUploadsEnabled(enabled: Boolean) {
        state.update { it.copy(driveUploadsEnabled = enabled) }
    }

    override suspend fun setDriveBackupEnabled(enabled: Boolean) {
        state.update { it.copy(driveBackupEnabled = enabled) }
    }

    override suspend fun clear() {
        state.value = GoogleLinkSnapshot()
    }
}

/** Scripted [GoogleAuthorizer] — no Play services, no live APIs. */
class FakeGoogleAuthorizer(
    var pickAccountResult: Result<String> = Result.failure(GoogleLinkException.NotConfigured()),
    var scopeRequestResult: ScopeRequestResult = ScopeRequestResult.Granted(emptyList()),
    var consentResult: Result<List<String>> = Result.success(emptyList()),
    var token: String? = null,
) : GoogleAuthorizer {
    var clearCredentialCalls = 0
        private set
    var lastRequestedScopes: List<String> = emptyList()
        private set

    override suspend fun pickAccount(activityContext: Context): Result<String> = pickAccountResult

    override suspend fun requestScopes(
        activityContext: Context,
        scopes: List<String>,
    ): ScopeRequestResult {
        lastRequestedScopes = scopes
        return scopeRequestResult
    }

    override suspend fun scopesFromConsentResult(resultIntent: Intent?): Result<List<String>> = consentResult

    override suspend fun silentAccessToken(scopes: List<String>): String? = token

    override suspend fun clearCredentialState() {
        clearCredentialCalls++
    }
}

/** Recording [GoogleSyncScheduler] — verifies scheduling without WorkManager. */
class FakeGoogleSyncScheduler : GoogleSyncScheduler {
    val calls = mutableListOf<String>()

    override fun scheduleCalendarSync() {
        calls += "calendar-sync"
    }

    override fun cancelCalendarSync() {
        calls += "calendar-cancel"
    }

    override fun scheduleDriveUploads() {
        calls += "drive-uploads"
    }

    override fun cancelDriveUploads() {
        calls += "drive-cancel"
    }

    override fun scheduleBackupUpload() {
        calls += "backup-upload"
    }

    override fun scheduleDisconnectCleanup(calendarId: String) {
        calls += "cleanup:$calendarId"
    }
}

/** Fixed-token provider for engine tests. */
class FakeAccessTokenProvider(
    var token: String? = "token",
) : GoogleAccessTokenProvider {
    override suspend fun accessToken(): String? = token
}
