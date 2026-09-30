package com.zhangwenkang.cinefin.repository

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReadingProgressMappingTest {
    private val itemId = UUID.fromString("8bb46d1a-2e5f-4d5f-9d19-8e4a2f4f0e1a")

    @Test
    fun `progression 会夹在 0 到 1 并映射为 ticks`() {
        assertEquals(0.0, normalizedProgression(-0.5), 0.0001)
        assertEquals(1.0, normalizedProgression(1.5), 0.0001)
        assertEquals(
            DEFAULT_BOOK_TIMELINE_TICKS / 2,
            progressionToTicks(0.5),
        )
        assertEquals(2_000, progressionToTicks(0.5, runtimeTicks = 4_000))
    }

    @Test
    fun `最近时间戳的进度胜出`() {
        val local =
            ReadingProgress(
                itemId = itemId,
                locatorJson = """{"href":"chapter-2"}""",
                progression = 0.8,
                positionTicks = 8_000,
                updatedAt = Instant.ofEpochMilli(2_000),
            )
        val remote =
            ReadingProgress(
                itemId = itemId,
                progression = 0.3,
                positionTicks = 3_000,
                updatedAt = Instant.ofEpochMilli(1_000),
            )

        val merged = resolveReadingProgress(itemId, local, remote)

        assertNotNull(merged)
        assertEquals(0.8, merged!!.progression, 0.0001)
        assertEquals("""{"href":"chapter-2"}""", merged.locatorJson)
    }

    @Test
    fun `服务器进度较新时仍保留本地 locator`() {
        val local =
            ReadingProgress(
                itemId = itemId,
                locatorJson = """{"href":"chapter-2"}""",
                progression = 0.2,
                positionTicks = 2_000,
                updatedAt = Instant.ofEpochMilli(1_000),
            )
        val remote =
            ReadingProgress(
                itemId = itemId,
                progression = 0.6,
                positionTicks = 6_000,
                updatedAt = Instant.ofEpochMilli(2_000),
            )

        val merged = resolveReadingProgress(itemId, local, remote)

        assertNotNull(merged)
        assertEquals(0.6, merged!!.progression, 0.0001)
        assertEquals("""{"href":"chapter-2"}""", merged.locatorJson)
    }
}
