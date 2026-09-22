package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.core.security.vault.VaultState
import com.itsluminous.cleartravel.di.TestSecurityModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.applock.R as AppLockR

/**
 * ADR-031/032 first run: with NO vault set up, the shell shows wizard step 1 (password
 * + confirmation + data-loss warning + fingerprint toggle) instead of the tabs; a
 * too-short pair and a mismatched confirmation are refused in place; a valid pair
 * moves to step 2 (Google — the connect button is disabled in this unconfigured
 * build), "Use offline" to step 3, "Start fresh" opens the app (Trips tab visible)
 * with the vault unlocked and the onboarding flag cleared. The suite's
 * [TestSecurityModule] is switched to its fresh-install mode for this class.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLockSetupE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var keyVault: KeyVault

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun freshInstall_wizard_password_offline_startFresh_opensTheApp() {
        // ---- Step 1: password ----
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_onboarding_step, 1, 4)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_setup_title)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_setup_warning_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_setup_biometric_title)).performScrollTo().assertIsDisplayed()
        // The tabs are NOT there yet.
        composeRule.onAllNodesWithText(composeRule.string(R.string.nav_trips)).fetchSemanticsNodes().let { check(it.isEmpty()) }

        val passwordField =
            composeRule.onNode(
                hasSetTextAction() and hasText(composeRule.string(AppLockR.string.applock_setup_password_label)),
            )
        val confirmField =
            composeRule.onNode(
                hasSetTextAction() and hasText(composeRule.string(AppLockR.string.applock_setup_confirm_label)),
            )
        val create = composeRule.string(AppLockR.string.applock_setup_action)

        passwordField.performScrollTo().performTextInput("short")
        confirmField.performScrollTo().performTextInput("short")
        composeRule.onNodeWithText(create).performScrollTo().performClick()
        composeRule.waitForText(composeRule.string(AppLockR.string.applock_error_too_short, 8))
        check(keyVault.state.value == VaultState.NotSetUp)

        passwordField.performScrollTo().performTextClearance()
        passwordField.performTextInput("e2e-password-one")
        confirmField.performScrollTo().performTextClearance()
        confirmField.performTextInput("e2e-password-two")
        composeRule.onNodeWithText(create).performScrollTo().performClick()
        composeRule.waitForText(composeRule.string(AppLockR.string.applock_error_mismatch))
        check(keyVault.state.value == VaultState.NotSetUp)

        confirmField.performScrollTo().performTextClearance()
        confirmField.performTextInput("e2e-password-one")
        composeRule.onNodeWithText(create).performScrollTo().performClick()

        // ---- Step 2: Google — the Connect button follows the BUILD's client id (an
        // unconfigured build disables it with an explanation; a build with
        // GOOGLE_WEB_CLIENT_ID in local.properties enables it), "Use offline" continues
        // either way. Asserting only the unconfigured branch made this test depend on
        // the developer's local.properties.
        composeRule.waitForText(composeRule.string(AppLockR.string.applock_onboarding_google_title))
        check(keyVault.state.value is VaultState.Unlocked)
        check(runBlocking { settingsRepository.onboardingPending.first() })
        val connect = composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_onboarding_google_connect))
        val notConfigured = composeRule.string(AppLockR.string.applock_onboarding_google_not_configured)
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            connect.assertIsNotEnabled()
            composeRule.onNodeWithText(notConfigured).assertIsDisplayed()
        } else {
            connect.assertIsEnabled()
            composeRule.onAllNodesWithText(notConfigured).assertCountEquals(0)
        }
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_onboarding_google_offline)).performClick()

        // ---- Step 3: restore or start fresh (no Drive card when offline) ----
        composeRule.waitForText(composeRule.string(AppLockR.string.applock_onboarding_restore_title))
        composeRule
            .onNodeWithText(
                composeRule.string(AppLockR.string.applock_onboarding_restore_file_action),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText(composeRule.string(AppLockR.string.applock_onboarding_restore_drive_title))
            .fetchSemanticsNodes()
            .let { check(it.isEmpty()) }
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_onboarding_start_fresh)).performScrollTo().performClick()

        // ---- Step 5: the app ----
        composeRule.waitForText(composeRule.string(R.string.nav_trips))
        check(keyVault.state.value is VaultState.Unlocked)
        check(!runBlocking { settingsRepository.onboardingPending.first() })
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun freshInstall() {
            E2e.grantNotificationPermission()
            TestSecurityModule.freshInstall = true
        }

        @JvmStatic
        @AfterClass
        fun restore() {
            TestSecurityModule.freshInstall = false
        }
    }
}
