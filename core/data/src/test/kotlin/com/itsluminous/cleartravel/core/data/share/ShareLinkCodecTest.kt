package com.itsluminous.cleartravel.core.data.share

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.util.Base64

/** ADR-039: the share-link codec — round trips, both link shapes, typed failures, the size guard. */
class ShareLinkCodecTest {
    private val trip = Fixtures.trip(name = "Tokyo", destination = "Japan")

    private fun tripItems(count: Int) =
        List(count) { index ->
            Fixtures.itineraryItem(
                tripId = trip.id,
                dayIndex = index / 4,
                orderInDay = index % 4,
                name = "Place number $index with a fairly ordinary length name",
                note = "Open 09:00–18:00, buy tickets online, bring cash for the shrine stalls.",
                plannedTime = "1${index % 10}:30",
                category = PlaceCategory.entries[index % PlaceCategory.entries.size],
                link = if (index % 3 == 0) "https://example.com/place/$index" else "",
            )
        }

    @Test
    fun `trip payload round-trips through encode, https url, parse and decode`() {
        val items =
            tripItems(3) +
                Fixtures.itineraryItem(
                    tripId = trip.id,
                    dayIndex = 1,
                    type = ItineraryItemType.COMMUTE,
                    name = "To Kyoto",
                    commuteMode = CommuteMode.TRAIN,
                    fromName = "Tokyo",
                    toName = "Kyoto",
                    latitude = null,
                    longitude = null,
                    linkedJourneyId = "local-journey",
                    linkedJourneyType = JourneyType.TRAIN,
                )
        val payload = SharePayloadMappers.toPayload(trip, items)

        val url = (ShareLinkCodec.buildShareUrl(payload) as ShareUrlResult.Ok).url
        assertThat(url).startsWith("https://cleartravel.itsluminous.com/share/trip/")

        val decoded = ShareLinkCodec.decode(url) as ShareDecodeResult.Ok
        assertThat(decoded.payload).isEqualTo(payload)
        val back = decoded.payload as TripSharePayload
        assertThat(back.items.map { it.id }).containsExactlyElementsIn(items.map { it.id }).inOrder()
        assertThat(back.items.last().commuteMode).isEqualTo("train")
    }

    @Test
    fun `commute legs lose their device-local journey link and calendar id`() {
        val leg =
            Fixtures.itineraryItem(
                tripId = trip.id,
                type = ItineraryItemType.COMMUTE,
                linkedJourneyId = "journey-1",
                linkedJourneyType = JourneyType.FLIGHT,
                googleEventId = "evt",
            )
        val payload = SharePayloadMappers.toPayload(trip, listOf(leg))
        val json = ShareLinkCodec.json.encodeToString(TripSharePayload.serializer(), payload)

        assertThat(json).doesNotContain("journey-1")
        assertThat(json).doesNotContain("evt")
        assertThat(json).doesNotContain("linkedJourney")
        // Restoring on a device without a local twin yields an unlinked leg.
        val restored = SharePayloadMappers.toItineraryItems(payload, existing = emptyMap()).single()
        assertThat(restored.linkedJourneyId).isNull()
        assertThat(restored.linkedJourneyType).isNull()
        assertThat(restored.googleEventId).isNull()
    }

    @Test
    fun `checklist payload round-trips including checked state and sort order`() {
        val checklist = Fixtures.checklist(name = "Tokyo packing", tripId = trip.id)
        val items =
            listOf(
                Fixtures.checklistItem(checklistId = checklist.id, text = "Passport", checked = true, sortOrder = 0),
                Fixtures.checklistItem(checklistId = checklist.id, text = "Charger", checked = false, sortOrder = 1),
                Fixtures.checklistItem(checklistId = checklist.id, text = "JR Pass", checked = true, sortOrder = 2),
            )
        val payload = SharePayloadMappers.toPayload(checklist, items)

        val url = (ShareLinkCodec.buildShareUrl(payload) as ShareUrlResult.Ok).url
        assertThat(url).startsWith("https://cleartravel.itsluminous.com/share/checklist/")
        val decoded = (ShareLinkCodec.decode(url) as ShareDecodeResult.Ok).payload as ChecklistSharePayload
        assertThat(decoded).isEqualTo(payload)
        assertThat(decoded.items.map { it.checked }).containsExactly(true, false, true).inOrder()
        assertThat(decoded.tripId).isEqualTo(trip.id)
    }

