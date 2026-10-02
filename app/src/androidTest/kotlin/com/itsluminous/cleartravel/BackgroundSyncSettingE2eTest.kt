package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.di.TestSecurityModule
import com.itsluminous.cleartravel.feature.menu.BACKGROUND_SYNC_SWITCH_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.menu.R as MenuR

/**
 * ADR-043: Settings → Security → "Allow sync while locked". Flipping the switch ON opens
 * the password confirmation (nothing changes until it is answered), a wrong password
 * keeps the switch off, the right one turns it on (the vault now holds a background
 * wrap); flipping it OFF removes the wrap. Hermetic: the pre-unlocked
 * [TestSecurityModule] vault with its plain-AES background-key stand-in.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BackgroundSyncSettingE2eTest {
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
    fun security_allowSyncWhileLocked_toggleOn_needsPassword_toggleOff() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_menu)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_settings))
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_settings)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_security_title))

        val switchTitle = composeRule.string(MenuR.string.menu_security_background_sync_switch_title)
        composeRule.onNodeWithText(switchTitle).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_security_sync_status_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_security_sync_kind_flights)).performScrollTo().assertIsDisplayed()
        val switch = composeRule.onNodeWithTag(BACKGROUND_SYNC_SWITCH_TAG)
        switch.performScrollTo().assertIsOff()
        check(!keyVault.hasBackgroundKey)

        // ON → the confirmation dialog, switch still off.
        switch.performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_security_background_sync_confirm_title))
        val passwordField =
            composeRule.onNode(hasSetTextAction() and hasText(composeRule.string(MenuR.string.menu_security_current_password)))
        val allow = composeRule.string(MenuR.string.menu_security_background_sync_confirm_action)

        passwordField.performTextInput("definitely-wrong")
        composeRule.onNodeWithText(allow).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_security_background_sync_wrong_password))
        check(!keyVault.hasBackgroundKey)
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_security_background_sync_confirm_title)).assertIsDisplayed()

        passwordField.performTextClearance()
        passwordField.performTextInput(TestSecurityModule.TEST_PASSWORD)
        composeRule.onNodeWithText(allow).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_security_background_sync_enabled))
        composeRule.waitUntil(E2e.WAIT_TIMEOUT_MILLIS) { keyVault.hasBackgroundKey }
        composeRule.onNodeWithTag(BACKGROUND_SYNC_SWITCH_TAG).performScrollTo().assertIsOn()

        // OFF → wrap gone, switch off.
        composeRule.onNodeWithTag(BACKGROUND_SYNC_SWITCH_TAG).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_security_background_sync_disabled))
        composeRule.waitUntil(E2e.WAIT_TIMEOUT_MILLIS) { !keyVault.hasBackgroundKey }
        composeRule.onNodeWithTag(BACKGROUND_SYNC_SWITCH_TAG).performScrollTo().assertIsOff()
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
