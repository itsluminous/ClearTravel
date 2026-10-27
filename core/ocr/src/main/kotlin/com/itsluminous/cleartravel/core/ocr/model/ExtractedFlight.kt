package com.itsluminous.cleartravel.core.ocr.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * ONE flight segment read from a travel document (ADR-045). A boarding pass normally
 * yields one, a booking confirmation one per leg, a multi-page "boarding pass" PDF one
 * per page; the document-level results ([BoardingPassExtraction],
 * [BookingConfirmationExtraction]) carry a LIST of these. Every field is an
 * [ExtractedField] with its own confidence (ADR-009 ladder); an unrecognised field is
 * simply [ExtractedField.EMPTY]. Dates are ISO-8601 `yyyy-MM-dd`, times 24-hour
 * `HH:mm`, terminals bare (`2`, not `T2`).
 *
 * ADR-047: the people on board are a LIST of [ExtractedPassenger] — a seat-fare table
 * with two travellers, a stacked page with two passes for the same flight, two pass
 * pages of one PDF all become ONE segment with several passengers. [passengerName],
 * [seat] and [sequenceNumber] stay as DERIVED views of passenger #1 so single-person
 * callers read what they always did; the JSON form keeps those flat fields alongside a
 * `passengers` array, and a fixture without the array decodes them as its only
 * passenger (every pre-ADR-047 expected file stays valid and identical).
 */
@Serializable(with = ExtractedFlightSerializer::class)
data class ExtractedFlight(
    /** Airline record locator (6-char) preferred; OTA booking reference as fallback. */
    val pnr: ExtractedField = ExtractedField.EMPTY,
    /** Airline IATA code, e.g. `6E`. */
    val carrier: ExtractedField = ExtractedField.EMPTY,
    /** Flight number without the carrier, leading zeros stripped. */
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    /** IATA code when printed; may carry a city name (LOW) when no code is present. */
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val depTime: ExtractedField = ExtractedField.EMPTY,
    val arrTime: ExtractedField = ExtractedField.EMPTY,
    val depTerminal: ExtractedField = ExtractedField.EMPTY,
    val arrTerminal: ExtractedField = ExtractedField.EMPTY,
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
    /** The people on this flight in document order; empty when the document named nobody (ADR-047). */
    val passengers: List<ExtractedPassenger> = emptyList(),
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** Passenger #1's name — what a single-person document prints (derived, ADR-047). */
    val passengerName: ExtractedField get() = passengers.firstOrNull()?.name ?: ExtractedField.EMPTY

    /** Passenger #1's seat (derived, ADR-047). */
    val seat: ExtractedField get() = passengers.firstOrNull()?.seat ?: ExtractedField.EMPTY

    /** Passenger #1's boarding-pass check-in sequence number (derived, ADR-047). */
    val sequenceNumber: ExtractedField get() = passengers.firstOrNull()?.sequenceNumber ?: ExtractedField.EMPTY

    /**
     * True when the segment carries everything a journey needs to be saved WITHOUT a
     * review form: airline, flight number, date and both airports (ADR-045 direct-save
     * rule; the form's own validation is still applied by the caller).
     */
    val hasMinimumFields: Boolean
        get() =
            carrier.isPresent &&
                flightNumber.isPresent &&
                flightDate.isPresent &&
                fromAirport.isPresent &&
                toAirport.isPresent

    /**
     * Fills every blank flight field of this segment from [other] (confidence travels
     * with the value) and MERGES the passengers (ADR-047): a person [other] names who is
     * already here fills their blanks, anyone new is appended.
     */
    fun fillBlanksFrom(other: ExtractedFlight): ExtractedFlight = merge(other, other.passengers)

    /**
     * [fillBlanksFrom] for a SECOND MENTION of the same flight (ADR-047 segment
     * dedupe): a passenger of [other] whose segment printed a different locator / cabin
     * than the merged flight's keeps that difference on their own row — a stacked page
     * of two passes with two locators yields two passengers, each with their own PNR.
     */
    fun mergeSegment(other: ExtractedFlight): ExtractedFlight {
        val mergedPnr = pnr.or(other.pnr)
        val mergedCabin = cabinClass.or(other.cabinClass)
        val qualified =
            other.passengers.map { passenger ->
                passenger.copy(
                    pnr = passenger.pnr.or(other.pnr.differingFrom(mergedPnr)),
                    cabinClass = passenger.cabinClass.or(other.cabinClass.differingFrom(mergedCabin)),
                )
            }
        return merge(other, qualified)
    }

    private fun merge(
        other: ExtractedFlight,
        incomingPassengers: List<ExtractedPassenger>,
    ): ExtractedFlight =
        ExtractedFlight(
            pnr = pnr.or(other.pnr),
            carrier = carrier.or(other.carrier),
            flightNumber = flightNumber.or(other.flightNumber),
            fromAirport = fromAirport.or(other.fromAirport),
            toAirport = toAirport.or(other.toAirport),
            flightDate = flightDate.or(other.flightDate),
            depTime = depTime.or(other.depTime),
            arrTime = arrTime.or(other.arrTime),
            depTerminal = depTerminal.or(other.depTerminal),
            arrTerminal = arrTerminal.or(other.arrTerminal),
            cabinClass = cabinClass.or(other.cabinClass),
            passengers = ExtractedPassenger.merge(passengers, incomingPassengers),
        )

    companion object {
        val EMPTY = ExtractedFlight()

        private fun ExtractedField.or(fallback: ExtractedField): ExtractedField = if (isPresent) this else fallback

        /** This field when present and different from [merged]; EMPTY otherwise. */
        private fun ExtractedField.differingFrom(merged: ExtractedField): ExtractedField =
            if (isPresent && value != merged.value) this else ExtractedField.EMPTY
    }
}

