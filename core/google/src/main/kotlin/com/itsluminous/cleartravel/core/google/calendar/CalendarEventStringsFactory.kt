package com.itsluminous.cleartravel.core.google.calendar

import android.content.Context
import com.itsluminous.cleartravel.core.google.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the localized [CalendarEventStrings] templates from `core:google`'s string
 * resources (hard rule 1) so [CalendarEventMapper] stays pure.
 */
@Singleton
class CalendarEventStringsFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun create(): CalendarEventStrings =
            CalendarEventStrings(
                trainSummary = context.getString(R.string.google_event_train_summary),
                flightSummary = context.getString(R.string.google_event_flight_summary),
                commuteSummary = context.getString(R.string.google_event_commute_summary),
                pnrLine = context.getString(R.string.google_event_pnr_line),
                seatLine = context.getString(R.string.google_event_seat_line),
                passengerLine = context.getString(R.string.google_event_passenger_line),
            )

        /** The dedicated calendar's display name. */
        fun calendarName(): String = context.getString(R.string.google_calendar_name)

        /** The app's Drive folder name. */
        fun driveFolderName(): String = context.getString(R.string.google_drive_folder_name)
    }
