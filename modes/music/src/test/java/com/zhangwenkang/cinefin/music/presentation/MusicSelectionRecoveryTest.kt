package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.selection.MultiSelectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSelectionRecoveryTest {

    @Test
    fun `partial failure restores only unaccepted visible songs after owned clear`() {
        val original = selection("a", "b", "c")
        val initial = MusicBatchSelectionState(selection = original)
        val snapshot = initial.snapshotForPlayback()
        val accepted = initial.acceptedForPlayback(requestId = 7, snapshot = snapshot)

        assertEquals(MultiSelectState(), accepted.selection)
        val recovered =
            accepted.restoreUnaccepted(
                requestId = 7,
                unacceptedIds = setOf("b", "c", "hidden"),
                visibleIds = setOf("a", "b", "c"),
            )

        assertEquals(selection("b", "c"), recovered.selection)
        assertNull(recovered.recoveryClaim)
        assertTrue(recovered.revision > accepted.revision)
    }

    @Test
    fun `explicit user clear prevents automatic restoration`() {
        val initial = MusicBatchSelectionState(selection = selection("a", "b"))
        val accepted =
            initial.acceptedForPlayback(
                requestId = 8,
                snapshot = initial.snapshotForPlayback(),
            )
        val userCleared = accepted.userChanged(MultiSelectState())

        val recovered =
            userCleared.restoreUnaccepted(
                requestId = 8,
                unacceptedIds = setOf("a", "b"),
                visibleIds = setOf("a", "b"),
            )

        assertEquals(MultiSelectState(), recovered.selection)
        assertNull(recovered.recoveryClaim)
    }

    @Test
    fun `new user selection survives a late partial failure`() {
        val initial = MusicBatchSelectionState(selection = selection("a", "b"))
        val accepted =
            initial.acceptedForPlayback(
                requestId = 9,
                snapshot = initial.snapshotForPlayback(),
            )
        val newSelection = selection("c")
        val userChanged = accepted.userChanged(newSelection)

        val afterFailure =
            userChanged.restoreUnaccepted(
                requestId = 9,
                unacceptedIds = setOf("a", "b"),
                visibleIds = setOf("a", "b", "c"),
            )

        assertEquals(newSelection, afterFailure.selection)
    }

    @Test
    fun `partial failure from another operation token cannot restore`() {
        val initial = MusicBatchSelectionState(selection = selection("a", "b"))
        val accepted =
            initial.acceptedForPlayback(
                requestId = 10,
                snapshot = initial.snapshotForPlayback(),
            )

        val staleFailure =
            accepted.restoreUnaccepted(
                requestId = 11,
                unacceptedIds = setOf("a", "b"),
                visibleIds = setOf("a", "b"),
            )

        assertEquals(MultiSelectState(), staleFailure.selection)
    }

    @Test
    fun `selection changed while request was pending is not cleared on acceptance`() {
        val initial = MusicBatchSelectionState(selection = selection("a", "b"))
        val snapshot = initial.snapshotForPlayback()
        val changed = initial.userChanged(selection("b", "c"))

        val accepted = changed.acceptedForPlayback(requestId = 12, snapshot = snapshot)

        assertEquals(selection("b", "c"), accepted.selection)
        assertNull(accepted.recoveryClaim)
    }

    private fun selection(vararg ids: String): MultiSelectState =
        MultiSelectState(selectionMode = true, selectedIds = ids.toSet())
}
