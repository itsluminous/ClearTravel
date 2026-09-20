package com.itsluminous.cleartravel.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/**
 * Pure last-write-wins merge tests (ADR-015). [BackupMerger] is generic over
 * [com.itsluminous.cleartravel.core.model.SyncableEntity]; [Fixtures.trip] stands in
 * for all 11 entity types.
 */
class BackupMergerTest {
    private val base = Fixtures.NOW

    @Test
    fun `backup-only row is inserted verbatim`() {
        val backupRow = Fixtures.trip(name = "From backup", updatedAt = base)

        val plan = BackupMerger.merge(local = emptyList(), backup = listOf(backupRow))

        assertThat(plan.toWrite).containsExactly(backupRow)
        assertThat(plan.summary).isEqualTo(MergeSummary(inserted = 1, updated = 0, skipped = 0))
    }

    @Test
    fun `local-only row is never touched`() {
        val localRow = Fixtures.trip(name = "Local only")

        val plan = BackupMerger.merge(local = listOf(localRow), backup = emptyList())

        assertThat(plan.toWrite).isEmpty()
        assertThat(plan.summary).isEqualTo(MergeSummary.ZERO)
    }

    @Test
    fun `newer backup row wins entirely`() {
        val local = Fixtures.trip(id = Fixtures.FIXED_ID, name = "Old local", updatedAt = base)
        val backup = Fixtures.trip(id = Fixtures.FIXED_ID, name = "New backup", updatedAt = base.plusSeconds(60))

        val plan = BackupMerger.merge(listOf(local), listOf(backup))

        assertThat(plan.toWrite).containsExactly(backup)
        assertThat(plan.summary).isEqualTo(MergeSummary(inserted = 0, updated = 1, skipped = 0))
    }

    @Test
    fun `newer local row wins and backup row is skipped`() {
        val local = Fixtures.trip(id = Fixtures.FIXED_ID, name = "New local", updatedAt = base.plusSeconds(60))
        val backup = Fixtures.trip(id = Fixtures.FIXED_ID, name = "Old backup", updatedAt = base)

        val plan = BackupMerger.merge(listOf(local), listOf(backup))

        assertThat(plan.toWrite).isEmpty()
        assertThat(plan.summary).isEqualTo(MergeSummary(inserted = 0, updated = 0, skipped = 1))
    }

    @Test
    fun `equal timestamps keep local - repeated import is idempotent`() {
        val local = Fixtures.trip(id = Fixtures.FIXED_ID, name = "Same", updatedAt = base)
        val backup = local.copy()

        val plan = BackupMerger.merge(listOf(local), listOf(backup))

        assertThat(plan.toWrite).isEmpty()
        assertThat(plan.summary).isEqualTo(MergeSummary(inserted = 0, updated = 0, skipped = 1))
    }

    @Test
    fun `newer backup tombstone deletes a local live row`() {
        val live = Fixtures.trip(id = Fixtures.FIXED_ID, updatedAt = base, deletedAt = null)
        val tombstone =
            Fixtures.trip(
                id = Fixtures.FIXED_ID,
                updatedAt = base.plusSeconds(30),
                deletedAt = base.plusSeconds(30),
            )

        val plan = BackupMerger.merge(listOf(live), listOf(tombstone))

        assertThat(plan.toWrite.single().deletedAt).isEqualTo(base.plusSeconds(30))
        assertThat(plan.summary.updated).isEqualTo(1)
    }

    @Test
    fun `newer local edit survives an older backup tombstone`() {
        val live = Fixtures.trip(id = Fixtures.FIXED_ID, updatedAt = base.plusSeconds(60), deletedAt = null)
        val tombstone = Fixtures.trip(id = Fixtures.FIXED_ID, updatedAt = base, deletedAt = base)

        val plan = BackupMerger.merge(listOf(live), listOf(tombstone))

        assertThat(plan.toWrite).isEmpty()
        assertThat(plan.summary.skipped).isEqualTo(1)
    }

    @Test
    fun `mixed batch produces correct per-row accounting`() {
        val sharedNewer = Fixtures.trip(updatedAt = base)
        val sharedOlder = Fixtures.trip(updatedAt = base.plusSeconds(60))
        val localOnly = Fixtures.trip()
        val backupOnly = Fixtures.trip()
        val local = listOf(sharedNewer, sharedOlder, localOnly)
        val backup =
            listOf(
                sharedNewer.copy(name = "Backup wins", updatedAt = base.plusSeconds(10)),
                sharedOlder.copy(name = "Backup loses", updatedAt = base),
                backupOnly,
            )

        val plan = BackupMerger.merge(local, backup)

        assertThat(plan.summary).isEqualTo(MergeSummary(inserted = 1, updated = 1, skipped = 1))
        assertThat(plan.toWrite.map { it.id }).containsExactly(sharedNewer.id, backupOnly.id)
    }
}
