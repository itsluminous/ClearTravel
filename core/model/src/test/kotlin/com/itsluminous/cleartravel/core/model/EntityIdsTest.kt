package com.itsluminous.cleartravel.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntityIdsTest {
    @Test
    fun `newId returns a canonical UUID`() {
        val id = EntityIds.newId()

        assertThat(EntityIds.isValid(id)).isTrue()
        assertThat(id).hasLength(36)
    }

    @Test
    fun `newId returns unique values`() {
        val ids = List(1000) { EntityIds.newId() }

        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `isValid rejects malformed ids`() {
        assertThat(EntityIds.isValid("")).isFalse()
        assertThat(EntityIds.isValid("not-a-uuid")).isFalse()
        // UUID.fromString tolerates short segments; canonical form must not.
        assertThat(EntityIds.isValid("1-2-3-4-5")).isFalse()
    }

    @Test
    fun `isValid accepts canonical ids`() {
        assertThat(EntityIds.isValid("00000000-0000-0000-0000-000000000000")).isTrue()
        assertThat(EntityIds.isValid("d9c1f9a2-3b4c-4d5e-8f6a-7b8c9d0e1f2a")).isTrue()
    }
}
