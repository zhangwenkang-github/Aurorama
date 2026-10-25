package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            setOf(taskKey(first), taskKey(second), taskKey(third)),
            keys.toSet(),
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

        assertEquals(listOf(taskKey(music)), keys)
    }

    @Test
    fun `删除失败后 intent key 刷新为真实 source 时仍保留同一项选择`() {
        val itemId = UUID.randomUUID()
        val intentTask =
            failedTask("待删曲目", DownloadFailureReason.CANCELLED)
                .copy(
                    itemId = itemId,
                    sourceId = "intent:$itemId",
                    serverId = "server-a",
                    userId = "user-a",
                )
        val sourceTask =
            intentTask.copy(sourceId = "media-source", status = DownloadTaskStatus.DELETING)
        val oldEntries = listOf(entry(intentTask))
        val newEntries = listOf(entry(sourceTask))
        val state =
            DownloadManagerState(
                failedTasks = listOf(intentTask),
                failedContainers = DownloadHierarchyBuilder.build(oldEntries),
                selectionMode = true,
                selection = listOf(oldEntries.single().key),
                pendingActions = mapOf(oldEntries.single().key to DownloadPendingAction.DELETE),
                actionFailure = DownloadActionFailure.DELETE,
            )

        assertEquals(
            listOf(newEntries.single().key),
            DownloadSelectionRules.selectionAfterRefresh(
                state = state,
                validKeys = setOf(newEntries.single().key),
                currentEntries = newEntries,
            ),
        )
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
                selection = listOf(book.key),
            )

        assertEquals(
            listOf(book.key),
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
                selection = containers.flatMap { it.allEntries() }.map { it.key },
            )

        val retryable = DownloadSelectionRules.retryableTasks(state)

        assertEquals(listOf(failed.sourceId), retryable.map { it.sourceId })
    }

    @Test
    fun `已完成的同类型选中曲目按用户点选顺序生成播放计划`() {
        val first =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "第一首",
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-first",
                albumName = "专辑",
                trackIndex = 1,
            )
        val second =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "第二首",
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-second",
                albumName = "专辑",
                trackIndex = 2,
            )
        val containers = DownloadHierarchyBuilder.build(listOf(second, first))
        val state =
            DownloadManagerState(
                isLoading = false,
                completedContainers = containers,
                selectionMode = true,
                selection = listOf(second.key, first.key),
            )

        val plan = DownloadSelectionRules.playbackPlan(state)

        assertEquals(
            listOf(second.itemId, first.itemId),
            (plan as DownloadPlaybackPlan.Music).itemIds,
        )
    }

    @Test
    fun `已完成的视频选中项按用户点选顺序生成播放计划`() {
        val first =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "第一集",
                mediaKind = DownloadMediaKind.VIDEO,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-first-video",
                seriesId = UUID.randomUUID(),
                seasonId = UUID.randomUUID(),
                episodeIndex = 1,
            )
        val second =
            first.copy(
                itemId = UUID.randomUUID(),
                name = "第二集",
                sourceId = "local-second-video",
                episodeIndex = 2,
            )
        val state =
            DownloadManagerState(
                isLoading = false,
                completedContainers = DownloadHierarchyBuilder.build(listOf(first, second)),
                selectionMode = true,
                selection = listOf(second.key, first.key),
            )

        val plan = DownloadSelectionRules.playbackPlan(state)

        assertEquals(
            listOf(second.itemId, first.itemId),
            (plan as DownloadPlaybackPlan.Video).itemIds,
        )
    }

    @Test
    fun `已完成选择在本地阶段刷新后保持选择顺序`() {
        val first =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "第一首",
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-first",
                albumName = "专辑",
                trackIndex = 1,
            )
        val second =
            first.copy(
                itemId = UUID.randomUUID(),
                name = "第二首",
                sourceId = "local-second",
                trackIndex = 2,
            )
        val state =
            DownloadManagerState(
                isLoading = false,
                completedContainers = DownloadHierarchyBuilder.build(listOf(first, second)),
                selectionMode = true,
                selection = listOf(second.key, first.key),
            )

        val retained = DownloadSelectionRules.selectionAfterLocalRefresh(state, emptySet())

        assertEquals(listOf(second.key, first.key), retained)
    }

    @Test
    fun `容器后再选条目时保持显式追加顺序`() {
        val first =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "第一首",
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-first",
                albumName = "专辑",
                trackIndex = 1,
            )
        val second =
            first.copy(
                itemId = UUID.randomUUID(),
                name = "第二首",
                sourceId = "local-second",
                trackIndex = 2,
            )
        val containers = DownloadHierarchyBuilder.build(listOf(first, second))
        val state = DownloadManagerState(isLoading = false, completedContainers = containers)

        val selected = DownloadSelectionRules.toggleSelection(state, containers.single().key)

        assertEquals(listOf(first.key, second.key), selected)
    }

    @Test
    fun `连续点选两个容器时后点容器条目追加到尾部`() {
        fun track(name: String, album: String) =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = name,
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-$name",
                albumName = album,
                trackIndex = 1,
            )

        val first = track("第一首", "专辑 A")
        val second = track("第二首", "专辑 B")
        val containers = DownloadHierarchyBuilder.build(listOf(first, second))
        val firstContainer = containers.first { container ->
            container.allEntries().single() == first
        }
        val secondContainer = containers.first { container ->
            container.allEntries().single() == second
        }
        val firstSelected =
            DownloadSelectionRules.toggleSelection(
                DownloadManagerState(isLoading = false, completedContainers = containers),
                firstContainer.key,
            )
        val bothSelected =
            DownloadSelectionRules.toggleSelection(
                DownloadManagerState(
                    isLoading = false,
                    completedContainers = containers,
                    selection = firstSelected,
                ),
                secondContainer.key,
            )

        assertEquals(listOf(first.key, second.key), bothSelected)
    }

    @Test
    fun `混合媒体类型不生成多选播放计划`() {
        val video =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "视频",
                mediaKind = DownloadMediaKind.VIDEO,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-video",
            )
        val music =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "音乐",
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-music",
                albumName = "专辑",
            )
        val containers = DownloadHierarchyBuilder.build(listOf(video, music))
        val state =
            DownloadManagerState(
                isLoading = false,
                completedContainers = containers,
                selectionMode = true,
                selection = listOf(video.key, music.key),
            )

        assertNull(DownloadSelectionRules.playbackPlan(state))
    }

    @Test
    fun `选中未完成内容时不生成播放计划`() {
        val running = failedTask("仍在下载", null).copy(status = DownloadTaskStatus.RUNNING)
        val containers = DownloadHierarchyBuilder.build(listOf(entry(running)))
        val state =
            DownloadManagerState(
                isLoading = false,
                activeTasks = listOf(running),
                activeContainers = containers,
                selectionMode = true,
                selection = listOf(running.itemId.let(::itemKey)),
            )

        assertNull(DownloadSelectionRules.playbackPlan(state))
    }

    @Test
    fun `点击下载动作后在异步完成前立即产生 pending`() {
        val task = failedTask("等待继续", null).copy(status = DownloadTaskStatus.PAUSED)
        val state =
            DownloadManagerState(
                isLoading = false,
                activeTasks = listOf(task),
                activeContainers = DownloadHierarchyBuilder.build(listOf(entry(task))),
                selectionMode = true,
                selection = listOf(taskKey(task)),
            )

        val pending = state.beginPending(DownloadPendingAction.RESUME)

        assertEquals(
            DownloadPendingAction.RESUME,
            pending.pendingActions[taskKey(task)],
        )
    }

    @Test
    fun `下载动作完成后清除对应 pending`() {
        val key = itemKey(UUID.randomUUID())
        val state =
            DownloadManagerState(pendingActions = mapOf(key to DownloadPendingAction.RESUME))

        val finished = state.finishPending(setOf(key))

        assertNull(finished.pendingActions[key])
    }

    @Test
    fun `任一批次 pending 都阻止选择栏再次发起动作`() {
        val key = itemKey(UUID.randomUUID())
        DownloadPendingAction.entries.forEach { action ->
            val state = DownloadManagerState(pendingActions = mapOf(key to action))

            assertFalse(DownloadSelectionRules.canStartSelectionAction(state))
        }
        assertTrue(DownloadSelectionRules.canStartSelectionAction(DownloadManagerState()))
    }

    @Test
    fun `批次动作失败清除 pending 并留下可见失败终态`() {
        val key = itemKey(UUID.randomUUID())
        val state = DownloadManagerState(pendingActions = mapOf(key to DownloadPendingAction.RETRY))

        val failed = state.failPending(DownloadPendingAction.RETRY, setOf(key))

        assertNull(failed.pendingActions[key])
        assertEquals(DownloadActionFailure.RETRY, failed.actionFailure)
    }
}
