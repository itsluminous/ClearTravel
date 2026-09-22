package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.menu.R as MenuR

/**
 * ADR-037: Menu → Backup & Restore shows the "Automatic backup" cadence picker with
 * its four cadences (hermetic — nothing seeded; the Off default enqueues no job).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BackupScheduleE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun backupRestore_showsSchedulePicker() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_menu)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_backup_restore))
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_backup_restore)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_backup_schedule_title))

        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_backup_schedule_title)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_backup_schedule_off)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_backup_schedule_daily)).assertIsDisplayed()
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
