package com.itsluminous.cleartravel.core.model

/**
 * The ONE rule deciding whether two passenger records describe the same person
 * (ADR-046 decision 5, hoisted here by ADR-047 so the OCR extractor and the data
 * layer cannot drift): names decide when both sides have one — a different name is
 * never a match, even on the same seat; a named record is otherwise only "the same"
 * as a NAMELESS one on the same seat (a legacy backfill waiting for its name); a
 * nameless record matches by seat, else sequence number, else record locator.
 *
 * Pure and storage-agnostic: callers project their own type onto [Keys] with the
 * four raw strings and read the match back by index. `core:data`'s
 * `FlightPassengerMatching` and `core:ocr`'s segment merge both delegate here; no
 * other copy of the rule may exist.
 */
object PassengerIdentity {
    private val HONORIFICS = setOf("MR", "MRS", "MS", "MISS", "MSTR", "MASTER", "DR", "PROF", "SHRI", "SMT")

    /** The identifying strings of one passenger record, raw as stored or printed (blank = unknown). */
    data class Keys(
        val name: String = "",
        val seat: String = "",
        val sequenceNumber: String = "",
        val pnr: String = "",
    )

    /**
     * Upper-cased letter tokens, honorifics dropped, as a SORTED set — so the BCBP
     * `SHARMA/RAHUL`, a ticket's `Rahul Sharma` and an OTA's `MR RAHUL SHARMA` are one
     * person. Empty when the name carries no letters.
     */
    fun normalizeName(raw: String): Set<String> =
        raw
            .uppercase()
            .split(Regex("[^A-Z]+"))
            .filter { it.isNotBlank() && it !in HONORIFICS }
            .toSortedSet()

    /** Upper-cased seat without leading zeros (`010A` → `10A`), empty when blank. */
    fun normalizeSeat(raw: String): String = raw.trim().uppercase().trimStart('0')

    /** Sequence number without leading zeros (`0042` → `42`), empty when blank. */
    fun normalizeSequence(raw: String): String = raw.trim().trimStart('0')

    /** Upper-cased record locator, empty when blank. */
    fun normalizePnr(raw: String): String = raw.trim().uppercase()

    /**
     * Index in [existing] of the record [incoming] denotes, or -1 when they are a new
     * person. The first match in list order wins.
     */
    fun indexOfMatch(
        existing: List<Keys>,
        incoming: Keys,
    ): Int {
        val incomingName = normalizeName(incoming.name)
        val incomingSeat = normalizeSeat(incoming.seat)
        val incomingSequence = normalizeSequence(incoming.sequenceNumber)
        val incomingPnr = normalizePnr(incoming.pnr)
        // Names first: the most specific signal when both sides carry one.
        if (incomingName.isNotEmpty()) {
            existing.indexOfFirst { normalizeName(it.name) == incomingName }.takeIf { it >= 0 }?.let { return it }
            // A named passenger that matches NO existing name is only "the same" as a
            // nameless row with the same seat.
            if (incomingSeat.isEmpty()) return -1
            return existing.indexOfFirst { normalizeName(it.name).isEmpty() && normalizeSeat(it.seat) == incomingSeat }
        }
        if (incomingSeat.isNotEmpty()) {
            return existing.indexOfFirst { normalizeSeat(it.seat) == incomingSeat }
        }
        if (incomingSequence.isNotEmpty()) {
            existing.indexOfFirst { normalizeSequence(it.sequenceNumber) == incomingSequence }.takeIf { it >= 0 }?.let { return it }
        }
        if (incomingPnr.isNotEmpty()) {
            existing.indexOfFirst { normalizePnr(it.pnr) == incomingPnr }.takeIf { it >= 0 }?.let { return it }
        }
        return -1
    }
}
