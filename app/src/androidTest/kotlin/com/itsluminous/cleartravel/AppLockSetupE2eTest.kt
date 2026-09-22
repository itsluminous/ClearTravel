package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.core.security.vault.VaultState
import com.itsluminous.cleartravel.di.TestSecurityModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.applock.R as AppLockR

/**
 * ADR-031 first run: with NO vault set up, the shell shows the blocking password
 * setup instead of the tabs; a too-short pair is refused in place; creating a valid
 * password opens the app (Trips tab visible) and leaves the vault unlocked. The
 * suite's [TestSecurityModule] is switched to its fresh-install mode for this class.
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

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun freshInstall_showsSetup_refusesShortPassword_thenOpensTheApp() {
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_setup_title)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(AppLockR.string.applock_setup_warning_title)).assertIsDisplayed()
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

        passwordField.performTextInput("short")
        confirmField.performTextInput("short")
        composeRule.onNodeWithText(create).performClick()
        composeRule.waitForText(composeRule.string(AppLockR.string.applock_error_too_short, 8))
        check(keyVault.state.value == VaultState.NotSetUp)

        passwordField.performTextInput("-e2e-password")
        confirmField.performTextInput("-e2e-password")
        composeRule.onNodeWithText(create).performClick()

        composeRule.waitForText(composeRule.string(R.string.nav_trips))
        check(keyVault.state.value is VaultState.Unlocked)
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
