package com.itsluminous.cleartravel.feature.documents

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.ui.graphics.vector.ImageVector
import com.itsluminous.cleartravel.core.designsystem.DateFormats
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The shipped type presets (ADR-027): every [TravelDocumentType] maps to a label
 * resource (the default name a new document gets) and a list icon. Presets are the
 * enum itself — adding a type is a model + strings change, never a UI edit.
 */
object DocumentTypePresets {
    /** Chip/menu order: the common paper first, `OTHER` last. */
    val ordered: List<TravelDocumentType> = TravelDocumentType.entries.toList()

    @StringRes
    fun labelRes(type: TravelDocumentType): Int =
        when (type) {
            TravelDocumentType.PASSPORT -> R.string.documents_type_passport
            TravelDocumentType.VISA -> R.string.documents_type_visa
            TravelDocumentType.ID_CARD -> R.string.documents_type_id_card
            TravelDocumentType.DRIVING_LICENSE -> R.string.documents_type_driving_license
            TravelDocumentType.INSURANCE -> R.string.documents_type_insurance
            TravelDocumentType.VACCINATION -> R.string.documents_type_vaccination
            TravelDocumentType.TICKET -> R.string.documents_type_ticket
            TravelDocumentType.OTHER -> R.string.documents_type_other
        }

    fun icon(type: TravelDocumentType): ImageVector =
        when (type) {
            TravelDocumentType.PASSPORT -> Icons.Filled.Public
            TravelDocumentType.VISA -> Icons.Filled.Badge
            TravelDocumentType.ID_CARD -> Icons.Filled.CreditCard
            TravelDocumentType.DRIVING_LICENSE -> Icons.Filled.DirectionsCar
            TravelDocumentType.INSURANCE -> Icons.Filled.HealthAndSafety
            TravelDocumentType.VACCINATION -> Icons.Filled.Vaccines
            TravelDocumentType.TICKET -> Icons.Filled.ConfirmationNumber
            TravelDocumentType.OTHER -> Icons.Filled.Description
        }

    /** Types whose documents usually carry an expiry — the dialog opens the date row for them. */
    fun usuallyExpires(type: TravelDocumentType): Boolean =
        type == TravelDocumentType.PASSPORT ||
            type == TravelDocumentType.VISA ||
            type == TravelDocumentType.ID_CARD ||
            type == TravelDocumentType.DRIVING_LICENSE ||
            type == TravelDocumentType.INSURANCE
}

/** How an expiry date relates to today — drives the card's expiry line colour/text. */
enum class ExpiryState {
    /** No expiry recorded. */
    NONE,

    /** More than [DocumentExpiry.SOON_DAYS] days away. */
    VALID,

    /** Within [DocumentExpiry.SOON_DAYS] days (today included). */
    EXPIRING_SOON,

    /** Before today. */
    EXPIRED,
}

/** Pure expiry classification + formatting (unit-tested; the UI only maps to strings). */
object DocumentExpiry {
    /** Passports need ≥6 months validity for most visas — flag anything closer than that. */
    const val SOON_DAYS = 180L

    fun stateOf(
        expiryDate: LocalDate?,
        today: LocalDate,
    ): ExpiryState =
        when {
            expiryDate == null -> ExpiryState.NONE
            expiryDate.isBefore(today) -> ExpiryState.EXPIRED
            ChronoUnit.DAYS.between(today, expiryDate) <= SOON_DAYS -> ExpiryState.EXPIRING_SOON
            else -> ExpiryState.VALID
        }

    /** Medium locale date, e.g. `12 Mar 2031` (the app-wide [DateFormats]). */
    fun format(date: LocalDate): String = DateFormats.formatDate(date)
}
