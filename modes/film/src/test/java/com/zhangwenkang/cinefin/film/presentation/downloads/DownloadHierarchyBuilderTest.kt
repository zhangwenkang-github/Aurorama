package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHierarchyBuilderTest {

    private val showId = UUID.randomUUID()
    private val seasonOneId = UUID.randomUUID()
    private val seasonTwoId = UUID.randomUUID()

    private fun videoEntry(
        name: String,
        status: DownloadTaskStatus,
        seasonId: UUID = seasonOneId,
        seasonName: String = "第 1 季",
        episodeIndex: Int,
        sizeBytes: Long = 100L,
        sourceId: String? = if (status == DownloadTaskStatus.COMPLETED) "src-$name" else null,
        updatedAt: Long = 0L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.VIDEO,
            status = status,
            sourceId = sourceId,
            sizeBytes = sizeBytes,
            updatedAt = updatedAt,
            seriesId = showId,
            seasonId = seasonId,
            seriesName = "示例剧",
            seasonName = seasonName,
            episodeIndex = episodeIndex,
        )

    @Test
    fun `episodes group by show then season with aggregated progress`() {
        val entries =
            listOf(
                videoEntry("E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1),
                videoEntry("E2", DownloadTaskStatus.COMPLETED, episodeIndex = 2),
                videoEntry("E3", DownloadTaskStatus.RUNNING, episodeIndex = 3),
                videoEntry(
                    "S2E1",
                    DownloadTaskStatus.FAILED,
                    seasonId = seasonTwoId,
                    seasonName = "第 2 季",
                    episodeIndex = 1,
                ),
            )

        val containers = DownloadHierarchyBuilder.build(entries)

        assertEquals(1, containers.size)
        val show = containers.single()
        assertEquals("示例剧", show.title)
        assertEquals(DownloadMediaKind.VIDEO, show.mediaKind)
        assertEquals(4, show.totalCount)
        assertEquals(2, show.completedCount)
        assertEquals(DownloadHierarchyStatus.FAILED, show.status)
        assertEquals("2/4 集 · 失败", show.detail)
        assertFalse(show.canOpen)

        val seasonOne = show.children[0] as DownloadHierarchySubContainer
        assertEquals("第 1 季", seasonOne.title)
        assertEquals("2/3 集 · 下载中", seasonOne.detail)
        assertEquals(DownloadHierarchyStatus.RUNNING, seasonOne.status)
        assertEquals(listOf("E1", "E2", "E3"), seasonOne.children.map { it.entry.name })

        val seasonTwo = show.children[1] as DownloadHierarchySubContainer
        assertEquals("0/1 集 · 失败", seasonTwo.detail)
    }

    @Test
    fun `movies become single leaf containers with poster and size`() {
        val entry =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "示例电影",
                mediaKind = DownloadMediaKind.VIDEO,
                status = DownloadTaskStatus.COMPLETED,
                sourceId = "src",
                sizeBytes = 2048L,
                imageUri = "file://poster",
            )

        val container = DownloadHierarchyBuilder.build(listOf(entry)).single()

        assertEquals("示例电影", container.title)
        assertEquals("file://poster", container.imageUri)
        assertEquals(DownloadHierarchyStatus.COMPLETED, container.status)
        assertEquals(2048L, container.sizeBytes)
        assertTrue(container.canOpen)
        assertTrue(container.canDelete)
        assertEquals(1, container.children.size)
    }

    @Test
    fun `music groups tracks into album container with cover fallback`() {
        val albumEntries =
            listOf(
                DownloadHierarchyEntry(
                    itemId = UUID.randomUUID(),
                    name = "第一首",
                    mediaKind = DownloadMediaKind.MUSIC,
                    status = DownloadTaskStatus.COMPLETED,
                    sourceId = "src-1",
                    albumName = "示例专辑",
                    artist = "示例艺人",
                    trackIndex = 1,
                    imageUri = null,
                    sizeBytes = 10L,
                ),
                DownloadHierarchyEntry(
                    itemId = UUID.randomUUID(),
                    name = "第二首",
                    mediaKind = DownloadMediaKind.MUSIC,
                    status = DownloadTaskStatus.RUNNING,
                    albumName = "示例专辑",
                    artist = "示例艺人",
                    trackIndex = 2,
                    imageUri = "file://album",
                    sizeBytes = 20L,
                ),
            )

        val container = DownloadHierarchyBuilder.build(albumEntries).single()

        assertEquals("示例专辑", container.title)
        assertEquals("示例艺人 · 1/2 首", container.detail)
        assertEquals(DownloadHierarchyStatus.RUNNING, container.status)
        assertEquals("file://album", container.imageUri)
        assertEquals(2, container.totalCount)
        assertEquals(30L, container.sizeBytes)
        assertEquals(
            listOf("第一首", "第二首"),
            container.children.map { (it as DownloadHierarchyLeaf).entry.name },
        )
    }

    @Test
    fun `books become containers with cover and detail`() {
        val entry =
            DownloadHierarchyEntry(
                itemId = UUID.randomUUID(),
                name = "示例书籍",
                mediaKind = DownloadMediaKind.BOOK,
                status = DownloadTaskStatus.COMPLETED,
                sizeBytes = 2200L,
                imageUri = "file://book",
                bookDetail = "2.1 MB",
            )

        val container = DownloadHierarchyBuilder.build(listOf(entry)).single()

        assertEquals(DownloadMediaKind.BOOK, container.mediaKind)
        assertEquals("示例书籍", container.title)
        assertEquals("2.1 MB", container.detail)
        assertEquals("file://book", container.imageUri)
        assertTrue(container.canOpen)
    }

    @Test
    fun `aggregate status prefers failure then running then paused`() {
        assertEquals(
            DownloadHierarchyStatus.FAILED,
            aggregateStatus(
                listOf(
                    DownloadHierarchyStatus.COMPLETED,
                    DownloadHierarchyStatus.RUNNING,
                    DownloadHierarchyStatus.FAILED,
                )
            ),
        )
        assertEquals(
            DownloadHierarchyStatus.RUNNING,
            aggregateStatus(
                listOf(DownloadHierarchyStatus.PAUSED, DownloadHierarchyStatus.RUNNING)
            ),
        )
        assertEquals(
            DownloadHierarchyStatus.PAUSED,
            aggregateStatus(
                listOf(DownloadHierarchyStatus.PAUSED, DownloadHierarchyStatus.COMPLETED)
            ),
        )
        assertEquals(
            DownloadHierarchyStatus.PENDING,
            aggregateStatus(listOf(DownloadHierarchyStatus.PENDING)),
        )
        assertEquals(
            DownloadHierarchyStatus.COMPLETED,
            aggregateStatus(
                listOf(DownloadHierarchyStatus.COMPLETED, DownloadHierarchyStatus.COMPLETED)
            ),
        )
    }

    @Test
    fun `flattener expands and collapses containers`() {
        val entries =
            listOf(
                videoEntry("E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1),
                videoEntry("E2", DownloadTaskStatus.COMPLETED, episodeIndex = 2),
            )
        val containers = DownloadHierarchyBuilder.build(entries)
        val showKey = containers.single().key
        val seasonKey = (containers.single().children.single() as DownloadHierarchySubContainer).key

        // 默认折叠：只显示容器行。
        val collapsedByDefault = DownloadHierarchyFlattener.flatten(containers, emptySet())
        assertEquals(1, collapsedByDefault.size)
        assertTrue((collapsedByDefault.single() as DownloadHierarchyRow.ContainerRow).collapsed)

        val expanded = DownloadHierarchyFlattener.flatten(containers, setOf(showKey, seasonKey))
        assertEquals(4, expanded.size)
        assertTrue(expanded[0] is DownloadHierarchyRow.ContainerRow)
        assertEquals(1, (expanded[1] as DownloadHierarchyRow.ChildContainerRow).depth)
        assertEquals(2, (expanded[2] as DownloadHierarchyRow.ItemRow).depth)
        assertEquals(2, (expanded[3] as DownloadHierarchyRow.ItemRow).depth)

        // 只展开节目：季容器可见但折叠。
        val showOnly = DownloadHierarchyFlattener.flatten(containers, setOf(showKey))
        assertEquals(2, showOnly.size)
        assertTrue((showOnly[1] as DownloadHierarchyRow.ChildContainerRow).collapsed)

        // 只展开季：子项可见（容器本身仍折叠时子项不显示，这里验证季展开逻辑）。
        val seasonOnly = DownloadHierarchyFlattener.flatten(containers, setOf(showKey, seasonKey))
        assertEquals(2, seasonOnly.count { it is DownloadHierarchyRow.ItemRow })
    }
}
