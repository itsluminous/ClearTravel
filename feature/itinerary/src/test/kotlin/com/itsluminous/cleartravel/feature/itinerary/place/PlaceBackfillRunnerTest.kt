package com.itsluminous.cleartravel.feature.itinerary.place

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeNetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakePlaceLocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** ADR-048: the backfill runner over fake Room + a scripted ladder. */
class PlaceBackfillRunnerTest {
    private val repository = FakeItineraryRepository()
    private val locator = FakePlaceLocator()
    private val network = FakeNetworkAvailability()
    private var now: Instant = Instant.parse("2026-10-28T12:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId) = this

            override fun instant() = now
        }
    private val logs = mutableListOf<String>()
    private val runner = PlaceBackfillRunner(repository, locator, network, clock, log = { logs += it })

    private val saved = Instant.parse("2026-10-01T00:00:00Z")

    private fun unplaced(
        name: String,
        tripId: String = TRIP,
        link: String = "",
    ) = Fixtures.itineraryItem(tripId = tripId, name = name, latitude = null, longitude = null, link = link, updatedAt = saved)

    @Test
    fun `places unplaced items, bumps updatedAt and skips placed, tombstoned, commute and bare-name items`() =
        runTest {
            val rumi = unplaced(RUMI)
            val linkOnly = unplaced("Shared place", link = "https://maps.app.goo.gl/SPre92rQxfbVNLoY7")
            val placed = Fixtures.itineraryItem(tripId = TRIP, name = "Placed, Lucknow", updatedAt = saved)
            val gone = unplaced("Gone, Lucknow").copy(deletedAt = saved)
            val leg = unplaced("Leg, Lucknow").copy(type = ItineraryItemType.COMMUTE)
            val bare = unplaced("Lunch")
            repository.items.value = listOf(rumi, linkOnly, placed, gone, leg, bare)
            locator.answer(RUMI, 26.8712, 80.9122)
            locator.answer("https://maps.app.goo.gl/SPre92rQxfbVNLoY7", 26.87, 80.91, source = PlaceSource.LINK)

            val count = runner.backfill(TRIP)

            assertThat(count).isEqualTo(2)
            val byId = repository.items.value.associateBy { it.id }
            assertThat(byId[rumi.id]?.latitude).isEqualTo(26.8712)
            assertThat(byId[rumi.id]?.updatedAt).isEqualTo(now)
            assertThat(byId[linkOnly.id]?.longitude).isEqualTo(80.91)
            assertThat(byId[placed.id]).isEqualTo(placed)
            assertThat(byId[gone.id]).isEqualTo(gone)
            assertThat(byId[leg.id]).isEqualTo(leg)
            assertThat(byId[bare.id]).isEqualTo(bare)
            assertThat(locator.calls.map { it.second }).containsExactly(RUMI, "Shared place")
            assertThat(logs).hasSize(2)
            assertThat(runner.progress.value).isEmpty()
        }

    /** The progress flow is a StateFlow (conflating): observers see the per-item steps, and null once the run is over. */
    @Test
    fun `progress counts items for the trip and disappears at the end`() =
        runTest {
            repository.items.value = listOf(unplaced(RUMI), unplaced(BADA))
            locator.answer(RUMI, 26.87, 80.91)
            val seen = mutableListOf<PlaceBackfillProgress?>()
            val collector = launch { runner.progressFor(TRIP).collect { seen += it } }
            yield()

            assertThat(runner.backfill(TRIP)).isEqualTo(1)
            yield()
            collector.cancel()

            assertThat(seen.first()).isNull()
            assertThat(seen).containsAtLeast(PlaceBackfillProgress(0, 2), PlaceBackfillProgress(1, 2)).inOrder()
            assertThat(seen.last()).isNull()
            assertThat(runner.progressFor("other").first()).isNull()
        }

    @Test
    fun `a failed item is not retried within the cooldown and is retried after it`() =
        runTest {
            repository.items.value = listOf(unplaced(BADA))

            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(locator.calls).hasSize(1)

            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(locator.calls).hasSize(1)

            now = now.plus(PlaceBackfillRunner.RETRY_COOLDOWN).plus(Duration.ofSeconds(1))
            locator.answer(BADA, 26.8696, 80.9135)
            assertThat(runner.backfill(TRIP)).isEqualTo(1)
            assertThat(locator.calls).hasSize(2)
        }

    /** Fixing a typo is the user's own retry: a renamed (or re-linked) item is tried again at once. */
    @Test
    fun `editing a failed item's name or link retries it before the cooldown`() =
        runTest {
            val typo = unplaced("Rumi Darwazza, Lucknow")
            repository.items.value = listOf(typo)
            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(locator.calls).hasSize(1)

            repository.save(typo.copy(name = "Rumi Darwaza, Lucknow"))
            locator.answer("Rumi Darwaza, Lucknow", 26.8712, 80.9122)

            assertThat(runner.backfill(TRIP)).isEqualTo(1)
            assertThat(locator.calls.map { it.second }).containsExactly("Rumi Darwazza, Lucknow", "Rumi Darwaza, Lucknow").inOrder()
        }

