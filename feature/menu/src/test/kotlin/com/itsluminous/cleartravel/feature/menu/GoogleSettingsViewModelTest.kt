package com.itsluminous.cleartravel.feature.menu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleFeature
import com.itsluminous.cleartravel.core.google.auth.GoogleFeatureSettings
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkException
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scripted [GoogleAccountManager] — the ViewModel is tested without any Google code. */
class FakeGoogleAccountManager(
    initialState: GoogleLinkState = GoogleLinkState.NotLinked,
) : GoogleAccountManager {
    val state = MutableStateFlow(initialState)
    val settings = MutableStateFlow(GoogleFeatureSettings())
    var linkResult: Result<GoogleLinkState.Linked> = Result.failure(GoogleLinkException.NotConfigured())
    var featureResult: Result<Unit> = Result.success(Unit)
    var consentResult: Result<Unit> = Result.success(Unit)
    val featureCalls = mutableListOf<Pair<GoogleFeature, Boolean>>()
    var unlinkedWithDelete: Boolean? = null

    override val linkState = state

    override val featureSettings = settings

    override suspend fun link(activityContext: Context): Result<GoogleLinkState.Linked> {
        linkResult.getOrNull()?.let { state.value = it }
        return linkResult
    }

    override suspend fun unlink(deleteCalendar: Boolean) {
        unlinkedWithDelete = deleteCalendar
        state.value = GoogleLinkState.NotLinked
        settings.value = GoogleFeatureSettings()
    }

    override suspend fun setFeatureEnabled(
        feature: GoogleFeature,
        enabled: Boolean,
        activityContext: Context?,
    ): Result<Unit> {
        featureCalls += feature to enabled
        if (featureResult.isSuccess) {
            settings.value =
                when (feature) {
                    GoogleFeature.CALENDAR_SYNC -> settings.value.copy(calendarSyncEnabled = enabled)
                    GoogleFeature.DRIVE_UPLOADS -> settings.value.copy(driveUploadsEnabled = enabled)
                    GoogleFeature.DRIVE_BACKUP -> settings.value.copy(driveBackupEnabled = enabled)
                }
        }
        return featureResult
    }

    override suspend fun completeScopeConsent(resultIntent: Intent?): Result<Unit> = consentResult
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val manager = FakeGoogleAccountManager()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun viewModel() = GoogleSettingsViewModel(manager)

    @Test
    fun `not-configured state renders the disabled explanation`() =
        runTest {
            manager.state.value = GoogleLinkState.NotConfigured

            assertThat(viewModel().linkState.value).isEqualTo(GoogleLinkState.NotConfigured)
        }

    @Test
    fun `successful link emits Linked with the account email`() =
        runTest {
            manager.linkResult = Result.success(GoogleLinkState.Linked("traveler@example.com", emptySet()))
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.link(context)

                assertThat(awaitItem()).isEqualTo(GoogleSettingsEvent.Linked("traveler@example.com"))
            }
            assertThat(viewModel.linkState.value)
                .isEqualTo(GoogleLinkState.Linked("traveler@example.com", emptySet()))
        }

    @Test
    fun `failed link emits LinkFailed`() =
        runTest {
            manager.linkResult = Result.failure(GoogleLinkException.Failed(IllegalStateException("28444")))
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.link(context)

                assertThat(awaitItem()).isEqualTo(GoogleSettingsEvent.LinkFailed)
            }
        }

    @Test
    fun `cancelled link emits nothing`() =
        runTest {
            manager.linkResult = Result.failure(GoogleLinkException.Cancelled())
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.link(context)

                expectNoEvents()
            }
        }

    @Test
    fun `toggling a feature delegates with its flag`() =
        runTest {
            val viewModel = viewModel()

            viewModel.setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, enabled = true, activityContext = context)

            assertThat(manager.featureCalls).containsExactly(GoogleFeature.CALENDAR_SYNC to true)
            assertThat(viewModel.featureSettings.value.calendarSyncEnabled).isTrue()
        }

    @Test
    fun `NeedsScopeConsent surfaces the pending intent for the launcher`() =
        runTest {
            val pendingIntent = PendingIntent.getActivity(context, 0, Intent("consent"), PendingIntent.FLAG_IMMUTABLE)
            manager.featureResult = Result.failure(GoogleLinkException.NeedsScopeConsent(pendingIntent))
            val viewModel = viewModel()

            viewModel.setFeatureEnabled(GoogleFeature.DRIVE_UPLOADS, enabled = true, activityContext = context)

            assertThat(viewModel.uiState.value.consentIntent).isEqualTo(pendingIntent)

            viewModel.consentLaunched()
            assertThat(viewModel.uiState.value.consentIntent).isNull()
        }

    @Test
    fun `consent result failure emits ScopeDenied`() =
        runTest {
            manager.consentResult = Result.failure(GoogleLinkException.Failed(IllegalStateException("denied")))
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onConsentResult(null)

                assertThat(awaitItem()).isEqualTo(GoogleSettingsEvent.ScopeDenied)
            }
        }

    @Test
    fun `disconnect confirm delegates the delete-calendar choice and emits Disconnected`() =
        runTest {
            manager.state.value = GoogleLinkState.Linked("traveler@example.com", emptySet())
            val viewModel = viewModel()
            viewModel.requestDisconnect()
            assertThat(viewModel.uiState.value.showDisconnectDialog).isTrue()

            viewModel.events.test {
                viewModel.confirmDisconnect(deleteCalendar = true)

                assertThat(awaitItem()).isEqualTo(GoogleSettingsEvent.Disconnected)
            }
            assertThat(manager.unlinkedWithDelete).isTrue()
            assertThat(viewModel.linkState.value).isEqualTo(GoogleLinkState.NotLinked)
        }
}
