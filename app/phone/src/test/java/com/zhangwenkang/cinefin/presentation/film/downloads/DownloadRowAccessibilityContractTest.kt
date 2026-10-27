package com.zhangwenkang.cinefin.presentation.film.downloads

import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyLeaf
import com.zhangwenkang.cinefin.film.presentation.downloads.aggregateStatus
import com.zhangwenkang.cinefin.film.presentation.downloads.taskKey
import com.zhangwenkang.cinefin.film.presentation.downloads.toHierarchyStatus
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRowAccessibilityContractTest {

    @Test
    fun `普通任务按状态暴露独立主操作与删除`() {
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.PAUSE,
                    CoreR.string.download_action_pause,
                ),
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                ),
            ),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.RUNNING,
                canDelete = true,
                pending = false,
            ),
        )
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.RESUME,
                    CoreR.string.download_action_resume,
                ),
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                ),
            ),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.PAUSED,
                canDelete = true,
                pending = false,
            ),
        )
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.RETRY,
                    CoreR.string.download_action_retry,
                ),
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                ),
            ),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.FAILED,
                canDelete = true,
                pending = false,
            ),
        )
    }

    @Test
    fun `不可删除或无主操作的任务不保留空动作`() {
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.PAUSE,
                    CoreR.string.download_action_pause,
                )
            ),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.RUNNING,
                canDelete = false,
                pending = false,
            ),
        )
        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.COMPLETED,
                canDelete = false,
                pending = false,
            ),
        )
        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.RUNNING,
                canDelete = true,
                pending = true,
            ),
        )
    }

    @Test
    fun `删除中或无任务仅保留真实删除动作`() {
        val delete =
            DownloadRowActionSpec(
                DownloadRowTaskAction.DELETE,
                CoreR.string.download_action_delete,
            )

        assertEquals(
            listOf(delete),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.DELETING,
                canDelete = true,
                pending = false,
            ),
        )
        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.DELETING,
                canDelete = false,
                pending = false,
            ),
        )
        assertEquals(
            listOf(delete),
            downloadRowActionSpecs(
                status = null,
                canDelete = true,
                pending = false,
            ),
        )
        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadRowActionSpecs(
                status = null,
                canDelete = false,
                pending = false,
            ),
        )
        assertEquals(
            listOf(delete),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.COMPLETED,
                canDelete = true,
                pending = false,
            ),
        )
    }

    @Test
    fun `动作表映射到对应的任务下载回调`() {
        val task = downloadTask(DownloadTaskStatus.RUNNING)

        assertEquals(
            DownloadAction.Pause(task),
            downloadActionForRowAction(task, DownloadRowTaskAction.PAUSE),
        )
        assertEquals(
            DownloadAction.Resume(task),
            downloadActionForRowAction(task, DownloadRowTaskAction.RESUME),
        )
        assertEquals(
            DownloadAction.Retry(task),
            downloadActionForRowAction(task, DownloadRowTaskAction.RETRY),
        )
        assertNull(downloadActionForRowAction(task, DownloadRowTaskAction.DELETE))
    }

    @Test
    fun `顶层容器动作携带真实任务集合且只映射可执行回调`() {
        val failed = downloadTask(DownloadTaskStatus.FAILED, name = "失败")
        val running = downloadTask(DownloadTaskStatus.RUNNING, name = "下载中")
        val container = testContainer(failed, running)

        val specs = downloadContainerRowActionSpecs(container, pending = false)
        val pause = specs.single { it.action == DownloadRowTaskAction.PAUSE }
        val retry = specs.single { it.action == DownloadRowTaskAction.RETRY }

        assertEquals(setOf(taskKey(running)), pause.eligibleTaskKeys)
        assertEquals(setOf(taskKey(failed)), retry.eligibleTaskKeys)
        assertEquals(
            DownloadAction.PauseContainer(container.key, setOf(taskKey(running))),
            downloadActionForContainerRowAction(container.key, pause),
        )
        assertEquals(
            DownloadAction.RetryContainer(container.key, setOf(taskKey(failed))),
            downloadActionForContainerRowAction(container.key, retry),
        )
        val resumeKeys = setOf("item:paused")
        assertEquals(
            DownloadAction.ResumeContainer(container.key, resumeKeys),
            downloadActionForContainerRowAction(
                container.key,
                DownloadRowActionSpec(
                    DownloadRowTaskAction.RESUME,
                    CoreR.string.download_action_resume,
                    resumeKeys,
                ),
            ),
        )
        assertNull(
            downloadActionForContainerRowAction(
                container.key,
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                ),
            )
        )
    }

    @Test
    fun `顶层容器无可执行任务时只保留真实删除能力`() {
        val deleting = downloadTask(DownloadTaskStatus.DELETING, name = "删除中")
        val noDeleteContainer = testContainer(deleting, canDelete = false)

        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadContainerRowActionSpecs(noDeleteContainer, pending = false),
        )

        val deleteContainer = testContainer(deleting, canDelete = true)
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                )
            ),
            downloadContainerRowActionSpecs(deleteContainer, pending = false),
        )
        assertEquals(
            emptyList<DownloadRowActionSpec>(),
            downloadContainerRowActionSpecs(deleteContainer, pending = true),
        )
    }

    @Test
    fun `pending 指示保持原动作位的宽度与高度`() {
        assertEquals(44.dp, PendingDownloadActionMetrics.Width)
        assertEquals(48.dp, PendingDownloadActionMetrics.Height)
    }

    @Test
    fun `活动叶行内容区无空点击仅在可打开或多选时可点击`() {
        assertNull(
            downloadLeafContentAction(
                selectionMode = false,
                mediaKind = DownloadMediaKind.MUSIC,
                completed = false,
            )
        )
        assertEquals(
            DownloadRowContentAction.OPEN_DETAILS,
            downloadLeafContentAction(
                selectionMode = false,
                mediaKind = DownloadMediaKind.BOOK,
                completed = false,
            ),
        )
        assertEquals(
            DownloadRowContentAction.OPEN_DETAILS,
            downloadLeafContentAction(
                selectionMode = false,
                mediaKind = DownloadMediaKind.VIDEO,
                completed = true,
            ),
        )
        assertEquals(
            DownloadRowContentAction.TOGGLE_SELECTION,
            downloadLeafContentAction(
                selectionMode = true,
                mediaKind = DownloadMediaKind.MUSIC,
                completed = false,
            ),
        )
    }

    @Test
    fun `顶层行多选时标签跟随切换选择动作`() {
        assertEquals(
            DownloadRowContentAction.OPEN_DETAILS,
            downloadTopLevelContentAction(selectionMode = false),
        )
        assertEquals(
            DownloadRowContentAction.TOGGLE_SELECTION,
            downloadTopLevelContentAction(selectionMode = true),
        )
    }

    @Test
    fun `内容区点击标签使用既有可理解文案`() {
        assertEquals(
            CoreR.string.download_detail_open,
            DownloadRowContentAction.OPEN_DETAILS.labelRes,
        )
        assertEquals(
            CoreR.string.download_action_select,
            DownloadRowContentAction.TOGGLE_SELECTION.labelRes,
        )
    }

    @Test
    fun `顶层钻取卡片只由内容区承接点击长按且尾部动作独立`() {
        val source = downloadRowsSource()
        val card =
            source
                .substringAfter("internal fun DownloadTopLevelCard(")
                .substringBefore("/** 平铺单条目行")

        assertTrue(
            "顶层卡片本身不得承接 click / long-click，否则动作会被整卡合并",
            Regex("""CinefinCard\([\s\S]*?onClick = null,\s*onLongClick = null,""")
                .containsMatchIn(card),
        )
        assertTrue(
            "顶层卡片必须把 click / long-click 限制在内容区",
            card.contains(".downloadRowContentInteraction("),
        )
        assertTrue(
            "顶层卡片必须渲染与内容区互为兄弟的独立任务动作",
            card.contains("DownloadContainerActionIcon("),
        )
    }

    @Test
    fun `长按输入使用稳定 key 并读取最新回调`() {
        val source = downloadRowsSource()

        assertTrue(
            "长按输入 key 必须只使用稳定 interactionSource",
            Regex("""pointerInput\(\s*interactionSource\s*\)\s*\{""").containsMatchIn(source),
        )
        assertFalse(
            "pointerInput key 不得包含每次组合变化的 onLongPress",
            Regex("""pointerInput\([^)]*onLongPress[^)]*\)""").containsMatchIn(source),
        )
        assertTrue(
            "长按回调必须桥接到 rememberUpdatedState",
            Regex("""val latestOnLongPress by rememberUpdatedState\(onLongPress\)""")
                .containsMatchIn(source),
        )
        assertTrue(
            "pointerInput 必须通过稳定桥接调用最新回调",
            Regex("""onLongPress = \{\s*latestOnLongPress\(\)\s*\}""").containsMatchIn(source),
        )
        assertTrue(
            "协程未正常释放时必须补发 PressInteraction.Cancel",
            Regex(
                    """finally\s*\{\s*if \(!releasedNormally\)\s*\{\s*interactionSource\.tryEmit\(PressInteraction\.Cancel\(press\)\)\s*\}\s*\}"""
                )
                .containsMatchIn(source),
        )
    }

    private fun downloadRowsSource(): String =
        sequenceOf(
                File(
                    "src/main/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadRows.kt"
                ),
                File(
                    "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadRows.kt"
                ),
            )
            .firstOrNull { it.isFile }
            ?.readText() ?: error("无法定位 DownloadRows.kt")

    private fun testContainer(
        vararg tasks: DownloadTask,
        canDelete: Boolean = true,
    ): DownloadHierarchyContainer {
        val entries = tasks.mapIndexed { index, task ->
            DownloadHierarchyEntry(
                itemId = task.itemId,
                name = task.name,
                mediaKind = task.mediaKind,
                status = task.status,
                task = task,
                totalBytes = task.totalBytes,
                albumName = task.albumName,
                trackIndex = index,
            )
        }
        return DownloadHierarchyContainer(
            key = "container:test",
            title = "测试容器",
            detail = null,
            mediaKind = DownloadMediaKind.MUSIC,
            status = aggregateStatus(entries.map { it.toHierarchyStatus() }),
            completedCount = 0,
            totalCount = entries.size,
            sizeBytes = entries.sumOf { it.sizeBytes },
            imageUri = null,
            canDelete = canDelete,
            canOpen = false,
            children = entries.map { DownloadHierarchyLeaf(it.key, it) },
        )
    }

    private fun downloadTask(
        status: DownloadTaskStatus,
        name: String = "测试条目",
    ) =
        DownloadTask(
            itemId = UUID.randomUUID(),
            sourceId = "source",
            name = name,
            path = "/tmp/test.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
            updatedAt = 0L,
        )
}
