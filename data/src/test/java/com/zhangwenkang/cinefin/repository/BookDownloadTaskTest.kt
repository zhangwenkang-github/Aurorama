package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `活动队列口径与主引擎一致包含暂停`() {
        assertTrue(BookDownloadTaskRules.isActiveQueueStatus(BookDownloadStatus.PENDING))
        assertTrue(BookDownloadTaskRules.isActiveQueueStatus(BookDownloadStatus.RUNNING))
        assertTrue(BookDownloadTaskRules.isActiveQueueStatus(BookDownloadStatus.PAUSED))
        assertTrue(!BookDownloadTaskRules.isActiveQueueStatus(BookDownloadStatus.COMPLETED))
        assertTrue(!BookDownloadTaskRules.isActiveQueueStatus(BookDownloadStatus.FAILED))
        assertTrue(BookDownloadTask(itemId = itemId, status = BookDownloadStatus.PAUSED).isActive)
    }

    @Test
    fun `暂停继续重试只对对应状态开放`() {
        assertEquals(
            listOf(BookDownloadStatus.PENDING, BookDownloadStatus.RUNNING),
            BookDownloadStatus.entries.filter(BookDownloadTaskRules::canPause),
        )
        assertEquals(
            listOf(BookDownloadStatus.PAUSED),
            BookDownloadStatus.entries.filter(BookDownloadTaskRules::canResume),
        )
        assertEquals(
            listOf(BookDownloadStatus.FAILED),
            BookDownloadStatus.entries.filter(BookDownloadTaskRules::canRetry),
        )
    }

    @Test
    fun `重启只自动恢复待启动与进行中暂停态保持用户意图`() {
        assertEquals(
            listOf(BookDownloadStatus.PENDING, BookDownloadStatus.RUNNING),
            BookDownloadStatus.entries.filter(BookDownloadTaskRules::isAutoResumeOnRestore),
        )
        assertTrue(!BookDownloadTaskRules.isAutoResumeOnRestore(BookDownloadStatus.PAUSED))
    }

    @Test
    fun `有声书按服务器类型或音频容器识别`() {
        assertTrue(BookAudioRules.isAudiobook(mediaKind = "Audio", container = null))
        assertTrue(BookAudioRules.isAudiobook(mediaKind = "audiobook", container = null))
        assertTrue(BookAudioRules.isAudiobook(mediaKind = "Book", container = "m4b"))
        assertTrue(BookAudioRules.isAudiobook(mediaKind = "book", container = "m4a"))
        assertTrue(!BookAudioRules.isAudiobook(mediaKind = "Book", container = "epub"))
        assertTrue(!BookAudioRules.isAudiobook(mediaKind = "Book", container = null))
        assertTrue("识别不了不得按有声书处理", !BookAudioRules.isAudiobook(mediaKind = null, null))
    }

    @Test
    fun `容器只认扩展名不拿文件名当容器`() {
        assertEquals("m4b", BookAudioRules.containerFromPath("/media/books/三体.m4b"))
        assertEquals("epub", BookAudioRules.containerFromPath("book.EPUB"))
        assertNull(BookAudioRules.containerFromPath("/media/books/no-extension"))
        assertNull(BookAudioRules.containerFromPath(null))
        assertNull("超长后缀不是容器", BookAudioRules.containerFromPath("file.mpegts"))
    }

    @Test
    fun `只有有声书投影时长电子书为零不造数据`() {
        assertEquals(
            3_600_000_000L,
            BookAudioRules.displayRuntimeTicks(
                runtimeTicks = 3_600_000_000L,
                mediaKind = "Book",
                container = "m4b",
            ),
        )
        assertEquals(
            0L,
            BookAudioRules.displayRuntimeTicks(
                runtimeTicks = 3_600_000_000L,
                mediaKind = "Book",
                container = "epub",
            ),
        )
        assertEquals(0L, BookAudioRules.displayRuntimeTicks(-5L, "Audio", null))
    }
}
