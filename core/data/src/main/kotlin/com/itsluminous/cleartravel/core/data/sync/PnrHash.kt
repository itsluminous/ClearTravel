package com.itsluminous.cleartravel.core.data.sync

import java.security.MessageDigest

/**
 * ADR-044: the ONLY form in which a train PNR may appear in the plaintext sync store.
 * A 10-digit PNR unlocks the live IRCTC status page, so the locked-readable hints and
 * the "already reminded" set carry `SHA-256(pnr)` instead — enough for the worker to
 * recognise a ticket it has already reminded about, useless to anyone reading the
 * preferences file (the 10^10 search space is small, so this is obscurity against
 * casual reading, not a cryptographic guarantee; the departure instants beside it are
 * the same class of data ADR-043 already accepted).
 */
object PnrHash {
    /** Lower-case hex SHA-256 of the trimmed, upper-cased [pnr]. */
    fun of(pnr: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pnr.trim().uppercase().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * A stable 31-bit notification id for [pnr]: the first eight hex digits of the hash
     * with the sign bit cleared, so one ticket owns one reminder slot.
     */
    fun notificationId(pnr: String): Int = of(pnr).take(HEX_DIGITS_FOR_ID).toLong(radix = 16).toInt() and Int.MAX_VALUE

    private const val HEX_DIGITS_FOR_ID = 8
}
