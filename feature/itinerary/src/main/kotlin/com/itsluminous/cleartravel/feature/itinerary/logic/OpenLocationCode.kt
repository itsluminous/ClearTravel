package com.itsluminous.cleartravel.feature.itinerary.logic

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A minimal, pure Open Location Code ("Plus Code") implementation (ADR-048): decode
 * a full code, and recover a SHORT code (`VW6G+7P3`, as Google Maps prints in a
 * place's address) to the nearest full code around a reference point. Mirrors the
 * reference algorithm (github.com/google/open-location-code) and is checked against
 * its published test vectors; encoding is only what recovery needs.
 */
object OpenLocationCode {
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING = '0'
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val ENCODING_BASE = 20
    private const val PAIR_CODE_LENGTH = 10
    private const val MAX_CODE_LENGTH = 15
    private const val GRID_ROWS = 5
    private const val GRID_COLUMNS = 4
    private const val LATITUDE_MAX = 90.0
    private const val LONGITUDE_MAX = 180.0
    private const val GRID_DIGITS = MAX_CODE_LENGTH - PAIR_CODE_LENGTH

    /** 1 / 0.000125°: the number of finest-pair cells per degree. */
    private const val FINEST_PAIR_STEPS = 8000.0

    /** Resolution in degrees of each digit pair: 20°, 1°, 0.05°, 0.0025°, 0.000125°. */
    private val PAIR_RESOLUTIONS = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

    /** The decoded cell; [latitude]/[longitude] are its centre. */
    data class Area(
        val latitudeLo: Double,
        val longitudeLo: Double,
        val latitudeHi: Double,
        val longitudeHi: Double,
    ) {
        val latitude: Double get() = min(LATITUDE_MAX, (latitudeLo + latitudeHi) / 2)
        val longitude: Double get() = min(LONGITUDE_MAX, (longitudeLo + longitudeHi) / 2)
    }

    /** Matches a plus code (short `XXXX+XX` to full `XXXXXXXX+XXX`) as a whole token. */
    val CODE_PATTERN =
        Regex("""(?:[23456789CFGHJMPQRVWX]{8}|[23456789CFGHJMPQRVWX]{4,6})\+[23456789CFGHJMPQRVWX]{2,7}""", RegexOption.IGNORE_CASE)

    /** True for a syntactically valid full OR short code. */
    fun isValid(code: String): Boolean {
        val upper = code.trim().uppercase()
        val separator = upper.indexOf(SEPARATOR)
        if (separator < 0 || separator != upper.lastIndexOf(SEPARATOR)) return false
        if (separator > SEPARATOR_POSITION || separator % 2 == 1) return false
        if (upper.length - separator - 1 == 1) return false
        val padStart = upper.indexOf(PADDING)
        if (padStart >= 0) {
            if (separator < SEPARATOR_POSITION) return false
            if (padStart == 0) return false
            val padEnd = upper.lastIndexOf(PADDING)
            if (padEnd != separator - 1 || (padEnd - padStart + 1) % 2 == 1) return false
            if (upper.length != separator + 1) return false
        }
        return upper.withIndex().all { (index, ch) -> ch in ALPHABET || ch == PADDING || index == separator }
    }

    /** A code is short when the separator sits before position 8 (leading pairs removed). */
    fun isShort(code: String): Boolean {
        if (!isValid(code)) return false
        val separator = code.indexOf(SEPARATOR)
        return separator in 0 until SEPARATOR_POSITION
    }

    fun isFull(code: String): Boolean {
        if (!isValid(code) || isShort(code)) return false
        val upper = code.uppercase()
        val firstLat = ALPHABET.indexOf(upper[0]) * ENCODING_BASE
        if (firstLat >= LATITUDE_MAX * 2) return false
        if (upper.length > 1) {
            val firstLng = ALPHABET.indexOf(upper[1]) * ENCODING_BASE
            if (firstLng >= LONGITUDE_MAX * 2) return false
        }
        return true
    }

    /** Decodes a FULL code; null when the code is not a valid full code. */
    fun decode(code: String): Area? {
        if (!isFull(code)) return null
        val clean = code.uppercase().replace(SEPARATOR.toString(), "").replace(PADDING.toString(), "")
        var latitudeLo = -LATITUDE_MAX
        var longitudeLo = -LONGITUDE_MAX
        // Divided by 20 before the first pair is applied, so that pair spans 20°.
        var latitudeResolution = ENCODING_BASE.toDouble() * ENCODING_BASE
        var longitudeResolution = ENCODING_BASE.toDouble() * ENCODING_BASE
        var index = 0
        while (index < min(clean.length, PAIR_CODE_LENGTH)) {
            latitudeResolution /= ENCODING_BASE
            longitudeResolution /= ENCODING_BASE
            latitudeLo += latitudeResolution * ALPHABET.indexOf(clean[index])
            longitudeLo += longitudeResolution * ALPHABET.indexOf(clean[index + 1])
            index += 2
        }
        while (index < min(clean.length, MAX_CODE_LENGTH)) {
            latitudeResolution /= GRID_ROWS
            longitudeResolution /= GRID_COLUMNS
            val digit = ALPHABET.indexOf(clean[index])
            latitudeLo += latitudeResolution * (digit / GRID_COLUMNS)
            longitudeLo += longitudeResolution * (digit % GRID_COLUMNS)
            index += 1
        }
        return Area(
            latitudeLo = latitudeLo,
            longitudeLo = longitudeLo,
            latitudeHi = latitudeLo + latitudeResolution,
            longitudeHi = longitudeLo + longitudeResolution,
        )
    }

    /**
     * Expands a SHORT code to the full code nearest to the reference point (the
     * reference algorithm's `recoverNearest`). A full code is returned as-is; an
     * invalid code yields null.
     */
    fun recoverNearest(
        shortCode: String,
        referenceLatitude: Double,
        referenceLongitude: Double,
    ): String? {
        if (!isValid(shortCode)) return null
        val upper = shortCode.trim().uppercase()
        if (isFull(upper)) return upper
        val refLat = clipLatitude(referenceLatitude)
        val refLng = normalizeLongitude(referenceLongitude)
        val paddingLength = SEPARATOR_POSITION - upper.indexOf(SEPARATOR)
        val resolution = ENCODING_BASE.toDouble().pow(2 - paddingLength / 2)
        val halfResolution = resolution / 2
        val candidate = encode(refLat, refLng, PAIR_CODE_LENGTH).substring(0, paddingLength) + upper
        val area = decode(candidate) ?: return null
        var latitude = area.latitude
        var longitude = area.longitude
        if (refLat + halfResolution < latitude && latitude - resolution >= -LATITUDE_MAX) {
            latitude -= resolution
        } else if (refLat - halfResolution > latitude && latitude + resolution <= LATITUDE_MAX) {
            latitude += resolution
        }
        if (refLng + halfResolution < longitude) {
            longitude -= resolution
        } else if (refLng - halfResolution > longitude) {
            longitude += resolution
        }
        return encode(latitude, longitude, candidate.count { it != SEPARATOR && it != PADDING })
    }

    /** Encodes a point to a code of [codeLength] digits (pairs only up to 10, grid digits beyond). */
    fun encode(
        latitude: Double,
        longitude: Double,
        codeLength: Int = PAIR_CODE_LENGTH,
    ): String {
        val length = max(2, min(MAX_CODE_LENGTH, codeLength))
        var lat = clipLatitude(latitude)
        var lng = normalizeLongitude(longitude)
        if (lat == LATITUDE_MAX) lat -= computeLatitudePrecision(length)
        // Integer arithmetic like the reference: 1/8000° is the finest pair, then 5^5 / 4^5 grid steps.
        var latInt = floor(roundMicro((lat + LATITUDE_MAX) * FINEST_PAIR_STEPS * GRID_ROWS.toDouble().pow(GRID_DIGITS))).toLong()
        var lngInt = floor(roundMicro((lng + LONGITUDE_MAX) * FINEST_PAIR_STEPS * GRID_COLUMNS.toDouble().pow(GRID_DIGITS))).toLong()
        val digits = CharArray(MAX_CODE_LENGTH)
        for (i in MAX_CODE_LENGTH - 1 downTo PAIR_CODE_LENGTH) {
            val latDigit = (latInt % GRID_ROWS).toInt()
            val lngDigit = (lngInt % GRID_COLUMNS).toInt()
            digits[i] = ALPHABET[latDigit * GRID_COLUMNS + lngDigit]
            latInt /= GRID_ROWS
            lngInt /= GRID_COLUMNS
        }
        var i = PAIR_CODE_LENGTH - 1
        while (i >= 0) {
            digits[i] = ALPHABET[(lngInt % ENCODING_BASE).toInt()]
            digits[i - 1] = ALPHABET[(latInt % ENCODING_BASE).toInt()]
            latInt /= ENCODING_BASE
            lngInt /= ENCODING_BASE
            i -= 2
        }
        val code = String(digits)
        return if (length < SEPARATOR_POSITION) {
            code.substring(0, length) + PADDING.toString().repeat(SEPARATOR_POSITION - length) + SEPARATOR
        } else {
            code.substring(0, SEPARATOR_POSITION) + SEPARATOR + code.substring(SEPARATOR_POSITION, length)
        }
    }

    private fun computeLatitudePrecision(codeLength: Int): Double =
        if (codeLength <= PAIR_CODE_LENGTH) {
            ENCODING_BASE.toDouble().pow(floor(codeLength / -2.0 + 2))
        } else {
            ENCODING_BASE.toDouble().pow(-3) / GRID_ROWS.toDouble().pow(codeLength - PAIR_CODE_LENGTH)
        }

    private fun roundMicro(value: Double): Double = Math.round(value * 1e6) / 1e6

    private fun clipLatitude(latitude: Double): Double = min(LATITUDE_MAX, max(-LATITUDE_MAX, latitude))

    private fun normalizeLongitude(longitude: Double): Double {
        var lng = longitude
        while (lng < -LONGITUDE_MAX) lng += LONGITUDE_MAX * 2
        while (lng >= LONGITUDE_MAX) lng -= LONGITUDE_MAX * 2
        return lng
    }
}
