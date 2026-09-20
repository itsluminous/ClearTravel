package com.itsluminous.cleartravel.feature.flights.di

import android.content.Context
import com.itsluminous.cleartravel.core.scrape.AssetRuleSource
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.feature.flights.checkin.AssetCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.form.BoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.form.OcrBoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusAlerts
import com.itsluminous.cleartravel.feature.flights.status.NotifierFlightStatusAlerts
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    abstract fun bindFlightStatusAlerts(impl: NotifierFlightStatusAlerts): FlightStatusAlerts
}

@Module
@InstallIn(SingletonComponent::class)
object FlightsProvidersModule {
    /** The scrape-rule registry over the asset-bundled rule files (ADR-008). */
    @Provides
    @Singleton
    fun provideRuleRegistry(
        @ApplicationContext context: Context,
    ): RuleRegistry = RuleRegistry(AssetRuleSource(context))
}
