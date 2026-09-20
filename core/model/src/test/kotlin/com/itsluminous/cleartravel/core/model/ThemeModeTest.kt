package com.itsluminous.cleartravel.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `fromStorage round-trips every mode`() {
        ThemeMode.entries.forEach { mode ->
            assertThat(ThemeMode.fromStorage(mode.storageValue)).isEqualTo(mode)
        }
    }

    @Test
    fun `fromStorage falls back to SYSTEM for unknown or absent values`() {
        assertThat(ThemeMode.fromStorage(null)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStorage("")).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStorage("sepia")).isEqualTo(ThemeMode.SYSTEM)
    }
}
