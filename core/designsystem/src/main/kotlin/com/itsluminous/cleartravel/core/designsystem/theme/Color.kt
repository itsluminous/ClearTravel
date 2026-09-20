package com.itsluminous.cleartravel.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Curated fallback palette derived from the travel-blue seed #0B57D0.
 * Used everywhere dynamic color is unavailable (pre-Android 12).
 */

/** The brand seed color — exposed so tests and the splash background stay in sync. */
val SeedColor = Color(0xFF0B57D0)

internal val LightColorScheme =
    lightColorScheme(
        primary = SeedColor,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3FD),
        onPrimaryContainer = Color(0xFF041E49),
        secondary = Color(0xFF575E71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDBE2F9),
        onSecondaryContainer = Color(0xFF141B2C),
        tertiary = Color(0xFF715573),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFBD7FC),
        onTertiaryContainer = Color(0xFF29132D),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFDFBFF),
        onBackground = Color(0xFF1A1B1F),
        surface = Color(0xFFFDFBFF),
        onSurface = Color(0xFF1A1B1F),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        outline = Color(0xFF74777F),
    )

internal val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF08428E),
        onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFFBFC6DC),
        onSecondary = Color(0xFF293041),
        secondaryContainer = Color(0xFF3F4759),
        onSecondaryContainer = Color(0xFFDBE2F9),
        tertiary = Color(0xFFDEBCDF),
        onTertiary = Color(0xFF402843),
        tertiaryContainer = Color(0xFF583E5B),
        onTertiaryContainer = Color(0xFFFBD7FC),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = Color(0xFF1A1B1F),
        onBackground = Color(0xFFE3E2E6),
        surface = Color(0xFF1A1B1F),
        onSurface = Color(0xFFE3E2E6),
        surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099),
    )