/**
 * JSON shape of [ExtractedFlight]: the pre-ADR-047 flat fields (`passengerName`,
 * `seat`, `sequenceNumber` = passenger #1) PLUS the `passengers` array. Decoding
 * prefers the array; a document without one builds its single passenger from the flat
 * fields, so recorded fixtures never had to change.
 */
@Serializable
private class ExtractedFlightSurrogate(
    val passengerName: ExtractedField = ExtractedField.EMPTY,
    val pnr: ExtractedField = ExtractedField.EMPTY,
    val carrier: ExtractedField = ExtractedField.EMPTY,
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val depTime: ExtractedField = ExtractedField.EMPTY,
    val arrTime: ExtractedField = ExtractedField.EMPTY,
    val depTerminal: ExtractedField = ExtractedField.EMPTY,
    val arrTerminal: ExtractedField = ExtractedField.EMPTY,
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
    val seat: ExtractedField = ExtractedField.EMPTY,
    val sequenceNumber: ExtractedField = ExtractedField.EMPTY,
    val passengers: List<ExtractedPassenger>? = null,
)

private object ExtractedFlightSerializer : KSerializer<ExtractedFlight> {
    override val descriptor: SerialDescriptor = ExtractedFlightSurrogate.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: ExtractedFlight,
    ) {
        encoder.encodeSerializableValue(
            ExtractedFlightSurrogate.serializer(),
            ExtractedFlightSurrogate(
                passengerName = value.passengerName,
                pnr = value.pnr,
                carrier = value.carrier,
                flightNumber = value.flightNumber,
                fromAirport = value.fromAirport,
                toAirport = value.toAirport,
                flightDate = value.flightDate,
                depTime = value.depTime,
                arrTime = value.arrTime,
                depTerminal = value.depTerminal,
                arrTerminal = value.arrTerminal,
                cabinClass = value.cabinClass,
                seat = value.seat,
                sequenceNumber = value.sequenceNumber,
                passengers = value.passengers,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): ExtractedFlight {
        val s = decoder.decodeSerializableValue(ExtractedFlightSurrogate.serializer())
        return ExtractedFlight(
            pnr = s.pnr,
            carrier = s.carrier,
            flightNumber = s.flightNumber,
            fromAirport = s.fromAirport,
            toAirport = s.toAirport,
            flightDate = s.flightDate,
            depTime = s.depTime,
            arrTime = s.arrTime,
            depTerminal = s.depTerminal,
            arrTerminal = s.arrTerminal,
            cabinClass = s.cabinClass,
            passengers = s.passengers ?: ExtractedPassenger.single(s.passengerName, s.seat, s.sequenceNumber),
        )
    }
}
