package com.zhangwenkang.cinefin.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentDeletionRulesTest {

    @Test
    fun `任一 source 未确认清理时不删除 intent 且所有 source 都被尝试`() = runBlocking {
        val attempted = mutableListOf<String>()
        var intentDeleted = false

        val result =
            DownloadIntentDeletionRules.deleteAllOrReport(
                sourceIds = listOf("source-a", "source-b", "source-c"),
                deleteSource = { sourceId ->
                    attempted += sourceId
                    sourceId != "source-b"
                },
                deleteIntent = {
                    intentDeleted = true
                    true
                },
            )

        assertEquals(listOf("source-a", "source-b", "source-c"), attempted)
        assertFalse(result.confirmed)
        assertFalse(intentDeleted)
    }

    @Test
    fun `异常作为失败报告且不吞掉后续 source 与 intent 保护`() = runBlocking {
        val attempted = mutableListOf<String>()
        var intentDeleted = false

        val result =
            DownloadIntentDeletionRules.deleteAllOrReport(
                sourceIds = listOf("source-a", "source-b"),
                deleteSource = { sourceId ->
                    attempted += sourceId
                    if (sourceId == "source-a") error("文件忙")
                    true
                },
                deleteIntent = {
                    intentDeleted = true
                    true
                },
            )

        assertEquals(listOf("source-a", "source-b"), attempted)
        assertFalse(result.confirmed)
        assertEquals("文件忙", result.failure?.message)
        assertFalse(intentDeleted)
    }

    @Test
    fun `全部 source 确认清理后才删除 intent`() = runBlocking {
        var intentDeleted = false

        val result =
            DownloadIntentDeletionRules.deleteAllOrReport(
                sourceIds = listOf("source-a", "source-b"),
                deleteSource = { true },
                deleteIntent = {
                    intentDeleted = true
                    true
                },
            )

        assertTrue(result.confirmed)
        assertTrue(intentDeleted)
    }
}
