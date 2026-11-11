package dev.jdtech.mpv.binding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvSourceEventGateTest {
    @Test
    fun `one source action owns start file loaded and end identity`() {
        val gate = MpvSourceEventGate()
        gate.arm(playlistEntryId = 41L, actionGeneration = 7L, afterNativeGeneration = 100L)

        val start = gate.accept(eventId = 6, playlistEntryId = 41L, nativeGeneration = 101L)
        val loaded = gate.accept(eventId = 8, playlistEntryId = 41L, nativeGeneration = 101L)
        val end = gate.accept(eventId = 7, playlistEntryId = 41L, nativeGeneration = 101L)

        assertEquals(7L, start?.actionGeneration)
        assertEquals(7L, loaded?.actionGeneration)
        assertEquals(7L, end?.actionGeneration)
        assertEquals(41L, start?.playlistEntryId)
        assertEquals(41L, loaded?.playlistEntryId)
        assertEquals(41L, end?.playlistEntryId)
    }

    @Test
    fun `old playlist identity is rejected before observer`() {
        val gate = MpvSourceEventGate()
        gate.arm(playlistEntryId = 42L, actionGeneration = 8L, afterNativeGeneration = 200L)

        assertNull(gate.accept(eventId = 6, playlistEntryId = 41L, nativeGeneration = 201L))
        assertNull(gate.accept(eventId = 8, playlistEntryId = 41L, nativeGeneration = 201L))
        assertNull(gate.accept(eventId = 7, playlistEntryId = 41L, nativeGeneration = 201L))
    }

    @Test
    fun `same uri successor never reuses predecessor identity`() {
        val gate = MpvSourceEventGate()
        gate.arm(playlistEntryId = 10L, actionGeneration = 1L, afterNativeGeneration = 0L)
        assertEquals(1L, gate.accept(6, 10L, 1L)?.actionGeneration)

        gate.arm(playlistEntryId = 11L, actionGeneration = 2L, afterNativeGeneration = 1L)
        assertNull(gate.accept(6, 10L, 2L))
        assertNull(gate.accept(8, 10L, 2L))
        assertNull(gate.accept(7, 10L, 2L))
        assertEquals(2L, gate.accept(6, 11L, 2L)?.actionGeneration)
    }

    @Test
    fun `duplicate and out of order events stay in one generation`() {
        val gate = MpvSourceEventGate()
        gate.arm(playlistEntryId = 21L, actionGeneration = 3L, afterNativeGeneration = 4L)

        assertEquals(3L, gate.accept(6, 21L, 5L)?.actionGeneration)
        assertNull(gate.accept(6, 21L, 5L))
        assertNull(gate.accept(6, 21L, 6L))
        assertEquals(3L, gate.accept(8, 21L, 5L)?.actionGeneration)
        assertNull(gate.accept(8, 21L, 6L))
        assertEquals(3L, gate.accept(7, 21L, 5L)?.actionGeneration)
        assertNull(gate.accept(7, 21L, 5L))
    }

    @Test
    fun `same playlist id after rearm rejects old native generation`() {
        val gate = MpvSourceEventGate()
        gate.arm(playlistEntryId = 31L, actionGeneration = 4L, afterNativeGeneration = 9L)

        assertNull(gate.accept(6, 31L, 9L))
        assertEquals(4L, gate.accept(6, 31L, 10L)?.actionGeneration)
        assertNull(gate.accept(8, 31L, 9L))
    }
}
