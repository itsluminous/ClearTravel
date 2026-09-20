package com.itsluminous.cleartravel.feature.flights.status

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.notifications.FlightNotifier
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-level seam over `core:notifications` so ViewModels stay plain-JVM testable
 * (the notifier needs a Context; tests substitute a recording fake).
 */
interface FlightStatusAlerts {
    /** Posts one notification per detected [changes] entry for [flight]. */
    fun announce(
        flight: FlightJourney,
        changes: List<FlightChange>,
    )
}

/** Production implementation: maps [FlightChange]s 1:1 onto [FlightNotifier] calls. */
@Singleton
class NotifierFlightStatusAlerts
    @Inject
    constructor(
        private val notifier: FlightNotifier,
    ) : FlightStatusAlerts {
        override fun announce(
            flight: FlightJourney,
            changes: List<FlightChange>,
        ) {
            val label = "${flight.airlineIata} ${flight.flightNumber}"
            for (change in changes) {
                when (change) {
                    is FlightChange.GateAssigned -> notifier.notifyGateAssigned(flight.id, label, change.gate)
                    is FlightChange.GateChanged ->
                        notifier.notifyGateChanged(flight.id, label, newGate = change.newGate, oldGate = change.oldGate)
                    is FlightChange.Delayed ->
                        notifier.notifyDelayed(flight.id, label, change.newEstimatedDeparture?.let(::formatTime))
                    is FlightChange.Cancelled -> notifier.notifyCancelled(flight.id, label)
                    is FlightChange.BeltAssigned -> notifier.notifyBeltAssigned(flight.id, label, change.belt)
                }
            }
        }

        private fun formatTime(instant: java.time.Instant): String =
            DateTimeFormatter
                .ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant)
    }
