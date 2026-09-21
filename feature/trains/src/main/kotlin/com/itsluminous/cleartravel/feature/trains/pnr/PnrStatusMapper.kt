package com.itsluminous.cleartravel.feature.trains.pnr

import com.itsluminous.cleartravel.core.data.provider.TrainPassengerStatus
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * PURE mapper from the `indianrail-pnr` rule's [ScrapedData] shape to the ADR-005
 * [TrainStatusResult] contract. The shape contract (see the rule's fixture
 * `core/scrape/src/test/resources/fixtures/indianrail-pnr/expected.json`):
 *
 * - `fields`: `trainNumber`, `trainName`, `chartingStatus`
 *   ("Chart Prepared" / "Chart Not Prepared"), plus journey fields not used here.
 * - `rows`: one map per passenger with `passenger`, `bookingStatus`
 *   (e.g. `CNF/B4/32/GN`), `currentStatus` (e.g. `CNF/B4/32`, `RAC 4`, `WL 12`).
 *
 * [TrainPassengerStatus.currentStatus] keeps the RAW page text (nothing is lost);
 * coach/seat-berth are additionally extracted from a `STATUS/COACH/SEAT` shape so
 * `TrainRepository.applyStatusResult` can merge them. Returns null when no
 * passenger row carries any status text (garbage/unusable page) — callers treat
 * that exactly like a parse failure and leave stored data unchanged.
 */
object PnrStatusMapper {
    private const val FIELD_TRAIN_NUMBER = "trainNumber"
    private const val FIELD_TRAIN_NAME = "trainName"
    private const val FIELD_CHARTING_STATUS = "chartingStatus"
    private const val FIELD_JOURNEY_DATE = "journeyDate"
    private const val FIELD_BOARDING_POINT = "boardingPoint"
    private const val FIELD_FROM_STATION = "fromStation"
    private const val FIELD_RESERVED_UPTO = "reservedUpto"
    private const val FIELD_TO_STATION = "toStation"
    private const val FIELD_JOURNEY_CLASS = "journeyClass"
    private const val ROW_BOOKING_STATUS = "bookingStatus"
    private const val ROW_CURRENT_STATUS = "currentStatus"

    /** Minimum `STATUS/COACH/SEAT` segments for coach+seat extraction. */
    private const val COACH_SEAT_SEGMENTS = 3

    fun map(
        pnr: String,
        data: ScrapedData,
        fetchedAt: Instant,
    ): TrainStatusResult? {
        val passengers =
            data.rows.map { row ->
                toPassengerStatus(
                    bookingStatus = row[ROW_BOOKING_STATUS].orEmpty().trim(),
                    currentStatus = row[ROW_CURRENT_STATUS].orEmpty().trim(),
                )
            }
        if (passengers.none { it.currentStatus.isNotEmpty() || it.bookingStatus.isNotEmpty() }) {
            return null
        }
        return TrainStatusResult(
            pnr = pnr,
            passengers = passengers,
            chartPrepared = chartPrepared(data.fields[FIELD_CHARTING_STATUS].orEmpty()),
            trainNumber = data.fields[FIELD_TRAIN_NUMBER].orEmpty().trim(),
            trainName = data.fields[FIELD_TRAIN_NAME].orEmpty().trim(),
            journeyDate = parseIsoDate(data.fields[FIELD_JOURNEY_DATE].orEmpty().trim()),
            // Boarding point / reserved-upto are the passenger's actual journey ends;
            // fall back to the train's own from/to columns when absent.
            fromStation =
                data.fields[FIELD_BOARDING_POINT].orEmpty().trim().ifEmpty {
                    data.fields[FIELD_FROM_STATION].orEmpty().trim()
                },
            toStation =
                data.fields[FIELD_RESERVED_UPTO].orEmpty().trim().ifEmpty {
                    data.fields[FIELD_TO_STATION].orEmpty().trim()
                },
            travelClass = data.fields[FIELD_JOURNEY_CLASS].orEmpty().trim(),
            fetchedAt = fetchedAt,
        )
    }

    /** The rule post-processes the date to ISO `uuuu-MM-dd`; anything else → null. */
    private fun parseIsoDate(raw: String): LocalDate? =
        try {
            if (raw.isEmpty()) null else LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun toPassengerStatus(
        bookingStatus: String,
        currentStatus: String,
    ): TrainPassengerStatus {
        val segments = currentStatus.split('/').map(String::trim).filter(String::isNotEmpty)
        val hasCoachSeat = segments.size >= COACH_SEAT_SEGMENTS
        return TrainPassengerStatus(
            currentStatus = currentStatus,
            bookingStatus = bookingStatus,
            coach = if (hasCoachSeat) segments[1] else "",
            seatBerth = if (hasCoachSeat) segments[2] else "",
        )
    }

    /**
     * "Chart Not Prepared" → false, "Chart Prepared" → true, anything else → null
     * (unknown). The "not" check runs first — both texts contain "prepared".
     */
    private fun chartPrepared(chartingStatus: String): Boolean? {
        val normalized = chartingStatus.lowercase()
        return when {
            normalized.isBlank() -> null
            "not" in normalized -> false
            "prepared" in normalized -> true
            else -> null
        }
    }
}
