package com.itsluminous.cleartravel.feature.itinerary.di

import com.itsluminous.cleartravel.feature.itinerary.intake.HttpMapsLinkResolver
import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Feature-local bindings (ADR-029 part D: the Maps short-link resolver). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ItineraryModule {
    @Binds
    abstract fun bindMapsLinkResolver(impl: HttpMapsLinkResolver): MapsLinkResolver
}
