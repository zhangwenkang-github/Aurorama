package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadContainerActionRulesTest {

    @Test
    fun `仅删除中的容器不提供暂停动作`() {
        val container = containerWith(DownloadTaskStatus.DELETING)

        assertEquals(emptyList<DownloadContainerTaskActionSpec>(), container.taskActionSpecs())
    }

    @Test
    fun `失败与下载中混合容器的动作仅声明各自可执行任务`() {
        val failed = task("失败", DownloadTaskStatus.FAILED)
        val running = task("下载中", DownloadTaskStatus.RUNNING)
        val container = containerWith(failed, running)

        val specs = container.taskActionSpecs()
        assertEquals(
            listOf(
                DownloadContainerTaskActionSpec(
                    DownloadContainerTaskAction.PAUSE,
                    setOf(taskKey(running)),
                ),
                DownloadContainerTaskActionSpec(
                    DownloadContainerTaskAction.RETRY,
                    setOf(taskKey(failed)),
                ),
            ),
            specs,
        )
        assertEquals(listOf(running), container.tasksForTaskAction(specs[0]))
        assertEquals(listOf(failed), container.tasksForTaskAction(specs[1]))
    }

    @Test
    fun `已完成与删除中任务不生成无目标动作`() {
        val container =
            containerWith(
                task("已完成", DownloadTaskStatus.COMPLETED),
                task("删除中", DownloadTaskStatus.DELETING),
            )

        assertEquals(emptyList<DownloadContainerTaskActionSpec>(), container.taskActionSpecs())
    }

    @Test
    fun `空容器不生成动作且过期规格不再解析任务`() {
        val running = task("已消失", DownloadTaskStatus.RUNNING)
        val staleSpec =
            DownloadContainerTaskActionSpec(
                DownloadContainerTaskAction.PAUSE,
                setOf(taskKey(running)),
            )
        val empty = container(emptyList())

        assertEquals(emptyList<DownloadContainerTaskActionSpec>(), empty.taskActionSpecs())
        assertEquals(emptyList<DownloadTask>(), empty.tasksForTaskAction(staleSpec))
    }

    private fun containerWith(vararg statuses: DownloadTaskStatus): DownloadHierarchyContainer {
        val entries = statuses.mapIndexed { index, status ->
            val task = task("曲目 $index", status)
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
        return DownloadHierarchyBuilder.build(entries).single()
    }

    private fun containerWith(vararg tasks: DownloadTask): DownloadHierarchyContainer =
        container(
            tasks.mapIndexed { index, task ->
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
        )

    private fun container(entries: List<DownloadHierarchyEntry>) =
        DownloadHierarchyBuilder.build(entries).singleOrNull()
            ?: DownloadHierarchyContainer(
                key = "container:test",
                title = "测试专辑",
                detail = null,
                mediaKind = DownloadMediaKind.MUSIC,
                status = DownloadHierarchyStatus.COMPLETED,
                completedCount = 0,
                totalCount = 0,
                sizeBytes = 0L,
                imageUri = null,
                canDelete = false,
                canOpen = false,
                children = emptyList(),
            )

    private fun task(
        name: String,
        status: DownloadTaskStatus,
    ) =
        DownloadTask(
            itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
            sourceId = "source-$name",
            name = name,
            path = "/tmp/$name.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = 0L,
            mediaKind = DownloadMediaKind.MUSIC,
            albumName = "测试专辑",
        )
}
