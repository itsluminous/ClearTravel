package com.itsluminous.cleartravel.core.ocr.model

import com.itsluminous.cleartravel.core.model.PassengerIdentity
import kotlinx.serialization.Serializable

/**
 * ONE person read from a flight document (ADR-047): their name, seat and check-in
 * sequence, plus the record locator / cabin printed FOR THEM when it differs from
 * the segment's own ([ExtractedFlight.pnr] / [ExtractedFlight.cabinClass]) — a stacked
 * page of two passes may carry two locators. Every field is an [ExtractedField]; a
 * passenger nobody knows anything about is never created (see [hasInfo]).
 */
@Serializable
data class ExtractedPassenger(
    val name: ExtractedField = ExtractedField.EMPTY,
    val seat: ExtractedField = ExtractedField.EMPTY,
    /** Boarding-pass check-in sequence number; absent on confirmations. */
    val sequenceNumber: ExtractedField = ExtractedField.EMPTY,
    /** This passenger's record locator when it differs from the segment's; usually EMPTY. */
    val pnr: ExtractedField = ExtractedField.EMPTY,
    /** This passenger's cabin when it differs from the segment's; usually EMPTY. */
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
) {
    /** A passenger worth keeping: a name, a seat or a sequence number. */
    val hasInfo: Boolean get() = name.isPresent || seat.isPresent || sequenceNumber.isPresent

    /** What [PassengerIdentity] compares (per-passenger locator only; the segment's is shared). */
    val identityKeys: PassengerIdentity.Keys
        get() =
            PassengerIdentity.Keys(
                name = name.value.orEmpty(),
                seat = seat.value.orEmpty(),
                sequenceNumber = sequenceNumber.value.orEmpty(),
                pnr = pnr.value.orEmpty(),
            )

    /** Every blank field filled from [other] (confidence travels with the value). */
    fun fillBlanksFrom(other: ExtractedPassenger): ExtractedPassenger =
        ExtractedPassenger(
            name = name.or(other.name),
            seat = seat.or(other.seat),
            sequenceNumber = sequenceNumber.or(other.sequenceNumber),
            pnr = pnr.or(other.pnr),
            cabinClass = cabinClass.or(other.cabinClass),
        )

    companion object {
        /**
         * The passenger list of a single-person source: ONE passenger when any of
         * [name] / [seat] / [sequenceNumber] is present, none otherwise.
         */
        fun single(
            name: ExtractedField = ExtractedField.EMPTY,
            seat: ExtractedField = ExtractedField.EMPTY,
            sequenceNumber: ExtractedField = ExtractedField.EMPTY,
        ): List<ExtractedPassenger> =
            ExtractedPassenger(name = name, seat = seat, sequenceNumber = sequenceNumber)
                .takeIf { it.hasInfo }
                ?.let { listOf(it) }
                .orEmpty()

        /**
         * [existing] grown by [incoming], one person at a time through
         * [PassengerIdentity]: a match fills the matched passenger's blanks, anyone
         * else is appended in order. Passengers without info are ignored.
         */
        fun merge(
            existing: List<ExtractedPassenger>,
            incoming: List<ExtractedPassenger>,
        ): List<ExtractedPassenger> {
            val result = existing.filter { it.hasInfo }.toMutableList()
            for (candidate in incoming.filter { it.hasInfo }) {
                val index = PassengerIdentity.indexOfMatch(result.map { it.identityKeys }, candidate.identityKeys)
                if (index >= 0) result[index] = result[index].fillBlanksFrom(candidate) else result += candidate
            }
            return result
        }

        private fun ExtractedField.or(fallback: ExtractedField): ExtractedField = if (isPresent) this else fallback
    }
}
