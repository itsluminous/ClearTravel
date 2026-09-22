package com.itsluminous.cleartravel.core.data.di

import com.itsluminous.cleartravel.core.data.crosstab.InMemoryJourneyAddRequestBus
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequestBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Cross-tab seams (ADR-028). Deliberately separate from [RepositoryModule] so the
 * hermetic e2e suite — which replaces the repository module — keeps the real bus:
 * the tab hand-off has no I/O and needs no fake.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrossTabModule {
    @Binds
    @Singleton
    abstract fun bindJourneyAddRequestBus(impl: InMemoryJourneyAddRequestBus): JourneyAddRequestBus
}
