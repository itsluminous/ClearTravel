package com.itsluminous.cleartravel.core.testing

import java.time.Instant

/**
 * Fixture builders for domain models: every field gets a sensible default so tests
 * override only what they assert on. Fixture names are intentionally not user-visible
 * strings — fixtures never reach the UI. Builders are added here as each feature
 * milestone introduces its entities (trips, itinerary items, train/flight journeys,
 * checklists).
 */
object Fixtures {
    /** Deterministic id for the singleton "current test row" — stable across runs. */
    const val FIXED_ID = "00000000-0000-0000-0000-0000c1ea51de"

    /** Deterministic clock for `updatedAt`/`createdAt` fields. */
    val NOW: Instant = Instant.parse("2026-09-20T09:00:00Z")
}
