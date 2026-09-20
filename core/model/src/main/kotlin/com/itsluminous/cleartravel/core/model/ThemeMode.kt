package com.itsluminous.cleartravel.core.model

/**
 * App theme preference. Persisted as [storageValue] in the settings DataStore
 * (milestone 2 wires the setting UI; the skeleton hardcodes [SYSTEM]).
 */
enum class ThemeMode(
    val storageValue: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [SYSTEM]. */
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}
