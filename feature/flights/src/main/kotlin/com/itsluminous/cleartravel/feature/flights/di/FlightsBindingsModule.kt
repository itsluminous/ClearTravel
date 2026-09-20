package com.itsluminous.cleartravel.feature.flights.di

import com.itsluminous.cleartravel.feature.flights.checkin.AssetCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.form.BoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.form.BookingConfirmationImporter
import com.itsluminous.cleartravel.feature.flights.form.OcrBoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.form.OcrBookingConfirmationImporter
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusAlerts
import com.itsluminous.cleartravel.feature.flights.status.NotifierFlightStatusAlerts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FlightsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindCheckInRuleSource(impl: AssetCheckInRuleSource): CheckInRuleSource

    @Binds
    @Singleton
    abstract fun bindBoardingPassImporter(impl: OcrBoardingPassImporter): BoardingPassImporter

    @Binds
    @Singleton
    abstract fun bindBookingConfirmationImporter(impl: OcrBookingConfirmationImporter): BookingConfirmationImporter

    @Binds
    @Singleton
    abstract fun bindFlightStatusAlerts(impl: NotifierFlightStatusAlerts): FlightStatusAlerts
}

// The RuleRegistry @Provides that used to live here (FlightsProvidersModule) was
// hoisted to core:scrape's ScrapeModule — the registry is shared with feature:trains
// (ADR-013 integration note; ADR-014).
