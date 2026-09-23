package com.itsluminous.cleartravel.ui.intake

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/** ADR-029 part D: one `text/plain` share filter, two destinations. */
class SharedTextRouteTest {
    @Test
    fun `a Google Maps link routes to the maps intake`() {
        val text = "Hampi Bazaar\nhttps://maps.app.goo.gl/AbCdEf"

        assertThat(routeSharedText(text)).isEqualTo(SharedTextRoute.MapsLink(text))
        assertThat(routeSharedText("https://www.google.com/maps/place/Taj+Mahal/@27.17,78.04,17z"))
            .isInstanceOf(SharedTextRoute.MapsLink::class.java)
    }

    @Test
    fun `IRCTC text keeps routing to the train form`() {
        val sms = "PNR:1234567890,TRN:12627,DOJ:20-10-26,SL,SBC-NDLS,Dep:20:00"

        assertThat(routeSharedText(sms)).isEqualTo(SharedTextRoute.TrainText(sms))
    }

    @Test
    fun `other links are not mistaken for maps links`() {
        assertThat(routeSharedText("https://www.irctc.co.in/nget/train-search"))
            .isInstanceOf(SharedTextRoute.TrainText::class.java)
    }

    @Test
    fun `a Clear Travel share link inside forwarded text routes to the share import`() {
        val url = (ShareLinkCodec.buildShareUrl(SharePayloadMappers.toPayload(Fixtures.trip(), emptyList())) as ShareUrlResult.Ok).url
        val text = "Here's my trip \"Tokyo\" - open it in Clear Travel: $url"

        assertThat(routeSharedText(text)).isEqualTo(SharedTextRoute.ShareLink(url))
        // Even next to a Maps link, the app's own link wins.
        assertThat(routeSharedText("$text https://maps.app.goo.gl/AbCdEf")).isEqualTo(SharedTextRoute.ShareLink(url))
    }

    @Test
    fun `blank text routes nowhere`() {
        assertThat(routeSharedText(null)).isNull()
        assertThat(routeSharedText("   ")).isNull()
    }
}
