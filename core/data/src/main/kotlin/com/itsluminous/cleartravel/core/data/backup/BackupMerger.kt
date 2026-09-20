package com.itsluminous.cleartravel.core.data.backup

import com.itsluminous.cleartravel.core.model.SyncableEntity

/**
 * The pure last-write-wins merge algorithm (ADR-015). Generic over any
 * [SyncableEntity] so one implementation — and one test suite — covers all 11 entity
 * types identically.
 *
 * Invariants (spec feature 6):
 * - **Merge, never wipe**: local rows absent from the backup are never touched.
 * - **Backup-only rows insert AS-IS**: id, `updatedAt` and tombstone state preserved.
 * - **Same-id rows resolve entirely to the newer `updatedAt`** — including tombstone
 *   state, so a newer backup tombstone deletes a local live row and a newer local
 *   edit survives an older backup tombstone.
 * - **Ties keep local**: equal `updatedAt` counts as `skipped`, which is what makes a
 *   repeated import of the same file a no-op (idempotent by UUID).
 */
object BackupMerger {
    /** The rows to write plus per-row accounting for the result snackbar. */
    data class Plan<T : SyncableEntity>(
        /** Rows the caller must upsert VERBATIM (timestamp-preserving upsert). */
        val toWrite: List<T>,
        val summary: MergeSummary,
    )

    fun <T : SyncableEntity> merge(
        local: List<T>,
        backup: List<T>,
    ): Plan<T> {
        val localById = local.associateBy { it.id }
        val toWrite = mutableListOf<T>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        for (backupRow in backup) {
            val localRow = localById[backupRow.id]
            when {
                localRow == null -> {
                    toWrite += backupRow
                    inserted++
                }
                backupRow.updatedAt.isAfter(localRow.updatedAt) -> {
                    toWrite += backupRow
                    updated++
                }
                else -> skipped++
            }
        }
        return Plan(toWrite, MergeSummary(inserted, updated, skipped))
    }
}
