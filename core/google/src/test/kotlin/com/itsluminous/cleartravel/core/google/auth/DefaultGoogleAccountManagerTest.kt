package com.itsluminous.cleartravel.core.google.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.FakeGoogleAuthorizer
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.FakeGoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.GoogleScopes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Linking state machine + incremental scope gating (all against fakes — spec:
 * "tested against fake Google clients, never live APIs").
 */
@RunWith(RobolectricTestRunner::class)
class DefaultGoogleAccountManagerTest {
    private lateinit var context: Context
    private lateinit var authorizer: FakeGoogleAuthorizer
    private lateinit var store: FakeGoogleLinkStore
    private lateinit var scheduler: FakeGoogleSyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authorizer = FakeGoogleAuthorizer()
        store = FakeGoogleLinkStore()
        scheduler = FakeGoogleSyncScheduler()
    }

    private fun manager(configured: Boolean = true) = DefaultGoogleAccountManager(authorizer, store, scheduler, configured)

    @Test
    fun `no client id yields NotConfigured and link fails typed`() =
        runTest {
            val manager = manager(configured = false)
            assertThat(manager.linkState.first()).isEqualTo(GoogleLinkState.NotConfigured)

            val result = manager.link(context)
            assertThat(result.exceptionOrNull()).isInstanceOf(GoogleLinkException.NotConfigured::class.java)
        }

    @Test
    fun `configured but unlinked yields NotLinked`() =
        runTest {
            assertThat(manager().linkState.first()).isEqualTo(GoogleLinkState.NotLinked)
        }

    @Test
    fun `link persists email with no scopes (incremental consent)`() =
        runTest {
            authorizer.pickAccountResult = Result.success("traveler@example.com")

            val result = manager().link(context)

            assertThat(result.getOrNull()).isEqualTo(GoogleLinkState.Linked("traveler@example.com", emptySet()))
            assertThat(store.current().email).isEqualTo("traveler@example.com")
            assertThat(store.current().grantedScopes).isEmpty()
        }

    @Test
    fun `link cancelled propagates Cancelled`() =
        runTest {
            authorizer.pickAccountResult = Result.failure(GoogleLinkException.Cancelled())

            val result = manager().link(context)

            assertThat(result.exceptionOrNull()).isInstanceOf(GoogleLinkException.Cancelled::class.java)
            assertThat(store.current().email).isNull()
        }

    @Test
    fun `enabling a feature while unlinked fails typed`() =
        runTest {
            val result = manager().setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, enabled = true, activityContext = context)
            assertThat(result.exceptionOrNull()).isInstanceOf(GoogleLinkException.NotLinked::class.java)
        }

    @Test
    fun `enabling calendar sync requests only the calendar scope and schedules sync`() =
        runTest {
            link()
            authorizer.scopeRequestResult = ScopeRequestResult.Granted(listOf(GoogleScopes.CALENDAR_APP_CREATED))

            val result = manager().setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, enabled = true, activityContext = context)

            assertThat(result.isSuccess).isTrue()
            assertThat(authorizer.lastRequestedScopes).containsExactly(GoogleScopes.CALENDAR_APP_CREATED)
            assertThat(store.current().calendarSyncEnabled).isTrue()
            assertThat(store.current().grantedScopes).contains(GoogleScopes.CALENDAR_APP_CREATED)
            assertThat(scheduler.calls).contains("calendar-sync")
        }

    @Test
    fun `enabling drive uploads requests the drive scope`() =
        runTest {
            link()
            authorizer.scopeRequestResult = ScopeRequestResult.Granted(listOf(GoogleScopes.DRIVE_FILE))

            manager().setFeatureEnabled(GoogleFeature.DRIVE_UPLOADS, enabled = true, activityContext = context)

            assertThat(authorizer.lastRequestedScopes).containsExactly(GoogleScopes.DRIVE_FILE)
            assertThat(store.current().driveUploadsEnabled).isTrue()
            assertThat(scheduler.calls).contains("drive-uploads")
        }

    @Test
    fun `already granted scope skips the authorization round-trip`() =
        runTest {
            link(scopes = setOf(GoogleScopes.DRIVE_FILE))

            val result = manager().setFeatureEnabled(GoogleFeature.DRIVE_BACKUP, enabled = true, activityContext = null)

            assertThat(result.isSuccess).isTrue()
            assertThat(authorizer.lastRequestedScopes).isEmpty()
            assertThat(store.current().driveBackupEnabled).isTrue()
            assertThat(scheduler.calls).contains("backup-upload")
        }

    @Test
    fun `consent resolution round-trip enables the pending feature`() =
        runTest {
            link()
            val pendingIntent =
                PendingIntent.getActivity(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
            authorizer.scopeRequestResult = ScopeRequestResult.NeedsConsent(pendingIntent)
            val manager = manager()

            val first = manager.setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, enabled = true, activityContext = context)
            assertThat(first.exceptionOrNull()).isInstanceOf(GoogleLinkException.NeedsScopeConsent::class.java)
            assertThat(store.current().calendarSyncEnabled).isFalse()

            authorizer.consentResult = Result.success(listOf(GoogleScopes.CALENDAR_APP_CREATED))
            val second = manager.completeScopeConsent(Intent())

            assertThat(second.isSuccess).isTrue()
            assertThat(store.current().calendarSyncEnabled).isTrue()
            assertThat(store.current().grantedScopes).contains(GoogleScopes.CALENDAR_APP_CREATED)
        }

    @Test
    fun `disabling a feature never needs consent and cancels its work`() =
        runTest {
            link(scopes = setOf(GoogleScopes.CALENDAR_APP_CREATED))
            store.setCalendarSyncEnabled(true)

            val result = manager().setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, enabled = false, activityContext = null)

            assertThat(result.isSuccess).isTrue()
            assertThat(store.current().calendarSyncEnabled).isFalse()
            assertThat(scheduler.calls).contains("calendar-cancel")
        }

    @Test
    fun `unlink clears state and optionally schedules calendar cleanup`() =
        runTest {
            link(scopes = setOf(GoogleScopes.CALENDAR_APP_CREATED))
            store.setCalendarId("cal-1")

            manager().unlink(deleteCalendar = true)

            assertThat(store.current()).isEqualTo(GoogleLinkSnapshot())
            assertThat(authorizer.clearCredentialCalls).isEqualTo(1)
            assertThat(scheduler.calls).contains("cleanup:cal-1")
        }

    @Test
    fun `unlink without delete keeps the calendar`() =
        runTest {
            link()
            store.setCalendarId("cal-1")

            manager().unlink(deleteCalendar = false)

            assertThat(scheduler.calls.none { it.startsWith("cleanup") }).isTrue()
            assertThat(store.current().email).isNull()
        }

    @Test
    fun `relink of the same account keeps previously granted scopes`() =
        runTest {
            link(scopes = setOf(GoogleScopes.DRIVE_FILE))
            authorizer.pickAccountResult = Result.success("traveler@example.com")

            val result = manager().link(context)

            assertThat(result.getOrNull()?.grantedScopes).contains(GoogleScopes.DRIVE_FILE)
        }

    private suspend fun link(scopes: Set<String> = emptySet()) {
        store.setLinked("traveler@example.com", scopes)
    }
}

