package com.itsluminous.cleartravel.core.data.crosstab

import com.itsluminous.cleartravel.core.model.JourneyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An itinerary commute leg asking for a NEW journey of [type] to be added and handed
 * back (ADR-028). [nonce] identifies the request so a result is only ever consumed by
 * the form that asked for it.
 */
data class JourneyAddRequest(
    val type: JourneyType,
    val nonce: Long = System.nanoTime(),
)

/** How a [JourneyAddRequest] ended. [nonce] echoes the request it answers. */
sealed interface JourneyAddResult {
    val nonce: Long

    /**
     * A journey to link: freshly saved, OR the already-existing one when the add was
     * refused as a duplicate (ADR-024/025) — from the itinerary's point of view both
     * are "the journey the user meant".
     */
    data class Added(
        override val nonce: Long,
        val type: JourneyType,
        val journeyId: String,
    ) : JourneyAddResult

    /** The user backed out; nothing was written and nothing gets linked. */
    data class Cancelled(
        override val nonce: Long,
    ) : JourneyAddResult
}

/**
 * Cross-tab seam between the Trips tab (requester) and the Journeys tab (fulfiller),
 * ADR-028. Feature modules never depend on each other, so the itinerary form only
 * knows this contract: it posts a [request] and awaits the matching entry in
 * [results]. The app shell observes [pendingRequest] to switch tabs and open the add
 * flow, and calls [complete] with what the Journeys segment reported — the shell is
 * the ONLY producer of results. A new [request] supersedes a still-pending one
 * (which is completed as [JourneyAddResult.Cancelled]).
 */
interface JourneyAddRequestBus {
    /** The request currently awaiting fulfilment, or null. */
    val pendingRequest: StateFlow<JourneyAddRequest?>

    /** Every completed request, in completion order. Filter by nonce. */
    val results: Flow<JourneyAddResult>

    fun request(type: JourneyType): JourneyAddRequest

    fun complete(result: JourneyAddResult)
}

/** Process-wide in-memory [JourneyAddRequestBus]; the whole flow happens in one app session. */
@Singleton
class InMemoryJourneyAddRequestBus
    @Inject
    constructor() : JourneyAddRequestBus {
        private val pending = MutableStateFlow<JourneyAddRequest?>(null)
        private val completed = MutableSharedFlow<JourneyAddResult>(extraBufferCapacity = RESULT_BUFFER)

        override val pendingRequest: StateFlow<JourneyAddRequest?> = pending.asStateFlow()
        override val results: Flow<JourneyAddResult> = completed.asSharedFlow()

        override fun request(type: JourneyType): JourneyAddRequest {
            pending.value?.let { stale -> completed.tryEmit(JourneyAddResult.Cancelled(stale.nonce)) }
            val request = JourneyAddRequest(type = type)
            pending.value = request
            return request
        }

        override fun complete(result: JourneyAddResult) {
            if (pending.value?.nonce == result.nonce) pending.value = null
            completed.tryEmit(result)
        }

        private companion object {
            /** Results are consumed within the same frame; a small buffer only guards late collectors. */
            const val RESULT_BUFFER = 8
        }
    }
