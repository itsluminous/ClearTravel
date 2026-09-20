package com.itsluminous.cleartravel.core.data.repository.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.preset.AssetBuiltInPresetSource
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetsParser
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

/**
 * Seeds the REAL asset (`presets/builtin-presets.json`) into an in-memory database —
 * this doubles as the data-file's fixture test (ADR-003): a malformed or incomplete
 * asset fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetSeedingTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var repository: OfflineChecklistPresetRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = inMemoryDatabase(context)
        repository = OfflineChecklistPresetRepository(db.checklistPresetDao(), AssetBuiltInPresetSource(context), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `asset parses with valid fixed ids and non-empty unique items`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val content =
            context.assets
                .open(BuiltInPresetsParser.ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }

        val file = BuiltInPresetsParser.parse(content)

        assertThat(file.version).isEqualTo(1)
        assertThat(file.presets.map { it.name })
            .containsExactly("Domestic trip", "International travel", "Trek", "Medicines")
        assertThat(file.presets.map { it.id }.toSet()).hasSize(file.presets.size)
        file.presets.forEach { preset ->
            assertThat(EntityIds.isValid(preset.id)).isTrue()
            assertThat(preset.items).isNotEmpty()
            assertThat(preset.items.toSet()).hasSize(preset.items.size)
        }
    }

    @Test
    fun `seeding creates all built-in presets with their items`() =
        runTest {
            repository.seedBuiltInPresets()

            val presets = repository.observePresets().first()
            assertThat(presets.map { it.name })
                .containsExactly("Domestic trip", "International travel", "Trek", "Medicines")
            assertThat(presets.all { it.builtIn }).isTrue()
            presets.forEach { preset ->
                assertThat(repository.getItems(preset.id)).isNotEmpty()
            }
        }

    @Test
    fun `seeding twice never duplicates presets or items`() =
        runTest {
            repository.seedBuiltInPresets()
            val firstItems = repository.observePresets().first().associateWith { repository.getItems(it.id).size }

            repository.seedBuiltInPresets()

            val presets = repository.observePresets().first()
            assertThat(presets).hasSize(4)
            firstItems.forEach { (preset, count) ->
                assertThat(repository.getItems(preset.id)).hasSize(count)
            }
        }

    @Test
    fun `re-seeding does not resurrect a built-in preset the user deleted`() =
        runTest {
            repository.seedBuiltInPresets()
            val trek = repository.observePresets().first().first { it.name == "Trek" }
            repository.deletePreset(trek.id)

            repository.seedBuiltInPresets()

            assertThat(repository.observePresets().first().map { it.name }).doesNotContain("Trek")
        }

    @Test
    fun `re-seeding does not overwrite a built-in preset the user edited`() =
        runTest {
            repository.seedBuiltInPresets()
            val trek = repository.observePresets().first().first { it.name == "Trek" }
            repository.save(trek.copy(name = "My trek"))

            repository.seedBuiltInPresets()

            val names = repository.observePresets().first().map { it.name }
            assertThat(names).contains("My trek")
            assertThat(names).doesNotContain("Trek")
        }
}
