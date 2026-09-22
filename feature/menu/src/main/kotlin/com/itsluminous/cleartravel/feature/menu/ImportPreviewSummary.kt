package com.itsluminous.cleartravel.feature.menu

import com.itsluminous.cleartravel.core.data.backup.BackupEntries
import com.itsluminous.cleartravel.core.data.backup.ImportPreview

/**
 * The headline counts of the import confirmation dialog, derived from the manifest's
 * per-entity-file counts (tombstones included, like `ImportPreview.totalRows`).
 * Journeys = train tickets + flight journeys; travel documents (ADR-027) are counted
 * on their own — they are bundled files too but live in their own entity file, so
 * the old "Attachments: 0" for a backup full of documents was misleading.
 */
data class ImportPreviewSummary(
    val trips: Int,
    val journeys: Int,
    val checklists: Int,
    val documents: Int,
    val attachments: Int,
) {
    companion object {
        fun of(preview: ImportPreview): ImportPreviewSummary =
            ImportPreviewSummary(
                trips = preview.count(BackupEntries.KEY_TRIPS),
                journeys = preview.count(BackupEntries.KEY_TRAIN_TICKETS) + preview.count(BackupEntries.KEY_FLIGHT_JOURNEYS),
                checklists = preview.count(BackupEntries.KEY_CHECKLISTS),
                documents = preview.count(BackupEntries.KEY_TRAVEL_DOCUMENTS),
                attachments = preview.count(BackupEntries.KEY_ATTACHMENTS),
            )

        private fun ImportPreview.count(key: String): Int = entityCounts[key] ?: 0
    }
}
