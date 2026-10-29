package com.itsluminous.cleartravel.feature.itinerary.di

import android.content.Context
import com.itsluminous.cleartravel.feature.itinerary.intake.HttpMapsLinkResolver
import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkResolver
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.logic.platformPlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.place.AndroidNetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.place.DefaultPlaceLocator
import com.itsluminous.cleartravel.feature.itinerary.place.NetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.place.PlaceLocator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Feature-local bindings: the Maps short-link resolver (ADR-029 part D) and the
 * place-location ladder (ADR-048: geocoder, locator, network seam).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ItineraryModule {
    @Binds
    abstract fun bindMapsLinkResolver(impl: HttpMapsLinkResolver): MapsLinkResolver

    @Binds
    abstract fun bindPlaceLocator(impl: DefaultPlaceLocator): PlaceLocator

    @Binds
    abstract fun bindNetworkAvailability(impl: AndroidNetworkAvailability): NetworkAvailability

    companion object {
        /** The same Nominatim-first geocoder the location picker uses (ADR-035), shared app-wide. */
        @Provides
        @Singleton
        fun providePlaceGeocoder(
            @ApplicationContext context: Context,
        ): PlaceGeocoder = platformPlaceGeocoder(context)
    }
}
