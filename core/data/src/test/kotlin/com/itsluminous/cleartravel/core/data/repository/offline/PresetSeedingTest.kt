package com.itsluminous.cleartravel.core.data.repository.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.preset.AssetBuiltInPresetSource
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetsParser
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
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
    private lateinit var context: Context
    private lateinit var db: ClearTravelDatabase
    private lateinit var repository: OfflineChecklistPresetRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    fun `ADR-040 - seeded item ids are derived from preset id and index, identical across installs`() =
        runTest {
            repository.seedBuiltInPresets()
            val otherDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val other = OfflineChecklistPresetRepository(otherDb.checklistPresetDao(), AssetBuiltInPresetSource(context), clock)
            other.seedBuiltInPresets()

            repository.observePresets().first().forEach { preset ->
                val ids = repository.getItems(preset.id).map { it.id }
                assertThat(ids).isEqualTo(other.getItems(preset.id).map { it.id })
                assertThat(ids).isEqualTo(ids.indices.map { OfflineChecklistPresetRepository.seededItemId(preset.id, it) })
                ids.forEach { assertThat(EntityIds.isValid(it)).isTrue() }
            }
            assertThat(
                repository
                    .observePresets()
                    .first()
                    .flatMap { repository.getItems(it.id) }
                    .map { it.id }
                    .toSet(),
            ).hasSize(repository.observePresets().first().sumOf { repository.getItems(it.id).size })
            otherDb.close()
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

    @Test
    fun `re-seeding keeps item edits on a built-in preset (renamed, removed, added, reordered)`() =
        runTest {
            repository.seedBuiltInPresets()
            val trek = repository.observePresets().first().first { it.name == "Trek" }
            val original = repository.getItems(trek.id)
            val first = original[0]
            val second = original[1]
            // Rename the first item, delete the second, append a new one, and swap the
            // order of the two remaining leading rows (ADR-021 editing paths).
            repository.saveItems(listOf(first.copy(text = "Edited ${first.text}")))
            repository.deleteItem(second.id)
            repository.saveItems(
                listOf(ChecklistPresetItem(presetId = trek.id, text = "User added", sortOrder = original.size)),
            )
            val live = repository.getItems(trek.id)
            repository.saveItems(
                listOf(live[0].copy(sortOrder = live[1].sortOrder), live[1].copy(sortOrder = live[0].sortOrder)),
            )
            val expected = repository.getItems(trek.id).map { it.text }
            assertThat(expected.take(2)).containsExactly(live[1].text, live[0].text).inOrder()

            repository.seedBuiltInPresets()

            val afterItems = repository.getItems(trek.id)
            assertThat(afterItems.map { it.text }).containsExactlyElementsIn(expected).inOrder()
            assertThat(afterItems.map { it.text }).contains("Edited ${first.text}")
            assertThat(afterItems.map { it.text }).doesNotContain(second.text)
            assertThat(afterItems.map { it.text }).contains("User added")
            assertThat(afterItems).hasSize(original.size)
            assertThat(repository.observePresets().first().count { it.id == trek.id }).isEqualTo(1)
        }
}
