package com.itsluminous.cleartravel

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner for the e2e suite: swaps [ClearTravelApplication] for
 * [HiltTestApplication] so `@HiltAndroidTest` classes can replace modules (the
 * in-memory database from `TestDatabaseModule`). Referenced from
 * `app/build.gradle.kts`' `testInstrumentationRunner`.
 */
@Suppress("unused") // Instantiated reflectively by the instrumentation framework.
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
