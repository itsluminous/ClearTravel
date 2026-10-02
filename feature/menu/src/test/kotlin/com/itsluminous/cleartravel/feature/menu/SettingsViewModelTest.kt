package com.itsluminous.cleartravel.feature.menu

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = FakeSettingsRepository()

    private fun viewModel() = SettingsViewModel(settingsRepository)

    @Test
    fun `themeMode defaults to SYSTEM`() =
        runTest {
            viewModel().themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeMode.SYSTEM)
            }
        }

    @Test
    fun `setThemeMode persists and re-emits`() =
        runTest {
            val viewModel = viewModel()
            viewModel.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeMode.SYSTEM)
                viewModel.setThemeMode(ThemeMode.DARK)
                assertThat(awaitItem()).isEqualTo(ThemeMode.DARK)
                viewModel.setThemeMode(ThemeMode.LIGHT)
                assertThat(awaitItem()).isEqualTo(ThemeMode.LIGHT)
            }
        }

    /** ADR-044: the reminder is on (24 h) without setup and every choice persists. */
    @Test
    fun `trainReminderLead defaults to one day and persists every choice`() =
        runTest {
            val viewModel = viewModel()
            viewModel.trainReminderLead.test {
                assertThat(awaitItem()).isEqualTo(TrainReminderLead.ONE_DAY)
                viewModel.setTrainReminderLead(TrainReminderLead.OFF)
                assertThat(awaitItem()).isEqualTo(TrainReminderLead.OFF)
                viewModel.setTrainReminderLead(TrainReminderLead.TWO_DAYS)
                assertThat(awaitItem()).isEqualTo(TrainReminderLead.TWO_DAYS)
                viewModel.setTrainReminderLead(TrainReminderLead.TWELVE_HOURS)
                assertThat(awaitItem()).isEqualTo(TrainReminderLead.TWELVE_HOURS)
            }
            assertThat(settingsRepository.trainReminderLead.first()).isEqualTo(TrainReminderLead.TWELVE_HOURS)
        }
}
