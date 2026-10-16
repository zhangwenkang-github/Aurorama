package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W80（#23）：书籍下载任务的进度换算（下载页「下载中 xx%」与"已下载 x MB"共用）。 */
class BookDownloadTaskTest {

    private val itemId = UUID.fromString("1fe05e6a-c68f-3982-b661-261cd3dd55f4")

    @Test
    fun `有总大小时按字节换算进度`() {
        val task =
            BookDownloadTask(
                itemId = itemId,
                downloadedBytes = 25L * 1024 * 1024,
                totalBytes = 100L * 1024 * 1024,
            )
        assertEquals(0.25f, task.progress, 0.0001f)
    }

    @Test
    fun `服务器没给 Content-Length 时进度为 0 不除零`() {
        val task = BookDownloadTask(itemId = itemId, downloadedBytes = 4096L, totalBytes = 0L)
        assertEquals(0f, task.progress, 0.0001f)
        assertTrue(task.progress.isFinite())
    }

    @Test
    fun `进度夹在 0 到 1 之间`() {
        val over = BookDownloadTask(itemId = itemId, downloadedBytes = 200L, totalBytes = 100L)
        assertEquals(1f, over.progress, 0.0001f)
    }

    @Test
    fun `默认状态是进行中`() {
        assertEquals(BookDownloadStatus.RUNNING, BookDownloadTask(itemId = itemId).status)
    }
}
