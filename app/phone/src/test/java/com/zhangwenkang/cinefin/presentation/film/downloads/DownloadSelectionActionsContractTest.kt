package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyBuilder
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSelectionActionsContractTest {

    @Test
    fun `活动页单个暂停任务只生成继续和删除`() {
        val specs =
            downloadSelectionActionSpecs(
                state = activeState(DownloadTaskStatus.PAUSED),
                group = DownloadTaskGroup.ACTIVE,
            )

        assertEquals(
            listOf(
                DownloadSelectionActionId.RESUME_SELECTED,
                DownloadSelectionActionId.DELETE_SELECTED,
            ),
            specs.map(DownloadSelectionActionSpec::actionId),
        )
        val resume = specs.single { it.actionId == DownloadSelectionActionId.RESUME_SELECTED }
        assertEquals(CoreR.drawable.ic_download, resume.iconRes)
        assertEquals(
            DownloadSelectionLabel(CoreR.string.download_action_resume_selected),
            resume.label,
        )
    }

    @Test
    fun `已完成视频只生成播放和删除`() {
        val specs =
            downloadSelectionActionSpecs(
                state = completedVideoState(),
                group = DownloadTaskGroup.COMPLETED,
            )

        assertEquals(
            listOf(
                DownloadSelectionActionId.PLAY_SELECTED,
                DownloadSelectionActionId.DELETE_SELECTED,
            ),
            specs.map(DownloadSelectionActionSpec::actionId),
        )
        val play = specs.single { it.actionId == DownloadSelectionActionId.PLAY_SELECTED }
        assertEquals(CoreR.drawable.ic_play, play.iconRes)
        assertEquals(
            DownloadSelectionLabel(CoreR.string.download_action_play_selected),
            play.label,
        )
    }

    @Test
    fun `失败任务只生成重试和删除`() {
        val specs =
            downloadSelectionActionSpecs(
                state = failedState(),
                group = DownloadTaskGroup.FAILED,
            )

        assertEquals(
            listOf(
                DownloadSelectionActionId.RETRY_SELECTED,
                DownloadSelectionActionId.DELETE_SELECTED,
            ),
            specs.map(DownloadSelectionActionSpec::actionId),
        )
    }

    @Test
    fun `活动页运行任务只生成暂停和删除`() {
        val specs =
            downloadSelectionActionSpecs(
                state = activeState(DownloadTaskStatus.RUNNING),
                group = DownloadTaskGroup.ACTIVE,
            )

        assertEquals(
            listOf(
                DownloadSelectionActionId.PAUSE_SELECTED,
                DownloadSelectionActionId.DELETE_SELECTED,
            ),
            specs.map(DownloadSelectionActionSpec::actionId),
        )
    }

    @Test
    fun `动作 ID 图标与文案均保持上下文唯一`() {
        val actionSets =
            listOf(
                downloadSelectionActionSpecs(
                    state = completedVideoState(),
                    group = DownloadTaskGroup.COMPLETED,
                ),
                downloadSelectionActionSpecs(
                    state = activeState(DownloadTaskStatus.PAUSED),
                    group = DownloadTaskGroup.ACTIVE,
                ),
                downloadSelectionActionSpecs(
                    state = failedState(),
                    group = DownloadTaskGroup.FAILED,
                ),
            )

        actionSets.forEach { specs ->
            assertEquals(
                specs.size,
                specs.map(DownloadSelectionActionSpec::actionId).distinct().size,
            )
            assertEquals(
                specs.size,
                specs.map(DownloadSelectionActionSpec::iconRes).distinct().size,
            )
            assertTrue(specs.all { it.label.resourceId != 0 })
        }
    }

    @Test
    fun `选择栏只渲染能力契约生成的动作`() {
        val source = downloadsScreenSource()
        val selectionBar =
            source
                .substringAfter("private fun SelectionBar(")
                .substringBefore("@Composable\nprivate fun SelectionActionIcon(")

        assertTrue(
            selectionBar.contains("val actions = downloadSelectionActionSpecs(state, group)")
        )
        assertTrue(selectionBar.contains("actions.forEach { action ->"))
        assertFalse(selectionBar.contains("\"播放选中\""))
        assertFalse(selectionBar.contains("CoreR.drawable.ic_play"))
    }

    private fun activeState(status: DownloadTaskStatus): DownloadManagerState {
        val task =
            downloadTask(
                status = status,
                name = "活动任务",
            )
        val entry = downloadEntry(task)
        return DownloadManagerState(
            isLoading = false,
            activeTasks = listOf(task),
            activeContainers = DownloadHierarchyBuilder.build(listOf(entry)),
            selectionMode = true,
            selection = listOf(entry.key),
        )
    }

    private fun completedVideoState(): DownloadManagerState {
        val entry =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "已完成视频",
                mediaKind = DownloadMediaKind.VIDEO,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "local-video",
            )
        return DownloadManagerState(
            isLoading = false,
            completedContainers = DownloadHierarchyBuilder.build(listOf(entry)),
            selectionMode = true,
            selection = listOf(entry.key),
        )
    }

    private fun failedState(): DownloadManagerState {
        val task =
            downloadTask(
                status = DownloadTaskStatus.FAILED,
                name = "失败任务",
            )
        val entry = downloadEntry(task)
        return DownloadManagerState(
            isLoading = false,
            failedTasks = listOf(task),
            failedContainers = DownloadHierarchyBuilder.build(listOf(entry)),
            selectionMode = true,
            selection = listOf(entry.key),
        )
    }

    private fun downloadEntry(task: DownloadTask) =
        DownloadHierarchyEntry(
            itemId = task.itemId,
            name = task.name,
            mediaKind = task.mediaKind,
            status = task.status,
            task = task,
            totalBytes = task.totalBytes,
        )

    private fun downloadTask(
        status: DownloadTaskStatus,
        name: String,
    ) =
        DownloadTask(
            itemId = UUID.randomUUID(),
            sourceId = "source-$name",
            name = name,
            path = "/tmp/$name.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = 0L,
            mediaKind = DownloadMediaKind.VIDEO,
        )

    private fun downloadsScreenSource(): String =
        sequenceOf(
                File("src/main/java/com/zhangwenkang/cinefin/presentation/film/DownloadsScreen.kt"),
                File(
                    "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/DownloadsScreen.kt"
                ),
            )
            .firstOrNull { it.isFile }
            ?.readText() ?: error("无法定位 DownloadsScreen.kt")
}
