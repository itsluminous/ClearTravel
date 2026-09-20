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

/** The ADR-002 sync columns of one row, read raw — visible even when tombstoned. */
data class SyncColumns(
    val updatedAtEpochMillis: Long?,
    val deletedAtEpochMillis: Long?,
)

/**
 * Reads a row's `updated_at`/`deleted_at` straight off the table, bypassing DAO
 * tombstone filters — the only way tests can assert that a soft delete both hid the
 * row AND bumped `updated_at` (ADR-002). Returns null when the row doesn't exist.
 */
fun RoomDatabase.syncColumns(
    table: String,
    id: String,
): SyncColumns? =
    query("SELECT updated_at, deleted_at FROM $table WHERE id = ?", arrayOf(id)).use { cursor ->
        if (!cursor.moveToFirst()) return null
        SyncColumns(
            updatedAtEpochMillis = if (cursor.isNull(0)) null else cursor.getLong(0),
            deletedAtEpochMillis = if (cursor.isNull(1)) null else cursor.getLong(1),
        )
    }
