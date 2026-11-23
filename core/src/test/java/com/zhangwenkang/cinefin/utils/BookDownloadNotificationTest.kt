package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W9-T7（F5）：书籍下载通知的映射契约。
 *
 * 书籍链复用引擎通知，重点是两点：投影成 `mediaKind = BOOK` 的完成任务（点击才直达阅读器）， 以及完成目的地携带的 deep link extra 与「回退已完成分组」不冲突。
 */
class BookDownloadNotificationTest {

    private val itemId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")

    @Test
    fun `完成书籍任务投影为可直达阅读器的完成通知`() {
        val task =
            BookDownloadTask(
                itemId = itemId,
                title = "海边的书",
                downloadedBytes = 4096L,
                totalBytes = 4096L,
                status = BookDownloadStatus.COMPLETED,
                canonicalKey = "book-canonical-key",
                targetPath = "/data/books/$itemId.book",
                updatedAtEpochMillis = 1_700_000_000_000L,
            )

        val notificationTask = bookDownloadNotificationTask(task)

        assertEquals(itemId, notificationTask.itemId)
        assertEquals("book-canonical-key", notificationTask.sourceId)
        assertEquals(DownloadMediaKind.BOOK, notificationTask.mediaKind)
        assertEquals(DownloadTaskStatus.COMPLETED, notificationTask.status)
        assertEquals("海边的书", notificationTask.name)
        assertEquals(4096L, notificationTask.downloadedBytes)
        assertEquals(4096L, notificationTask.totalBytes)
        assertNull("完成通知不携带失败原因", notificationTask.failureReason)

        val target =
            downloadNotificationTarget(
                DownloadNotificationDestination.Completed(
                    itemId = notificationTask.itemId,
                    mediaKind = notificationTask.mediaKind,
                )
            )
        assertEquals(itemId.toString(), target.completion?.itemId)
        assertEquals(DownloadMediaKind.BOOK, target.completion?.mediaKind)
        assertFalse("完成通知不打开下载页", target.openDownloads)
        assertNull(target.tab)
        assertNotEquals(
            "完成通知的请求码必须与失败 / 活动下载通知分离",
            downloadNotificationTarget(DownloadNotificationDestination.Failed).requestCode,
            target.requestCode,
        )
        assertEquals(
            DownloadMediaKind.BOOK,
            parseDownloadCompletionMediaKind(target.completion?.mediaKind?.name),
        )
    }

    @Test
    fun `缺书名时沿用离线书籍占位名`() {
        val task = BookDownloadTask(itemId = itemId, title = "  ")

        assertEquals(
            "离线书籍 ${itemId.toString().take(8)}",
            bookDownloadNotificationTask(task).name,
        )
    }

    @Test
    fun `传输失败按通用失败投影并带出错误文本`() {
        val task =
            BookDownloadTask(
                itemId = itemId,
                title = "海边的书",
                status = BookDownloadStatus.FAILED,
                failureReason = "ERROR",
                error = "下载书籍失败：HTTP 503（离线时请先联网下载）",
            )

        val notificationTask = bookDownloadNotificationTask(task)

        assertEquals(DownloadTaskStatus.FAILED, notificationTask.status)
        assertEquals(DownloadFailureReason.UNKNOWN, notificationTask.failureReason)
        assertEquals("下载书籍失败：HTTP 503（离线时请先联网下载）", notificationTask.failureDetail)
    }

    @Test
    fun `可归因的失败原因映射到既有失败类别`() {
        assertEquals(
            DownloadFailureReason.CANCELLED,
            bookDownloadFailureReason(
                BookDownloadTask(itemId = itemId, status = BookDownloadStatus.FAILED)
                    .copy(failureReason = "CANCELLED")
            ),
        )
        assertEquals(
            DownloadFailureReason.FILE_ERROR,
            bookDownloadFailureReason(
                BookDownloadTask(itemId = itemId, status = BookDownloadStatus.FAILED)
                    .copy(failureReason = "PROVENANCE_WRITE_FAILED")
            ),
        )
        assertNull(
            "未失败的任务不投影失败原因",
            bookDownloadFailureReason(BookDownloadTask(itemId = itemId)),
        )
    }
}