/** Pure scope-computation table (spec: incremental scopes per toggle). */
class GoogleScopesTest {
    @Test
    fun `calendar toggle needs only the app-created calendar scope`() {
        assertThat(GoogleScopes.requiredFor(calendarSync = true, driveUploads = false, driveBackup = false))
            .containsExactly(GoogleScopes.CALENDAR_APP_CREATED)
    }

    @Test
    fun `drive uploads and drive backup share the drive-file scope`() {
        assertThat(GoogleScopes.requiredFor(calendarSync = false, driveUploads = true, driveBackup = false))
            .containsExactly(GoogleScopes.DRIVE_FILE)
        assertThat(GoogleScopes.requiredFor(calendarSync = false, driveUploads = false, driveBackup = true))
            .containsExactly(GoogleScopes.DRIVE_FILE)
        assertThat(GoogleScopes.requiredFor(calendarSync = false, driveUploads = true, driveBackup = true))
            .containsExactly(GoogleScopes.DRIVE_FILE)
    }

    @Test
    fun `missing subtracts already granted scopes`() {
        assertThat(
            GoogleScopes.missing(
                granted = setOf(GoogleScopes.DRIVE_FILE),
                required = listOf(GoogleScopes.DRIVE_FILE, GoogleScopes.CALENDAR_APP_CREATED),
            ),
        ).containsExactly(GoogleScopes.CALENDAR_APP_CREATED)
    }
}
