package com.itsluminous.cleartravel.feature.applock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordRulesTest {
    @Test
    fun strength_isLengthFirstThenVariety() {
        assertThat(PasswordRules.strength("short1!")).isEqualTo(PasswordStrength.TOO_SHORT)
        assertThat(PasswordRules.strength("abcdefgh")).isEqualTo(PasswordStrength.WEAK)
        assertThat(PasswordRules.strength("abcdefghijkl")).isEqualTo(PasswordStrength.FAIR) // 12 lower only
        assertThat(PasswordRules.strength("Abcdef1!")).isEqualTo(PasswordStrength.FAIR) // 8 chars, 4 classes
        assertThat(PasswordRules.strength("Abcdefghijk1")).isEqualTo(PasswordStrength.STRONG) // 12, 3 classes
        assertThat(PasswordRules.strength("correct horse battery")).isEqualTo(PasswordStrength.STRONG) // 21, 2 classes
    }

    @Test
    fun setupError_requiresMinLengthAndMatch() {
        assertThat(PasswordRules.setupError("1234567", "1234567")).isEqualTo(SetupError.TOO_SHORT)
        assertThat(PasswordRules.setupError("12345678", "12345679")).isEqualTo(SetupError.MISMATCH)
        assertThat(PasswordRules.setupError("12345678", "12345678")).isNull()
    }
}