    @Test
    fun `flight payload round-trips and carries no booking reference or seat`() {
        val flight = Fixtures.flightJourney(pnrBookingRef = "SECRET", seat = "14A", depTerminal = "T2")
        val payload = SharePayloadMappers.toPayload(flight)
        val blob = ShareLinkCodec.encode(payload)
        val json = ShareLinkCodec.json.encodeToString(FlightSharePayload.serializer(), payload)

        assertThat(json).doesNotContain("SECRET")
        assertThat(json).doesNotContain("14A")
        val decoded = (ShareLinkCodec.decode(ShareLink(ShareKind.FLIGHT, blob)) as ShareDecodeResult.Ok).payload
        assertThat(decoded).isEqualTo(payload)
        assertThat((decoded as FlightSharePayload).schedDep).isEqualTo(Fixtures.NOW.epochSecond)
        assertThat(SharePayloadMappers.toInstant(decoded.schedDep)).isEqualTo(Fixtures.NOW)
    }

    @Test
    fun `custom scheme twin parses to the same link`() {
        val payload = SharePayloadMappers.toPayload(trip, tripItems(2))
        val blob = ShareLinkCodec.encode(payload)

        val fromHttps = ShareLinkCodec.parse(ShareLinkCodec.httpsUrl(ShareKind.TRIP, blob))
        val fromCustom = ShareLinkCodec.parse(ShareLinkCodec.customUrl(ShareKind.TRIP, blob))

        assertThat(fromCustom).isEqualTo(fromHttps)
        assertThat(fromCustom).isEqualTo(ShareLink(ShareKind.TRIP, blob))
    }

    @Test
    fun `parse rejects other hosts, unknown kinds, pnr links and empty blobs`() {
        assertThat(ShareLinkCodec.parse("https://example.com/share/trip/abc")).isNull()
        assertThat(ShareLinkCodec.parse("https://cleartravel.itsluminous.com/share/hotel/abc")).isNull()
        assertThat(ShareLinkCodec.parse("https://cleartravel.itsluminous.com/pnr/8553674906")).isNull()
        assertThat(ShareLinkCodec.parse("https://cleartravel.itsluminous.com/share/trip/")).isNull()
        assertThat(ShareLinkCodec.parse("cleartravel://share/trip")).isNull()
        assertThat(ShareLinkCodec.parse("")).isNull()
        assertThat(ShareLinkCodec.parse(null)).isNull()
        assertThat(ShareLinkCodec.decode("https://cleartravel.itsluminous.com/pnr/8553674906")).isNull()
    }

    @Test
    fun `finds a share link wrapped in share-sheet prose`() {
        val url = (ShareLinkCodec.buildShareUrl(SharePayloadMappers.toPayload(trip, tripItems(1))) as ShareUrlResult.Ok).url
        val found = ShareLinkCodec.findInText("Here's my trip \"Tokyo\" — open in Clear Travel: $url.")

        assertThat(found).isEqualTo(ShareLinkCodec.parse(url))
        assertThat(ShareLinkCodec.findInText("PNR 8553674906 IRCTC")).isNull()
    }

