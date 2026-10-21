package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W84：下载管理页「全选当前筛选全集 / 失败仅全选可重试项」。 */
class DownloadSelectionRulesTest {

    private fun failedTask(name: String, reason: DownloadFailureReason?) =
        DownloadTask(
            itemId = UUID.randomUUID(),
            sourceId = "failed-$name",
            name = name,
            path = "/tmp/$name.download",
            downloadId = null,
            status = DownloadTaskStatus.FAILED,
            failureReason = reason,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = 0L,
        )

    private fun entry(task: DownloadTask) =
        DownloadHierarchyEntry(
            itemId = task.itemId,
            name = task.name,
            mediaKind = task.mediaKind,
            status = task.status,
            task = task,
            totalBytes = task.totalBytes,
            albumName = task.albumName,
        )

    @Test
    fun `失败全选键数等于可重试失败任务数而不是容器数`() {
        val first = failedTask("第一集", DownloadFailureReason.SERVER_ERROR)
        val second = failedTask("第二集", DownloadFailureReason.AUTHENTICATION)
        val third = failedTask("第三集", DownloadFailureReason.NETWORK_UNAVAILABLE)
        val containers = DownloadHierarchyBuilder.build(listOf(first, second, third).map(::entry))
        val state =
            DownloadManagerState(
                isLoading = false,
                failedTasks = listOf(first, second, third),
                failedContainers = containers,
            )

        val keys = DownloadSelectionRules.keysFor(state, DownloadTaskGroup.FAILED)

        assertEquals(3, keys.size)
        assertEquals(
            setOf(itemKey(first.itemId), itemKey(second.itemId), itemKey(third.itemId)),
            keys,
        )
    }

    @Test
    fun `当前媒体筛选全选只包含该筛选下的全部条目`() {
        val video = failedTask("视频", DownloadFailureReason.SERVER_ERROR)
        val music =
            failedTask("音乐", DownloadFailureReason.SERVER_ERROR)
                .copy(
                    mediaKind = DownloadMediaKind.MUSIC,
                    albumName = "专辑",
                )
        val containers = DownloadHierarchyBuilder.build(listOf(entry(video), entry(music)))
        val state =
            DownloadManagerState(
                isLoading = false,
                failedTasks = listOf(video, music),
                failedContainers = containers,
                mediaFilter = DownloadMediaFilter.MUSIC,
            )

        val keys = DownloadSelectionRules.keysFor(state, DownloadTaskGroup.FAILED)

        assertEquals(setOf(itemKey(music.itemId)), keys)
    }

    @Test
    fun `失败全选包含没有引擎任务对象的书籍失败条目`() {
        val book =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "离线书籍",
                mediaKind = DownloadMediaKind.BOOK,
                status = DownloadTaskStatus.FAILED,
                task = null,
                bookDetail = "网络中断",
            )
        val state =
            DownloadManagerState(
                isLoading = false,
                failedContainers = DownloadHierarchyBuilder.build(listOf(book)),
                selection = setOf(book.key),
            )

        assertEquals(
            setOf(book.key),
            DownloadSelectionRules.keysFor(state, DownloadTaskGroup.FAILED),
        )
        assertEquals(
            listOf(book.itemId),
            DownloadSelectionRules.retryableBookEntries(state).map { it.itemId },
        )
    }

    @Test
    fun `混合选择重试时只返回失败任务`() {
        val active =
            failedTask("下载中", null)
                .copy(
                    status = DownloadTaskStatus.RUNNING,
                    failureReason = null,
                )
        val failed = failedTask("失败", DownloadFailureReason.SERVER_ERROR)
        val containers = DownloadHierarchyBuilder.build(listOf(entry(active), entry(failed)))
        val state =
            DownloadManagerState(
                isLoading = false,
                activeTasks = listOf(active),
                failedTasks = listOf(failed),
                activeContainers = DownloadHierarchyBuilder.build(listOf(entry(active))),
                failedContainers = DownloadHierarchyBuilder.build(listOf(entry(failed))),
                selectionMode = true,
                selection = containers.flatMap { it.allEntries() }.map { it.key }.toSet(),
            )

        val retryable = DownloadSelectionRules.retryableTasks(state)

        assertEquals(listOf(failed.sourceId), retryable.map { it.sourceId })
    }
}
