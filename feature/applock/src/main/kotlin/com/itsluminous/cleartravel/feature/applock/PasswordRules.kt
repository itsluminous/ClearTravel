package com.itsluminous.cleartravel.feature.applock

/** Coarse password strength shown as a hint while creating a password (ADR-031). */
enum class PasswordStrength { TOO_SHORT, WEAK, FAIR, STRONG }

/**
 * Pure strength heuristic — length first (the only thing PBKDF2 cannot compensate
 * for), then character variety. Deliberately simple and explainable; it is a hint,
 * not a gate beyond [MIN_LENGTH].
 */
object PasswordRules {
    const val MIN_LENGTH = 8

    fun strength(password: String): PasswordStrength {
        if (password.length < MIN_LENGTH) return PasswordStrength.TOO_SHORT
        val classes =
            listOf(
                password.any(Char::isLowerCase),
                password.any(Char::isUpperCase),
                password.any(Char::isDigit),
                password.any { !it.isLetterOrDigit() },
            ).count { it }
        return when {
            password.length >= 16 && classes >= 2 -> PasswordStrength.STRONG
            password.length >= 12 && classes >= 3 -> PasswordStrength.STRONG
            password.length >= 12 || classes >= 3 -> PasswordStrength.FAIR
            else -> PasswordStrength.WEAK
        }
    }

    /** Null when the pair is acceptable for setup, else the reason. */
    fun setupError(
        password: String,
        confirm: String,
    ): SetupError? =
        when {
            password.length < MIN_LENGTH -> SetupError.TOO_SHORT
            password != confirm -> SetupError.MISMATCH
            else -> null
        }
}

enum class SetupError { TOO_SHORT, MISMATCH }