    @Test
    fun `garbage blob decodes to CORRUPTED without throwing`() {
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, "not*base64!")))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.CORRUPTED))
        // Valid base64 but not a deflate stream.
        val junk = Base64.getUrlEncoder().withoutPadding().encodeToString("hello world".toByteArray())
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, junk)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.CORRUPTED))
        // Truncated real blob.
        val real = ShareLinkCodec.encode(SharePayloadMappers.toPayload(trip, tripItems(3)))
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, real.take(real.length / 2))))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.CORRUPTED))
    }

    @Test
    fun `payload of the wrong kind or with missing fields is CORRUPTED`() {
        val checklistBlob =
            ShareLinkCodec.encode(
                SharePayloadMappers.toPayload(Fixtures.checklist(), listOf(Fixtures.checklistItem(text = "Passport"))),
            )
        // A checklist blob presented as a trip: its items lack the mandatory `name` → deserialization failure.
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, checklistBlob)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.CORRUPTED))
        // Deflated JSON without a version key.
        val noVersion = encodeRawJson("""{"id":"${EntityIds.newId()}","name":"x"}""")
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, noVersion)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.CORRUPTED))
    }

    @Test
    fun `newer payload version is refused with UNSUPPORTED_VERSION`() {
        val newer = encodeRawJson("""{"v":${ShareLinkCodec.SUPPORTED_VERSION + 1},"id":"${EntityIds.newId()}","name":"Future"}""")
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, newer)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.UNSUPPORTED_VERSION))
    }

    @Test
    fun `older payload with unknown keys still decodes`() {
        val withExtras = encodeRawJson("""{"v":1,"id":"${EntityIds.newId()}","name":"Legacy","futureField":42}""")
        val decoded = ShareLinkCodec.decode(ShareLink(ShareKind.CHECKLIST, withExtras))
        assertThat(decoded).isInstanceOf(ShareDecodeResult.Ok::class.java)
        assertThat(((decoded as ShareDecodeResult.Ok).payload as ChecklistSharePayload).name).isEqualTo("Legacy")
    }

    @Test
    fun `malformed ids, blank names and duplicate item ids are INVALID_CONTENT`() {
        val badTripId = encodeRawJson("""{"v":1,"id":"not-a-uuid","name":"Tokyo"}""")
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, badTripId)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.INVALID_CONTENT))

        val blankName = encodeRawJson("""{"v":1,"id":"${EntityIds.newId()}","name":"   "}""")
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.CHECKLIST, blankName)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.INVALID_CONTENT))

        val itemId = EntityIds.newId()
        val duplicateItems =
            encodeRawJson(
                """{"v":1,"id":"${EntityIds.newId()}","name":"Pack","items":[{"id":"$itemId","text":"a"},{"id":"$itemId","text":"b"}]}""",
            )
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.CHECKLIST, duplicateItems)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.INVALID_CONTENT))

        val blankFlight = encodeRawJson("""{"v":1,"airlineIata":"","flightNumber":"101"}""")
        assertThat(ShareLinkCodec.decode(ShareLink(ShareKind.FLIGHT, blankFlight)))
            .isEqualTo(ShareDecodeResult.Failed(ShareLinkError.INVALID_CONTENT))
    }

    @Test
    fun `unknown enum storage values fall back instead of failing`() {
        val json =
            """{"v":1,"id":"${trip.id}","name":"Tokyo","items":[{"id":"${EntityIds.newId()}","name":"X","type":"teleport","category":"spa","commuteMode":"rocket"}]}"""
        val payload = (ShareLinkCodec.decode(ShareLink(ShareKind.TRIP, encodeRawJson(json))) as ShareDecodeResult.Ok).payload
        val item = SharePayloadMappers.toItineraryItems(payload as TripSharePayload, emptyMap()).single()

        assertThat(item.type).isEqualTo(ItineraryItemType.PLACE)
        assertThat(item.category).isEqualTo(PlaceCategory.OTHER)
        assertThat(item.commuteMode).isEqualTo(CommuteMode.OTHER)
    }

    @Test
    fun `typical trips stay far below the url ceiling - sizes measured`() {
        val sizes =
            listOf(1, 5, 10, 20).associateWith { count ->
                val url = ShareLinkCodec.buildShareUrl(SharePayloadMappers.toPayload(trip, tripItems(count)))
                (url as ShareUrlResult.Ok).url.length
            }
        // Documented in ADR-039; printed so the numbers are visible in the test report.
        println("ADR-039 share URL lengths by item count: $sizes")
        assertThat(sizes.getValue(10)).isLessThan(3_000)
        assertThat(sizes.getValue(20)).isLessThan(ShareLinkCodec.MAX_URL_LENGTH)

        val checklistUrl =
            ShareLinkCodec.buildShareUrl(
                SharePayloadMappers.toPayload(
                    Fixtures.checklist(name = "Tokyo packing"),
                    List(30) { Fixtures.checklistItem(text = "Packing item number $it", checked = it % 2 == 0, sortOrder = it) },
                ),
            ) as ShareUrlResult.Ok
        println("ADR-039 30-item checklist URL length: ${checklistUrl.url.length}")
        assertThat(checklistUrl.url.length).isLessThan(2_500)
    }

    @Test
    fun `oversized trip fails the url guard with TooLong`() {
        val huge =
            SharePayloadMappers.toPayload(
                trip,
                List(400) { index ->
                    Fixtures.itineraryItem(
                        tripId = trip.id,
                        dayIndex = index / 10,
                        orderInDay = index % 10,
                        name = "Unique place ${EntityIds.newId()}",
                        note = "Note ${EntityIds.newId()} ${EntityIds.newId()}",
                    )
                },
            )
        val result = ShareLinkCodec.buildShareUrl(huge)

        assertThat(result).isInstanceOf(ShareUrlResult.TooLong::class.java)
        assertThat((result as ShareUrlResult.TooLong).length).isGreaterThan(ShareLinkCodec.MAX_URL_LENGTH)
    }

    /** Deflates arbitrary JSON the way the codec does, for hand-crafted blobs. */
    private fun encodeRawJson(json: String): String {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip
            .DeflaterOutputStream(out, deflater)
            .use { it.write(json.toByteArray()) }
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
}
