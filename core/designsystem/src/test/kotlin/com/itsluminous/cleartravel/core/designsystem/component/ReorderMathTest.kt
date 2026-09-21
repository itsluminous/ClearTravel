package com.itsluminous.cleartravel.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReorderMathTest {
    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moving down re-inserts after the elements it passed`() {
        assertThat(list.moved(0, 2)).containsExactly("b", "c", "a", "d").inOrder()
    }

    @Test
    fun `moving up re-inserts before the elements it passed`() {
        assertThat(list.moved(3, 1)).containsExactly("a", "d", "b", "c").inOrder()
    }

    @Test
    fun `moving to the ends works`() {
        assertThat(list.moved(1, 3)).containsExactly("a", "c", "d", "b").inOrder()
        assertThat(list.moved(2, 0)).containsExactly("c", "a", "b", "d").inOrder()
    }

    @Test
    fun `same index is a no-op`() {
        assertThat(list.moved(2, 2)).isSameInstanceAs(list)
    }

    @Test
    fun `out of range indices are a no-op`() {
        assertThat(list.moved(-1, 2)).isSameInstanceAs(list)
        assertThat(list.moved(1, 4)).isSameInstanceAs(list)
        assertThat(emptyList<String>().moved(0, 0)).isEmpty()
    }

    @Test
    fun `does not mutate the receiver`() {
        list.moved(0, 3)
        assertThat(list).containsExactly("a", "b", "c", "d").inOrder()
    }
}