    @Test
    fun `offline does nothing and remembers nothing`() =
        runTest {
            repository.items.value = listOf(unplaced(BADA))
            network.online = false

            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(runner.backfillAll()).isEqualTo(0)
            assertThat(locator.calls).isEmpty()
            assertThat(runner.progress.value).isEmpty()

            network.online = true
            locator.answer(BADA, 26.8696, 80.9135)
            assertThat(runner.backfill(TRIP)).isEqualTo(1)
        }

    @Test
    fun `a second run is a no-op once everything is placed`() =
        runTest {
            repository.items.value = listOf(unplaced(RUMI))
            locator.answer(RUMI, 26.87, 80.91)

            assertThat(runner.backfill(TRIP)).isEqualTo(1)
            assertThat(runner.backfill(TRIP)).isEqualTo(0)
            assertThat(locator.calls).hasSize(1)
        }

    /** The map must not flash "Finding locations…" for a run that has nothing to do (all placed, or all cooling down). */
    @Test
    fun `a run with nothing due publishes no progress at all`() =
        runTest {
            repository.items.value = listOf(unplaced(BADA), Fixtures.itineraryItem(tripId = TRIP, name = "Placed, Lucknow"))
            val seen = mutableListOf<PlaceBackfillProgress?>()
            val collector = launch { runner.progressFor(TRIP).collect { seen += it } }
            yield()

            runner.backfill(TRIP) // BADA tried and failed → cooldown
            yield()
            val afterFirst = seen.size
            runner.backfill(TRIP) // nothing due
            runner.backfillAll()
            yield()
            collector.cancel()

            assertThat(seen.filterNotNull()).isNotEmpty()
            assertThat(seen.size).isEqualTo(afterFirst)
            assertThat(seen.last()).isNull()
        }

    @Test
    fun `an item deleted or placed while the ladder runs is left alone`() =
        runTest {
            val rumi = unplaced(RUMI)
            val bada = unplaced(BADA)
            repository.items.value = listOf(rumi, bada)
            val gate = CompletableDeferred<Unit>()
            val slowLocator =
                object : PlaceLocator {
                    override suspend fun locate(
                        link: String?,
                        text: String?,
                    ): LocatedPlace? {
                        gate.await()
                        return LocatedPlace(
                            com.itsluminous.cleartravel.feature.itinerary.logic
                                .MapPoint(1.0, 2.0),
                            PlaceSource.GEOCODE,
                        )
                    }
                }
            val slowRunner = PlaceBackfillRunner(repository, slowLocator, network, clock, log = {})

            val run = async { slowRunner.backfill(TRIP) }
            repository.delete(rumi.id)
            repository.save(bada.copy(latitude = 26.0, longitude = 80.0))
            gate.complete(Unit)

            assertThat(run.await()).isEqualTo(0)
            assertThat(
                repository.items.value
                    .single()
                    .latitude,
            ).isEqualTo(26.0)
        }

    @Test
    fun `the same trip is not backfilled twice concurrently`() =
        runTest {
            repository.items.value = listOf(unplaced(RUMI))
            val gate = CompletableDeferred<Unit>()
            val slowLocator =
                object : PlaceLocator {
                    var calls = 0

                    override suspend fun locate(
                        link: String?,
                        text: String?,
                    ): LocatedPlace? {
                        calls++
                        gate.await()
                        return null
                    }
                }
            val slowRunner = PlaceBackfillRunner(repository, slowLocator, network, clock, log = {})

            val first = async { slowRunner.backfill(TRIP) }
            val second = async { slowRunner.backfill(TRIP) }
            gate.complete(Unit)

            assertThat(first.await() + second.await()).isEqualTo(0)
            assertThat(slowLocator.calls).isEqualTo(1)
        }

    @Test
    fun `backfillAll walks every trip through the repository work list`() =
        runTest {
            repository.items.value = listOf(unplaced(RUMI, tripId = "a"), unplaced(BADA, tripId = "b"), unplaced("Lunch", tripId = "b"))
            locator.answer(RUMI, 26.87, 80.91)
            locator.answer(BADA, 26.8696, 80.9135)

            assertThat(runner.backfillAll()).isEqualTo(2)
            assertThat(repository.items.value.count { it.latitude != null }).isEqualTo(2)
            assertThat(locator.calls.map { it.second }).containsExactly(RUMI, BADA)
        }

    private companion object {
        const val TRIP = "trip-lucknow"
        const val RUMI =
            "Rumi Darwaza, 17/11 Hussainabad Road, Lajpat Nagar Colony, Lajpat Nagar, Machchhi Bhavan, Lucknow, Uttar Pradesh 226003"
        const val BADA = "Bada Imambara (Main Building), Machchhi Bhavan, Lucknow, Uttar Pradesh 226003"
    }
}
