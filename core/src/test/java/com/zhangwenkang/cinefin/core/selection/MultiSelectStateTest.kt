package com.zhangwenkang.cinefin.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSelectStateTest {

    @Test
    fun `long press enters selection mode and selects the pressed id`() {
        val state = MultiSelectState().longPress("a")
        assertTrue(state.selectionMode)
        assertEquals(setOf("a"), state.selectedIds)
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `toggle switches one id while in selection mode`() {
        val state = MultiSelectState().longPress("a").toggle("b")
        assertEquals(setOf("a", "b"), state.selectedIds)
        assertEquals(setOf("a"), state.toggle("b").selectedIds)
    }

    @Test
    fun `toggle is ignored outside selection mode`() {
        val state = MultiSelectState().toggle("a")
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `toggling the last selected id exits selection mode`() {
        val state = MultiSelectState().longPress("a").toggle("a")
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `select all adds only the loaded ids`() {
        val state = MultiSelectState().longPress("a").selectAll(listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), state.selectedIds)
        assertTrue(state.selectionMode)
    }

    @Test
    fun `select all on an empty loaded list keeps selection mode on current selection`() {
        val state = MultiSelectState().longPress("a").selectAll(emptyList())
        assertTrue(state.selectionMode)
        assertEquals(setOf("a"), state.selectedIds)
    }

    @Test
    fun `select all with nothing selected on an empty list stays out of selection mode`() {
        val state = MultiSelectState().selectAll(emptyList())
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `select none clears only the loaded ids and keeps the rest`() {
        val state = MultiSelectState().longPress("a").toggle("b").selectNone(listOf("b"))
        assertTrue(state.selectionMode)
        assertEquals(setOf("a"), state.selectedIds)
    }

    @Test
    fun `select none on the whole loaded list keeps selection mode`() {
        val state = MultiSelectState().longPress("a").selectNone(listOf("a"))
        assertTrue(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `retain drops ids that are no longer loaded`() {
        val state = MultiSelectState().longPress("a").toggle("b").retain(setOf("b", "c"))
        assertTrue(state.selectionMode)
        assertEquals(setOf("b"), state.selectedIds)
    }

    @Test
    fun `retain exits selection mode when nothing survives`() {
        val state = MultiSelectState().longPress("a").retain(setOf("z"))
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `toggle selection mode enters empty and clears on second call`() {
        val entered = MultiSelectState().toggleSelectionMode()
        assertTrue(entered.selectionMode)
        val exited = entered.longPress("a").toggleSelectionMode()
        assertFalse(exited.selectionMode)
        assertTrue(exited.selectedIds.isEmpty())
    }

    @Test
    fun `clear resets everything`() {
        val state = MultiSelectState().longPress("a").toggle("b").clear()
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.hasSelection)
    }
}
