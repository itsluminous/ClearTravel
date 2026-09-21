package com.itsluminous.cleartravel.feature.trains.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TicketShareLinksTest {
    private val template = "Check out my train ticket (PNR %1\$s): %2\$s"

    @Test
    fun `share url is the github pages pnr path`() {
        assertThat(TicketShareLinks.shareUrl("8553674906"))
            .isEqualTo("https://itsluminous.github.io/ClearTravel/pnr/8553674906")
    }

    @Test
    fun `share text fills pnr and link into the template`() {
        assertThat(TicketShareLinks.buildShareText("8553674906", template))
            .isEqualTo("Check out my train ticket (PNR 8553674906): https://itsluminous.github.io/ClearTravel/pnr/8553674906")
    }

    @Test
    fun `parses the https share link`() {
        assertThat(TicketShareLinks.parsePnr("https://itsluminous.github.io/ClearTravel/pnr/8553674906")).isEqualTo("8553674906")
    }

    @Test
    fun `parses the custom scheme link with trailing slash and query`() {
        assertThat(TicketShareLinks.parsePnr("cleartravel://pnr/8553674906/?utm_source=share")).isEqualTo("8553674906")
    }

    @Test
    fun `round trips what it builds`() {
        assertThat(TicketShareLinks.parsePnr(TicketShareLinks.shareUrl("1234567890"))).isEqualTo("1234567890")
    }

    @Test
    fun `rejects other hosts, other paths and malformed pnrs`() {
        assertThat(TicketShareLinks.parsePnr("https://example.com/ClearTravel/pnr/8553674906")).isNull()
        assertThat(TicketShareLinks.parsePnr("https://itsluminous.github.io/ClearTravel/flight/8553674906")).isNull()
        assertThat(TicketShareLinks.parsePnr("https://itsluminous.github.io/ClearTravel/pnr/85536749")).isNull()
        assertThat(TicketShareLinks.parsePnr("cleartravel://pnr/ABCDEFGHIJ")).isNull()
        assertThat(TicketShareLinks.parsePnr("not a uri at all ::")).isNull()
        assertThat(TicketShareLinks.parsePnr(null)).isNull()
    }
}
