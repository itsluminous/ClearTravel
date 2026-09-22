package com.itsluminous.cleartravel.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class BackupScheduleTest {
    @Test
    fun `storage values round-trip and unknown values fall back to off`() {
        for (schedule in BackupSchedule.entries) {
            assertThat(BackupSchedule.fromStorage(schedule.storageValue)).isEqualTo(schedule)
        }
        assertThat(BackupSchedule.fromStorage(null)).isEqualTo(BackupSchedule.OFF)
        assertThat(BackupSchedule.fromStorage("hourly")).isEqualTo(BackupSchedule.OFF)
        assertThat(BackupSchedule.DEFAULT).isEqualTo(BackupSchedule.OFF)
    }

    @Test
    fun `only off has no period, the rest map to 1, 7 and 30 days`() {
        assertThat(BackupSchedule.OFF.period).isNull()
        assertThat(BackupSchedule.DAILY.period).isEqualTo(Duration.ofDays(1))
        assertThat(BackupSchedule.WEEKLY.period).isEqualTo(Duration.ofDays(7))
        assertThat(BackupSchedule.MONTHLY.period).isEqualTo(Duration.ofDays(30))
    }
}
