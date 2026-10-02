package com.itsluminous.cleartravel.feature.trains.reminder

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** ADR-044 §2/§6: departure derivation and the reminder's wording inputs. */
class TrainDepartureAndContentTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val date: LocalDate = LocalDate.of(2026, 10, 4)
    private val ticket =
        Fixtures.trainTicket(
            pnr = "8524167890",
            trainNumber = "12951",
            trainName = "Mumbai Rajdhani",
            journeyDate = date,
            fromStation = "Mumbai Central",
        )
    private val stops =
        listOf(
            Fixtures.trainRouteStop(
                ticketId = ticket.id,
                stationName = "Mumbai Central (MMCT)",
                arrival = "",
                departure = "16:35",
                sortOrder = 0,
            ),
            Fixtures.trainRouteStop(
                ticketId = ticket.id,
                stationName = "Borivali (BVI)",
                arrival = "17:05",
                departure = "17:07",
                sortOrder = 1,
            ),
            Fixtures.trainRouteStop(
                ticketId = ticket.id,
                stationName = "New Delhi (NDLS)",
                arrival = "08:35",
                departure = "",
                day = 2,
                sortOrder = 2,
            ),
        )

    @Test
    fun departure_isBoardingStationTime_inZone_whenRouteKnown() {
        val departure = TrainDeparture.of(ticket, stops, zone)!!
        assertThat(departure.timeKnown).isTrue()
        assertThat(departure.instant).isEqualTo(Instant.parse("2026-10-04T11:05:00Z")) // 16:35 IST
    }

    @Test
    fun departure_matchesBoardingStation_notFirstStop_whenBoardingDownstream() {
        val boardingLater = ticket.copy(fromStation = "Borivali")
        assertThat(TrainDeparture.of(boardingLater, stops, zone)!!.instant).isEqualTo(Instant.parse("2026-10-04T11:37:00Z"))
    }

    @Test
    fun departure_isStartOfDay_whenRouteUnknown_andNullWithoutDate() {
        val noRoute = TrainDeparture.of(ticket, emptyList(), zone)!!
        assertThat(noRoute.timeKnown).isFalse()
        assertThat(noRoute.instant).isEqualTo(date.atStartOfDay(zone).toInstant())
        assertThat(TrainDeparture.of(ticket.copy(journeyDate = null), stops, zone)).isNull()
    }

    @Test
    fun content_carriesLabel_station_day_time_statusRollUp_andStableId() {
        val passengers =
            listOf(
                Fixtures.trainPassenger(ticketId = ticket.id, bookingStatus = "WL 20", currentStatus = "WL 12", sortOrder = 0),
                Fixtures.trainPassenger(ticketId = ticket.id, bookingStatus = "RAC 3", currentStatus = "", sortOrder = 1),
                Fixtures.trainPassenger(ticketId = ticket.id, bookingStatus = "WL 12", currentStatus = "WL 12", sortOrder = 2),
            )
        val now = Instant.parse("2026-10-03T12:00:00Z") // 3 Oct 17:30 IST → departure is "tomorrow"
        val content =
            TrainReminderContentBuilder.build(
                ticket,
                passengers,
                TrainDeparture.of(ticket, stops, zone)!!,
                now,
                zone,
                fallbackLabel = { "PNR $it" },
                locale = Locale.ENGLISH,
            )

        assertThat(content.pnr).isEqualTo("8524167890")
        assertThat(content.trainLabel).isEqualTo("12951 Mumbai Rajdhani")
        assertThat(content.boardingStation).isEqualTo("Mumbai Central")
        assertThat(content.departureDayText).isEqualTo("Sun 4 Oct")
        assertThat(content.departureText).isEqualTo("Sun 4 Oct, 16:35")
        assertThat(content.daysUntilDeparture).isEqualTo(1)
        assertThat(content.statusSummary).isEqualTo("WL 12, RAC 3")
        assertThat(content.notificationId).isEqualTo(PnrHash.notificationId("8524167890"))
    }

    @Test
    fun content_fallsBackToPnrLabel_dayOnlyWithoutRoute_nullStatusWhenUnknown() {
        val bare = ticket.copy(trainNumber = "", trainName = " ")
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val content =
            TrainReminderContentBuilder.build(
                bare,
                emptyList(),
                TrainDeparture.of(bare, emptyList(), zone)!!,
                now,
                zone,
                fallbackLabel = { "PNR $it" },
                locale = Locale.ENGLISH,
            )
        assertThat(content.trainLabel).isEqualTo("PNR 8524167890")
        assertThat(content.departureText).isEqualTo("Sun 4 Oct")
        assertThat(content.daysUntilDeparture).isEqualTo(3)
        assertThat(content.statusSummary).isNull()
        assertThat(
            TrainReminderContentBuilder.statusSummary(listOf(Fixtures.trainPassenger(bookingStatus = " ", currentStatus = ""))),
        ).isNull()
    }
}
