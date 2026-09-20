package com.itsluminous.cleartravel.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/** Body text is never below 16sp (accessibility floor for all body/label sizes). */
const val MIN_BODY_FONT_SIZE_SP = 16

/**
 * Typography tuned for readability: every body and label style is at least
 * [MIN_BODY_FONT_SIZE_SP]. Everything else follows Material 3 defaults.
 */
internal val ClearTravelTypography =
    Typography().let { base ->
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodySmall = base.bodySmall.copy(fontSize = 16.sp, lineHeight = 24.sp),
            labelLarge = base.labelLarge.copy(fontSize = 16.sp),
        )
    }
