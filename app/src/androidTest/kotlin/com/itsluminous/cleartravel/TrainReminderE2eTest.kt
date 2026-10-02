package com.itsluminous.cleartravel

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.notifications.NotificationChannels
import com.itsluminous.cleartravel.core.notifications.TrainNotifier
import com.itsluminous.cleartravel.feature.menu.TRAIN_REMINDER_LEAD_TAG_PREFIX
import com.itsluminous.cleartravel.feature.trains.reminder.TrainReminderOutcome
import com.itsluminous.cleartravel.feature.trains.reminder.TrainReminderRunner
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.menu.R as MenuR
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * ADR-044 end to end on the hermetic module (pre-unlocked vault, in-memory Room): a
 * ticket departing in 20 h → one reminder pass posts exactly one notification on the
 * trains channel with the right title and marks it; a second pass posts nothing; the
 * PNR link of a ticket you already have lands on its PNR check; Settings →
 * Notifications → Off turns the pass into a no-op.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrainReminderE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var trainRepository: TrainRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var gate: BackgroundSyncGate

    @Inject lateinit var stateStore: BackgroundSyncStateStore

    @Inject lateinit var trainNotifier: TrainNotifier

    @Inject lateinit var appLockNotifier: AppLockNotifier

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: NotificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var ticketId: String

    @Before
    fun setUp() {
        hiltRule.inject()
        manager.cancelAll()
        val zone = ZoneId.systemDefault()
        val departure = Instant.now().plus(Duration.ofHours(20)).atZone(zone)
        runBlocking {
            settingsRepository.setTrainReminderLead(TrainReminderLead.ONE_DAY)
            stateStore.retainRemindedTrainKeys(emptyList())
            val ticket =
                trainRepository.save(
                    TrainTicket(
                        pnr = PNR,
                        trainNumber = TRAIN_NUMBER,
                        trainName = TRAIN_NAME,
                        journeyDate = departure.toLocalDate(),
                        fromStation = BOARDING,
                        toStation = "New Delhi",
                        travelClass = "3A",
                    ),
                )
            ticketId = ticket.id
            trainRepository.savePassengers(listOf(TrainPassenger(ticketId = ticket.id, name = "Reminder Tester", bookingStatus = "WL 12")))
            trainRepository.replaceRouteStops(
                ticket.id,
                listOf(
                    TrainRouteStop(
                        ticketId = ticket.id,
                        stationName = "$BOARDING (MMCT)",
                        departure = departure.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        sortOrder = 0,
                    ),
                    TrainRouteStop(ticketId = ticket.id, stationName = "New Delhi (NDLS)", arrival = "08:35", day = 2, sortOrder = 1),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        runBlocking {
            settingsRepository.setTrainReminderLead(TrainReminderLead.DEFAULT)
            trainRepository.delete(ticketId)
            stateStore.retainRemindedTrainKeys(emptyList())
        }
    }

    private fun runner() =
        TrainReminderRunner(
            gate = gate,
            stateStore = stateStore,
            trainRepository = trainRepository,
            settingsRepository = settingsRepository,
            post = trainNotifier::notifyJourneyReminder,
            notifyUnlockToSync = appLockNotifier::notifyUnlockToSync,
            nudgeAlreadyPosted = { appLockNotifier.postedThisProcess },
            fallbackLabel = { pnr -> context.getString(TrainsR.string.trains_reminder_label_pnr, pnr) },
        )

    private fun trainReminders() = manager.activeNotifications.filter { it.notification.channelId == NotificationChannels.CHANNEL_TRAINS }

    @Test
    fun ticketIn20h_onePassPostsOneReminder_secondPassIsIdempotent_tapLandsOnPnrCheck() {
        val first = runBlocking { runner().run() } as TrainReminderOutcome.Ran
        assertThat(first.posted).isEqualTo(1)
        assertThat(first.lead).isEqualTo(TrainReminderLead.ONE_DAY)

        val reminders = trainReminders()
        assertThat(reminders).hasSize(1)
        val posted = reminders.single()
        assertThat(posted.id).isEqualTo(PnrHash.notificationId(PNR))
        val title =
            posted.notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                .toString()
        val text =
            posted.notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TEXT)
                .toString()
        assertThat(title).contains("$TRAIN_NUMBER $TRAIN_NAME")
        assertThat(title).startsWith("Train ")
        assertThat(text).contains(BOARDING)
        assertThat(text).contains("WL 12")
        // Hints + reminded set: hash only, never the PNR.
        val hints = runBlocking { stateStore.trainDepartureHints() }
        assertThat(hints.map { it.pnrHash }).contains(PnrHash.of(PNR))
        assertThat(hints.none { it.pnrHash.contains(PNR) }).isTrue()
        assertThat(runBlocking { stateStore.remindedTrainKeys() }.any { it.contains(PNR) }).isFalse()

        val second = runBlocking { runner().run() } as TrainReminderOutcome.Ran
        assertThat(second.posted).isEqualTo(0)
        assertThat(trainReminders()).hasSize(1)

        // The notification's tap: the PNR link of a ticket we already have → its PNR check.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, TrainNotifier.pnrLink(PNR))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_pnr_check_title))
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_pnr_check_title)).assertIsDisplayed()
    }

    @Test
    fun settings_notifications_leadOff_passPostsNothing_andBackToDefault() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_menu)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_settings))
        composeRule.onNodeWithText(composeRule.string(MenuR.string.menu_settings)).performClick()
        composeRule.waitForText(composeRule.string(MenuR.string.menu_notifications_title))

        composeRule
            .onNodeWithText(
                composeRule.string(MenuR.string.menu_notifications_train_reminder_title),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                TRAIN_REMINDER_LEAD_TAG_PREFIX + TrainReminderLead.ONE_DAY.storageValue,
            ).performScrollTo()
            .assertIsSelected()

        composeRule.onNodeWithTag(TRAIN_REMINDER_LEAD_TAG_PREFIX + TrainReminderLead.OFF.storageValue).performScrollTo().performClick()
        composeRule.waitUntil(E2e.WAIT_TIMEOUT_MILLIS) {
            runBlocking { settingsRepository.trainReminderLead.first() } == TrainReminderLead.OFF
        }
        composeRule.onNodeWithTag(TRAIN_REMINDER_LEAD_TAG_PREFIX + TrainReminderLead.OFF.storageValue).assertIsSelected()

        val off = runBlocking { runner().run() } as TrainReminderOutcome.Ran
        assertThat(off.posted).isEqualTo(0)
        assertThat(off.lead).isEqualTo(TrainReminderLead.OFF)
        assertThat(trainReminders()).isEmpty()

        composeRule.onNodeWithTag(TRAIN_REMINDER_LEAD_TAG_PREFIX + TrainReminderLead.TWO_DAYS.storageValue).performScrollTo().performClick()
        composeRule.waitUntil(E2e.WAIT_TIMEOUT_MILLIS) {
            runBlocking { settingsRepository.trainReminderLead.first() } == TrainReminderLead.TWO_DAYS
        }
        val on = runBlocking { runner().run() } as TrainReminderOutcome.Ran
        assertThat(on.posted).isEqualTo(1)
        assertThat(trainReminders()).hasSize(1)
    }

    private companion object {
        const val PNR = "8524167890"
        const val TRAIN_NUMBER = "12951"
        const val TRAIN_NAME = "Mumbai Rajdhani"
        const val BOARDING = "Mumbai Central"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
