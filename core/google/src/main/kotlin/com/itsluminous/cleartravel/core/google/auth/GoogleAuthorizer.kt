package com.itsluminous.cleartravel.core.google.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.itsluminous.cleartravel.core.google.GoogleServicesConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an incremental scope request. */
sealed interface ScopeRequestResult {
    /** The scopes are granted (silently or after prior consent). */
    data class Granted(
        val grantedScopes: List<String>,
    ) : ScopeRequestResult

    /** The user must approve via [pendingIntent]; pass the activity result to the caller's complete step. */
    data class NeedsConsent(
        val pendingIntent: PendingIntent,
    ) : ScopeRequestResult

    /** The user cancelled the consent sheet / account picker. */
    data object Cancelled : ScopeRequestResult

    data class Failed(
        val cause: Throwable,
    ) : ScopeRequestResult
}

/**
 * Thin seam over Credential Manager (account pick) and the Play services
 * [Identity] authorization client (incremental scopes + silent access tokens).
 * All Google-Play-services machinery lives behind this interface so
 * [DefaultGoogleAccountManager] and every engine are testable with fakes —
 * live APIs are never touched by tests.
 */
interface GoogleAuthorizer {
    /**
     * Shows the Credential Manager account picker and returns the chosen account's
     * email. [activityContext] MUST be an Activity context (UI is shown).
     */
    suspend fun pickAccount(activityContext: Context): Result<String>

    /** Requests [scopes] via the authorization client (may need user consent). */
    suspend fun requestScopes(
        activityContext: Context,
        scopes: List<String>,
    ): ScopeRequestResult

    /** Extracts the granted scopes from a consent-sheet activity result. */
    suspend fun scopesFromConsentResult(resultIntent: Intent?): Result<List<String>>

    /**
     * Silently authorizes the already-granted [scopes] and returns a fresh access
     * token, or null when unavailable (not configured, consent revoked, offline) —
     * callers treat null as "retry later", never as an error to surface.
     */
    suspend fun silentAccessToken(scopes: List<String>): String?

    /** Clears Credential Manager state on disconnect. */
    suspend fun clearCredentialState()
}

/**
 * Real [GoogleAuthorizer]. The most common release-only failure: the Cloud console
 * clients don't match this build (an ANDROID client id supplied as the web/"server"
 * client id, or a missing Android client for the signing cert) — Credential Manager
 * then fails with `[28444]` "Developer console is not set up correctly". Logged so
 * Settings failures are diagnosable; setup steps live in docs/google-setup.md.
 */
@Singleton
class PlayServicesGoogleAuthorizer
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) : GoogleAuthorizer {
        override suspend fun pickAccount(activityContext: Context): Result<String> {
            if (!GoogleServicesConfig.isConfigured) {
                return Result.failure(GoogleLinkException.NotConfigured())
            }
            return try {
                val option =
                    GetGoogleIdOption
                        .Builder()
                        .setServerClientId(GoogleServicesConfig.webClientId)
                        .setFilterByAuthorizedAccounts(false)
                        .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
                val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
                Result.success(credential.id)
            } catch (e: GetCredentialCancellationException) {
                Result.failure(GoogleLinkException.Cancelled())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "google account pick failed", e)
                Result.failure(GoogleLinkException.Failed(e))
            }
        }

        override suspend fun requestScopes(
            activityContext: Context,
            scopes: List<String>,
        ): ScopeRequestResult =
            try {
                val request =
                    AuthorizationRequest
                        .builder()
                        .setRequestedScopes(scopes.map(::Scope))
                        .build()
                val result = Identity.getAuthorizationClient(activityContext).authorize(request).await()
                val resolution = result.pendingIntent
                if (result.hasResolution() && resolution != null) {
                    ScopeRequestResult.NeedsConsent(resolution)
                } else {
                    ScopeRequestResult.Granted(result.grantedScopes.orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Typical cause: the OAuth consent screen (Data Access) lacks the scope.
                Log.w(TAG, "scope authorization failed", e)
                ScopeRequestResult.Failed(e)
            }

        override suspend fun scopesFromConsentResult(resultIntent: Intent?): Result<List<String>> =
            try {
                val result = Identity.getAuthorizationClient(appContext).getAuthorizationResultFromIntent(resultIntent)
                Result.success(result.grantedScopes.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(GoogleLinkException.Failed(e))
            }

        override suspend fun silentAccessToken(scopes: List<String>): String? {
            if (!GoogleServicesConfig.isConfigured || scopes.isEmpty()) return null
            return try {
                val request =
                    AuthorizationRequest
                        .builder()
                        .setRequestedScopes(scopes.map(::Scope))
                        .build()
                val result = Identity.getAuthorizationClient(appContext).authorize(request).await()
                if (result.hasResolution()) null else result.accessToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        override suspend fun clearCredentialState() {
            runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        }

        private companion object {
            const val TAG = "ClearTravelGoogle"
        }
    }
