package com.itsluminous.cleartravel.core.testing

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher for the duration of a test —
 * required by any test touching ViewModels or `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Generic in-memory Room database for DAO/repository tests (Robolectric or
 * instrumented): `inMemoryDatabase<ClearTravelDatabase>(context)`.
 */
inline fun <reified T : RoomDatabase> inMemoryDatabase(context: Context): T =
    Room
        .inMemoryDatabaseBuilder(context, T::class.java)
        .allowMainThreadQueries()
        .build()
