package com.zhangwenkang.cinefin.repository

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun `服务器进度较新且位置不同时丢弃本地 locator`() {
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
        assertEquals("", merged.locatorJson)
    }

    @Test
    fun `服务器进度是本机回传的回声时保留本地 locator`() {
        val local =
            ReadingProgress(
                itemId = itemId,
                locatorJson = """{"href":"chapter-4"}""",
                progression = 0.003568879,
                positionTicks = 35_689,
                updatedAt = Instant.ofEpochMilli(1_000),
                runtimeTicks = 10_000_000,
            )
        // 服务端把百分比四舍五入后返回，位置近似相等。
        val remote =
            ReadingProgress(
                itemId = itemId,
                progression = 0.0035689,
                positionTicks = 35_689,
                updatedAt = Instant.ofEpochMilli(2_000),
            )

        val merged = resolveReadingProgress(itemId, local, remote)

        assertNotNull(merged)
        assertEquals(0.0035689, merged!!.progression, 0.0000001)
        assertEquals("""{"href":"chapter-4"}""", merged.locatorJson)
        // 缓存的 runtimeTicks 参与换算，离线也能算出正确的 ticks。
        assertEquals(35_689, merged.positionTicks)
        assertEquals(10_000_000, merged.runtimeTicks)
    }

    @Test
    fun `本地较新时保留 locator 与缓存 runtime`() {
        val local =
            ReadingProgress(
                itemId = itemId,
                locatorJson = """{"href":"chapter-9"}""",
                progression = 0.9,
                positionTicks = 9_000,
                updatedAt = Instant.ofEpochMilli(5_000),
                runtimeTicks = 1_000_000_000,
            )
        val remote =
            ReadingProgress(
                itemId = itemId,
                progression = 0.1,
                positionTicks = 1_000,
                updatedAt = Instant.ofEpochMilli(1_000),
            )

        val merged = resolveReadingProgress(itemId, local, remote)

        assertEquals(0.9, merged!!.progression, 0.0001)
        assertEquals("""{"href":"chapter-9"}""", merged.locatorJson)
        assertEquals(900_000_000, merged.positionTicks)
    }

    @Test
    fun `只有远端进度时不带 locator`() {
        val remote =
            ReadingProgress(
                itemId = itemId,
                progression = 0.42,
                positionTicks = 4_200,
                updatedAt = Instant.ofEpochMilli(1_000),
            )

        val merged = resolveReadingProgress(itemId, local = null, remote = remote)

        assertEquals("", merged!!.locatorJson)
        assertTrue(progressionsMatch(merged.progression, 0.42))
        assertTrue(!progressionsMatch(merged.progression, 0.43))
    }
}
