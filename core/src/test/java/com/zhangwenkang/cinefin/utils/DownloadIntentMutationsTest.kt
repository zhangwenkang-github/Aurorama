package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.DownloadIntentDto
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadIntentMutationsTest {

    @Test
    fun `FAILED intent delete uses deletion CAS`() = runBlocking {
        val intent = MemoryIntentState(status = "FAILED", generation = 6L)

        val deleted = intent.mutations.delete(ITEM_ID, expectedGeneration = 6L, updatedAt = 100L)

        assertNotNull(deleted)
        assertEquals(listOf("delete"), intent.calls)
        assertEquals(7L, deleted?.taskGeneration)
    }

    @Test
    fun `RECEIVED intent cancel uses cancellation CAS`() = runBlocking {
        val intent = MemoryIntentState(status = "RECEIVED", generation = 3L)

        val cancelled = intent.mutations.cancel(ITEM_ID, expectedGeneration = 3L, updatedAt = 100L)

        assertNotNull(cancelled)
        assertEquals(listOf("cancel"), intent.calls)
        assertEquals("CANCELLED", cancelled?.failureReason)
        assertEquals(4L, cancelled?.taskGeneration)
    }

    private class MemoryIntentState(
        private var status: String,
        private var generation: Long,
    ) {
        val calls = mutableListOf<String>()

        val mutations =
            DownloadIntentMutations(
                beginCancellation = { itemId, expectedGeneration, updatedAt ->
                    calls += "cancel"
                    transition(
                        itemId = itemId,
                        expectedGeneration = expectedGeneration,
                        updatedAt = updatedAt,
                        allowedStatuses = setOf("RECEIVED"),
                    )
                },
                beginDeletion = { itemId, expectedGeneration, updatedAt ->
                    calls += "delete"
                    transition(
                        itemId = itemId,
                        expectedGeneration = expectedGeneration,
                        updatedAt = updatedAt,
                        allowedStatuses = setOf("RECEIVED", "FAILED"),
                    )
                },
            )

        private fun transition(
            itemId: UUID,
            expectedGeneration: Long,
            updatedAt: Long,
            allowedStatuses: Set<String>,
        ): DownloadIntentDto? {
            if (
                itemId != ITEM_ID || generation != expectedGeneration || status !in allowedStatuses
            ) {
                return null
            }
            status = "FAILED"
            generation += 1
            return DownloadIntentDto(
                itemId = itemId,
                name = "Track",
                albumName = null,
                artist = null,
                trackIndex = 0,
                status = status,
                failureReason = "CANCELLED",
                createdAt = 0L,
                updatedAt = updatedAt,
                taskGeneration = generation,
            )
        }
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 1L)
    }
}
