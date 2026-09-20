package com.itsluminous.cleartravel.core.scrape.di

import android.content.Context
import com.itsluminous.cleartravel.core.scrape.AssetRuleSource
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt provisioning for the scrape engine (ADR-008). [RuleRegistry] is shared by
 * `feature:trains` (indianrail-pnr) and `feature:flights` (airline status rules), so
 * the single `@Provides` lives here rather than in either feature — hoisted from
 * `feature:flights`' `FlightsProvidersModule` per the ADR-013 integration note.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScrapeModule {
    /** The scrape-rule registry over the asset-bundled rule files. */
    @Provides
    @Singleton
    fun provideRuleRegistry(
        @ApplicationContext context: Context,
    ): RuleRegistry = RuleRegistry(AssetRuleSource(context))
}
