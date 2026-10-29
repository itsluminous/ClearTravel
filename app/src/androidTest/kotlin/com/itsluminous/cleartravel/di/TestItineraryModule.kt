package com.itsluminous.cleartravel.di

import com.itsluminous.cleartravel.feature.itinerary.di.ItineraryModule
import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkResolver
import com.itsluminous.cleartravel.feature.itinerary.logic.GeocodedPlace
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.place.DefaultPlaceLocator
import com.itsluminous.cleartravel.feature.itinerary.place.NetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.place.PlaceLocator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * ADR-048: hermetic stand-ins for the network legs of the place-location ladder. The
 * REAL ladder ([DefaultPlaceLocator]: link parse → geocode candidates → plus code) runs
 * over (a) a resolver that returns the redirects captured from the user's five
 * `maps.app.goo.gl` links and (b) a geocoder scripted with the answers Nominatim gave
 * for them on 2026-10-28 — Chota Imambada deliberately has none, so one place stays
 * unplaced. Everything else about the itinerary module is bound as in production.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ItineraryModule::class])
abstract class TestItineraryModule {
    @Binds
    abstract fun bindPlaceLocator(impl: DefaultPlaceLocator): PlaceLocator

    companion object {
        @Provides
        @Singleton
        fun provideResolver(): MapsLinkResolver = MapsLinkResolver { shortUrl -> E2eLucknow.redirects[shortUrl] }

        @Provides
        @Singleton
        fun provideGeocoder(): PlaceGeocoder =
            PlaceGeocoder { query -> E2eLucknow.geocodes[query]?.let { listOf(GeocodedPlace(query, it)) }.orEmpty() }

        @Provides
        @Singleton
        fun provideNetwork(): NetworkAvailability = NetworkAvailability { true }
    }
}

/** The user's Lucknow trip: real short links → real redirects (curl, 2026-10-28) → real Nominatim answers. */
object E2eLucknow {
    private const val PLACE = "https://www.google.com/maps/place/"
    private const val FTID = "/data=!4m2!3m1!1s0x399bfdf0522e588b:0xb2370b12db96ece3!18m1!1e1?utm_source=mstt_1&entry=gps"

    val redirects: Map<String, String> =
        mapOf(
            "https://maps.app.goo.gl/nn5U4xiKj4JyYWQMA" to
                "${PLACE}Bada+Imambara+(Main+Building),+Machchhi+Bhavan,+Lucknow,+Uttar+Pradesh+226003$FTID",
            "https://maps.app.goo.gl/b7bzSE2NgKYHE8kc7" to
                "${PLACE}Chota+Imambada,+447,+Husainabad,+Lucknow,+Uttar+Pradesh+226003$FTID",
            "https://maps.app.goo.gl/nJaTk882BvsoPT5QA" to
                "${PLACE}Royal+Cafe(Hazratganj),+51,+Mahatma+Gandhi+Marg,+Sushanpura,+Hazratganj,+Lucknow,+Uttar+Pradesh+226001$FTID",
            "https://maps.app.goo.gl/BERUkuN3K4Ep9Q3G8" to
                "${PLACE}VW6G%2B7P3+The+Residency,+Lucknow,+Mahatma+Gandhi+Marg,+Deep+Manak+Nagar,+Kaiser+Bagh,+Lucknow,+Uttar+Pradesh+226018$FTID",
            "https://maps.app.goo.gl/SPre92rQxfbVNLoY7" to
                "${PLACE}Rumi+Darwaza,+17%2F11+Hussainabad+Road,+Lajpat+Nagar+Colony,+Lajpat+Nagar,+Machchhi+Bhavan,+Lucknow,+Uttar+Pradesh+226003$FTID",
        )

    val geocodes: Map<String, MapPoint> =
        mapOf(
            "Bada Imambara, Lucknow" to MapPoint(26.8696584, 80.9135101),
            "Royal Cafe, Lucknow" to MapPoint(26.8560964, 80.9451776),
            "The Residency, Lucknow" to MapPoint(26.8609040, 80.9258774),
            "Rumi Darwaza, Lucknow" to MapPoint(26.8712234, 80.9122124),
            "Lucknow, Uttar Pradesh" to MapPoint(26.8381000, 80.9346001),
        )

    /** The trip id inside the share payload (ids are the sender's, ADR-039). */
    const val TRIP_ID = "344fd1ce-e7b7-40ee-9fee-607768a6870c"

    /** The user's share link blob (gzip + base64url JSON, v=1): the five places, names only, no coordinates. */
    const val SHARE_BLOB =
        "vZRLbtswFEW3QqiTFBVjSqJIybM6dn6ODdv5ISg6eCKpj21RhiQ7cYKsIeOOuo3uql1C6cT5FWkQBGgngnRJke_c-8gra2E1HdvKpNW0PEpj6QiFFY84pkQpHMbmwQjnLAAWcCIs29KQKzP7YC4mujg3glRVnWmos0I_06sayroN9Wq2S1yGHYLd0AwoLf-QPcfIoliospMX48yM_Pp-8-3nj5t7eauYFqWRP2xvk5bP28QMZLXKK6v55equfMVV5As_xkEcAKZhyHAIvsIQCy4ixX2qvFW1LxS0RmqBBLSXQx5BCWijB5lGrXk2lZlOPtqoByIVaZqhVgoL0DZao9rouDaoaFCCsSJFrssIWW0lzFZJUS7N0lWWpLWRppmemM-0rmdVs9HIYVZtwmy2mRTFZjJtaO0f04usO6b7y7PTYe-zdW2v8XgoYhL5Eoc-czENnBADZwRHxOBSIhzuwl_wilKqck-3YXkb9pp2Ky3qe1wJNqKU22h3XhlqMMq_wIt4dHnYcftJ92y3E0wEf8RT3I0VRA72ndDHlIKLwWcMCyKUAnBlxOUb8NwHvFGxhCnaglht7MJlCXUCemxS9J1VkinUOaAd0NLk2YMysdHhvEpBz-al8eLxj9dtcN6X8j4cTYLAbS2qYnDkD5-kTCghUeQoTJnyMJVCmICB40AoAoE5jr6n3mCD92DDySnb-cQHHjpKFRqpKpNKi-UTqhe9aCs1M68aJqgPCZQ26kJWqRK1IElftcQJ3mVJqzM6nsz7Xpd2ZuHQ2wmeWCJ4TIAyzKIwxDTiHIcyCjFnwEH5MuaMvcES-tgZ8zxDbSjP4dJE7fCG46z6_r7x0ai47X4Yz6C-w0er60cvn4v_5UI4HJQqdMvhRRyd9A-KM3Nivl7_Bg"
}
