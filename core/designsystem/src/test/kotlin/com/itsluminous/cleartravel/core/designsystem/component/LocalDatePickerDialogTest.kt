package com.itsluminous.cleartravel.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The Material date picker reports UTC-midnight millis; the conversion must round-trip exactly. */
class LocalDatePickerDialogTest {
    @Test
    fun `picker millis at UTC midnight map to that calendar day`() {
        val day = LocalDate.of(2026, 9, 22)
        val millis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertThat(localDateFromPickerMillis(millis)).isEqualTo(day)
    }

    @Test
    fun `conversion ignores the device zone`() {
        // 2030-01-01T00:00Z is still Dec 31 in every western zone — the picker day must win.
        val millis =
            LocalDate
                .of(2030, 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        assertThat(localDateFromPickerMillis(millis)).isEqualTo(LocalDate.of(2030, 1, 1))
        assertThat(localDateFromPickerMillis(millis - 1)).isEqualTo(LocalDate.of(2029, 12, 31))
    }
}
