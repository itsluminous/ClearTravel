package com.itsluminous.cleartravel.feature.itinerary.place

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises geocoder calls app-wide and keeps at least [MIN_INTERVAL_MILLIS] between
 * them — Nominatim's usage policy is one request per second (ADR-048). The clock and
 * the sleep are injectable so tests run without real time.
 */
@Singleton
class GeocodePacer internal constructor(
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    @Inject
    constructor() : this(System::currentTimeMillis, { delay(it) })

    private val mutex = Mutex()
    private var lastCallAt = Long.MIN_VALUE / 2

    /** Runs [block] once the interval since the previous paced call has elapsed. */
    suspend fun <T> paced(block: suspend () -> T): T =
        mutex.withLock {
            val wait = lastCallAt + MIN_INTERVAL_MILLIS - now()
            if (wait > 0) sleep(wait)
            try {
                block()
            } finally {
                lastCallAt = now()
            }
        }

    companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
    }
}
