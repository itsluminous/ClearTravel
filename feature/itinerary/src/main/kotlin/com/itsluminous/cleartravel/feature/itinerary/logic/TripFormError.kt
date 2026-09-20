package com.itsluminous.cleartravel.feature.itinerary.logic

import java.time.LocalDate

/** Validation failure of the trip add/edit form. */
enum class TripFormError {
    NAME_REQUIRED,
    END_BEFORE_START,
}

/**
 * Validates the trip form: a non-blank name is required and, when both dates are
 * set, the end date must be on or after the start date. Null on success.
 */
fun validateTripForm(
    name: String,
    startDate: LocalDate?,
    endDate: LocalDate?,
): TripFormError? =
    when {
        name.isBlank() -> TripFormError.NAME_REQUIRED
        startDate != null && endDate != null && endDate.isBefore(startDate) -> TripFormError.END_BEFORE_START
        else -> null
    }
