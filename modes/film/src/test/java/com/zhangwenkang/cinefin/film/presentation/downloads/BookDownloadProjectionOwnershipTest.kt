package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.LocalBookFile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDownloadProjectionOwnershipTest {

    @Test
    fun `superseded 不进入 bookDownloads active failed 且旧 key 不命中新完成文件`() {
        val old =
            bookTask(
                canonicalKey = "old-local-delete",
                serverId = "__book_local_delete__",
                userId = ZERO_USER_ID,
                status = BookDownloadStatus.FAILED,
                failureReason = "DELETE_SUPERSEDED",
                updatedAt = 1,
            )
        val current =
            bookTask(
                canonicalKey = "book:current",
                serverId = "server-b",
                userId = OTHER_USER_ID,
                status = BookDownloadStatus.COMPLETED,
                updatedAt = 2,
            )

        val visible = BookDownloadProjectionRules.visibleTasks(listOf(old, current))
        val owner = BookDownloadProjectionRules.ownerFor(visible, ITEM_ID)
        val completedEntry =
            BookDownloadProjectionRules.completedEntry(
                file = LocalBookFile(itemId = ITEM_ID, sizeBytes = 2, title = "新来源"),
                serverName = "新来源",
                owner = owner,
                imageUri = null,
                allowOffline = true,
            )
        val oldKey = "item:${old.canonicalKey}"

        assertEquals(listOf(current), visible)
        assertTrue(BookDownloadProjectionRules.activeTasks(visible).isEmpty())
        assertTrue(BookDownloadProjectionRules.failedTasks(visible).isEmpty())
        assertEquals("server-b", completedEntry.serverId)
        assertEquals(OTHER_USER_ID.toString(), completedEntry.userId)
        assertNotEquals("旧 superseded key 不得与新 completed canonical 冲突", oldKey, completedEntry.key)
        assertNull(
            "刷新后旧 key 不得再解析到新文件或触发删除",
            listOf(completedEntry).firstOrNull { it.key == oldKey },
        )
    }

    @Test
    fun `active 与 failed 只投影当前 RUNNING FAILED 且多个 owner 不给默认 owner`() {
        val running =
            bookTask(
                canonicalKey = "book:running",
                serverId = "server-a",
                userId = USER_ID,
                status = BookDownloadStatus.RUNNING,
                updatedAt = 1,
            )
        val failed =
            bookTask(
                canonicalKey = "book:failed",
                serverId = "server-b",
                userId = OTHER_USER_ID,
                status = BookDownloadStatus.FAILED,
                updatedAt = 2,
            )
        val superseded =
            running.copy(
                canonicalKey = "book:superseded",
                failureReason = "DELETE_SUPERSEDED",
                updatedAtEpochMillis = 3,
            )

        val visible = BookDownloadProjectionRules.visibleTasks(listOf(running, failed, superseded))

        assertEquals(listOf(running), BookDownloadProjectionRules.activeTasks(visible))
        assertEquals(listOf(failed), BookDownloadProjectionRules.failedTasks(visible))
        assertNull(
            "多个 canonical owner 不得任选一个作为删除目标",
            BookDownloadProjectionRules.ownerFor(visible, ITEM_ID),
        )
    }

    private fun bookTask(
        canonicalKey: String,
        serverId: String,
        userId: UUID,
        status: BookDownloadStatus,
        failureReason: String? = null,
        updatedAt: Long,
    ) =
        BookDownloadTask(
            itemId = ITEM_ID,
            title = "书",
            status = status,
            failureReason = failureReason,
            canonicalKey = canonicalKey,
            serverId = serverId,
            userId = userId,
            targetPath = "/books/$ITEM_ID.book",
            createdAtEpochMillis = updatedAt,
            updatedAtEpochMillis = updatedAt,
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val ZERO_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
