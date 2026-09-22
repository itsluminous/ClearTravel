package com.itsluminous.cleartravel.feature.menu

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.backup.BackupEntries
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import org.junit.Test
import java.time.Instant

class ImportPreviewSummaryTest {
    private fun preview(counts: Map<String, Int>) =
        ImportPreview(schemaVersion = 2, appVersion = "0.1.0", createdAt = Instant.EPOCH, entityCounts = counts)

    @Test
    fun `counts travel documents separately from booking attachments`() {
        val summary =
            ImportPreviewSummary.of(
                preview(
                    mapOf(
                        BackupEntries.KEY_TRIPS to 2,
                        BackupEntries.KEY_TRAIN_TICKETS to 3,
                        BackupEntries.KEY_FLIGHT_JOURNEYS to 1,
                        BackupEntries.KEY_CHECKLISTS to 4,
                        BackupEntries.KEY_TRAVEL_DOCUMENTS to 5,
                        BackupEntries.KEY_ATTACHMENTS to 0,
                    ),
                ),
            )

        assertThat(summary).isEqualTo(ImportPreviewSummary(trips = 2, journeys = 4, checklists = 4, documents = 5, attachments = 0))
    }

    @Test
    fun `missing entity files count as zero`() {
        assertThat(ImportPreviewSummary.of(preview(emptyMap())))
            .isEqualTo(ImportPreviewSummary(trips = 0, journeys = 0, checklists = 0, documents = 0, attachments = 0))
    }
}
