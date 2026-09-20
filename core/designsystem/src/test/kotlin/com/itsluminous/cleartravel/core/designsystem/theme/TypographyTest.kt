package com.itsluminous.cleartravel.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TypographyTest {
    @Test
    fun `body and label styles never fall below the 16sp accessibility floor`() {
        val styles =
            mapOf(
                "bodyLarge" to ClearTravelTypography.bodyLarge,
                "bodyMedium" to ClearTravelTypography.bodyMedium,
                "bodySmall" to ClearTravelTypography.bodySmall,
                "labelLarge" to ClearTravelTypography.labelLarge,
            )

        styles.forEach { (name, style) ->
            assertThat(style.fontSize.isSp).isTrue()
            assertWithMessage(name)
                .that(style.fontSize.value)
                .isAtLeast(MIN_BODY_FONT_SIZE_SP.toFloat())
        }
    }
}
