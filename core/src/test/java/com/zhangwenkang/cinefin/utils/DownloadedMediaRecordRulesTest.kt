package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedMediaRecordRulesTest {

    @Test
    fun `同 item 只返回真正完成的 LOCAL source`() {
        val completed =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.VIDEO,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = "source-a",
                displayName = "完成来源",
            )
        val failed = completed.copy(sourceId = "source-b", displayName = "失败或取消来源")

        val selected =
            DownloadedMediaRecordRules.completedRecords(
                records = listOf(completed, failed),
                completedLocalSources =
                    setOf(DownloadedMediaSource(ITEM_ID, sourceId = "source-a")),
                bookFileSize = { 0L },
            )

        assertEquals(listOf(completed), selected)
    }

    @Test
    fun `音乐精确 source 过滤且 legacy 无 source 只在 item 有完整文件时保留`() {
        val completed =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.MUSIC,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = "source-a",
            )
        val legacyUnknownSource = completed.copy(sourceId = null, displayName = "缺来源")

        val withCompletedItem =
            DownloadedMediaRecordRules.completedRecords(
                records = listOf(legacyUnknownSource, completed),
                completedLocalSources =
                    setOf(DownloadedMediaSource(ITEM_ID, sourceId = "source-a")),
                bookFileSize = { 0L },
            )
        val withoutCompletedItem =
            DownloadedMediaRecordRules.completedRecords(
                records = listOf(legacyUnknownSource),
                completedLocalSources = emptySet(),
                bookFileSize = { 0L },
            )

        assertEquals(listOf(legacyUnknownSource, completed), withCompletedItem)
        assertEquals(emptyList<DownloadMediaRecord>(), withoutCompletedItem)
    }

    @Test
    fun `书籍仅在非空 book 文件存在时成为完成下载`() {
        val book =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.BOOK,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = null,
                displayName = "书",
            )
        val completedSources = emptySet<DownloadedMediaSource>()

        assertEquals(
            listOf(book),
            DownloadedMediaRecordRules.completedRecords(
                records = listOf(book),
                completedLocalSources = completedSources,
                bookFileSize = { size -> if (size == ITEM_ID) 42L else 0L },
            ),
        )
        assertEquals(
            emptyList<DownloadMediaRecord>(),
            DownloadedMediaRecordRules.completedRecords(
                records = listOf(book),
                completedLocalSources = completedSources,
                bookFileSize = { 0L },
            ),
        )
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server-a"
    }
}
