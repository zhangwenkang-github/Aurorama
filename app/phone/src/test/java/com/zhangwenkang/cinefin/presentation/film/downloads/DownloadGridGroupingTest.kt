package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyStatus
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGridGroupingTest {

    @Test
    fun `collapsed containers pair up on tablet`() {
        val rows =
            listOf(
                containerRow("container:1", collapsed = true),
                containerRow("container:2", collapsed = true),
                containerRow("container:3", collapsed = true),
            )

        val blocks = DownloadGridGrouping.group(rows)

        assertEquals(2, blocks.size)
        val pair = blocks[0] as DownloadGridBlock.Pair
        assertEquals("container:1", pair.first.key)
        assertEquals("container:2", pair.second.key)
        val single = blocks[1] as DownloadGridBlock.Single
        assertEquals("container:3", single.row.key)
    }

    @Test
    fun `expanded container keeps its children full width`() {
        val rows =
            listOf(
                containerRow("container:1", collapsed = false),
                itemRow("item:1"),
                itemRow("item:2"),
                containerRow("container:2", collapsed = true),
            )

        val blocks = DownloadGridGrouping.group(rows)

        assertEquals(4, blocks.size)
        assertTrue(blocks.all { it is DownloadGridBlock.Single })
        assertEquals(
            listOf("container:1", "item:1", "item:2", "container:2"),
            blocks.map { it.key },
        )
    }

    @Test
    fun `expanded container is not paired with the next collapsed one`() {
        val rows =
            listOf(
                containerRow("container:1", collapsed = false),
                itemRow("item:1"),
                containerRow("container:2", collapsed = true),
                containerRow("container:3", collapsed = true),
            )

        val blocks = DownloadGridGrouping.group(rows)

        assertEquals(
            listOf("container:1", "item:1", "pair:container:2|container:3"),
            blocks.map { it.key },
        )
    }

    private fun containerRow(key: String, collapsed: Boolean): DownloadHierarchyRow.ContainerRow =
        DownloadHierarchyRow.ContainerRow(
            key = key,
            container =
                DownloadHierarchyContainer(
                    key = key,
                    title = key,
                    detail = null,
                    mediaKind = DownloadMediaKind.VIDEO,
                    status = DownloadHierarchyStatus.COMPLETED,
                    completedCount = 1,
                    totalCount = 1,
                    sizeBytes = 100L,
                    imageUri = null,
                    canDelete = false,
                    canOpen = true,
                    children = emptyList(),
                ),
            depth = 0,
            collapsed = collapsed,
        )

    private fun itemRow(key: String): DownloadHierarchyRow.ItemRow =
        DownloadHierarchyRow.ItemRow(
            key = key,
            entry =
                DownloadHierarchyEntry(
                    itemId = UUID.randomUUID(),
                    name = key,
                    mediaKind = DownloadMediaKind.VIDEO,
                    status = DownloadTaskStatus.COMPLETED,
                ),
            depth = 1,
        )
}
