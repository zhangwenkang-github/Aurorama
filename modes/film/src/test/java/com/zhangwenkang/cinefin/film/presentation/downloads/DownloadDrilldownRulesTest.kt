package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W59 下载页钻取式 IA：顶层排序 / 可钻取判定 / 自动展开 / 聚合 / 行扁平化。 */
class DownloadDrilldownRulesTest {

    private val showId = UUID.randomUUID()
    private val seasonOneId = UUID.randomUUID()
    private val seasonTwoId = UUID.randomUUID()

    private fun episode(
        name: String,
        status: DownloadTaskStatus,
        seasonId: UUID = seasonOneId,
        seasonName: String = "第 1 季",
        episodeIndex: Int = 1,
        sizeBytes: Long = 100L,
        downloadedBytes: Long = 0L,
        speed: Long = 0L,
        updatedAt: Long = 0L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.VIDEO,
            status = status,
            sizeBytes = sizeBytes,
            downloadedBytes = downloadedBytes,
            totalBytes = sizeBytes,
            updatedAt = updatedAt,
            seriesId = showId,
            seasonId = seasonId,
            seriesName = "示例剧",
            seasonName = seasonName,
            episodeIndex = episodeIndex,
            task =
                if (status == DownloadTaskStatus.RUNNING) {
                    DownloadTask(
                        itemId = UUID.randomUUID(),
                        sourceId = "src-$name",
                        name = name,
                        path = "/tmp/$name.download",
                        downloadId = null,
                        status = DownloadTaskStatus.RUNNING,
                        failureReason = null,
                        downloadedBytes = downloadedBytes,
                        totalBytes = sizeBytes,
                        updatedAt = updatedAt,
                        speedBytesPerSecond = speed,
                    )
                } else {
                    null
                },
        )

    private fun movie(name: String, status: DownloadTaskStatus, updatedAt: Long = 0L) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.VIDEO,
            status = status,
            sizeBytes = 500L,
            updatedAt = updatedAt,
        )

    private fun containers(): List<DownloadHierarchyContainer> =
        DownloadHierarchyBuilder.build(
            listOf(
                episode("E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1, updatedAt = 10L),
                episode("E2", DownloadTaskStatus.RUNNING, episodeIndex = 2, updatedAt = 20L),
                movie("已完成电影", DownloadTaskStatus.COMPLETED, updatedAt = 30L),
            )
        )

    @Test
    fun `顶层排序 = 进行中 失败 已完成`() {
        val running =
            DownloadHierarchyBuilder.build(listOf(episode("E1", DownloadTaskStatus.RUNNING)))
        val failed =
            DownloadHierarchyBuilder.build(listOf(movie("失败电影", DownloadTaskStatus.FAILED)))
        val completed =
            DownloadHierarchyBuilder.build(listOf(movie("完成电影", DownloadTaskStatus.COMPLETED)))

        val sorted = DownloadDrilldownRules.sortForDisplay(completed + failed + running)

        assertEquals(
            listOf(
                DownloadHierarchyStatus.RUNNING,
                DownloadHierarchyStatus.FAILED,
                DownloadHierarchyStatus.COMPLETED,
            ),
            sorted.map { it.status },
        )
    }

    @Test
    fun `Show 与专辑可钻取 电影与书籍是平铺条目`() {
        val containers = containers()
        val show = containers.first { it.title == "示例剧" }
        val movieContainer = containers.first { it.title == "已完成电影" }

        assertTrue(DownloadDrilldownRules.isDrilldown(show))
        assertFalse(DownloadDrilldownRules.isDrilldown(movieContainer))

        val album =
            DownloadHierarchyBuilder.build(
                listOf(
                    DownloadHierarchyEntry(
                        itemId = UUID.randomUUID(),
                        name = "曲目 1",
                        mediaKind = DownloadMediaKind.MUSIC,
                        status = DownloadTaskStatus.COMPLETED,
                        albumName = "示例专辑",
                        trackIndex = 1,
                    )
                )
            )
        assertTrue(DownloadDrilldownRules.isDrilldown(album.single()))

        val book =
            DownloadHierarchyBuilder.build(
                listOf(
                    DownloadHierarchyEntry(
                        itemId = UUID.randomUUID(),
                        name = "示例书籍",
                        mediaKind = DownloadMediaKind.BOOK,
                        status = DownloadTaskStatus.COMPLETED,
                    )
                )
            )
        assertFalse(DownloadDrilldownRules.isDrilldown(book.single()))
    }

    @Test
    fun `自动展开第一个进行中的季`() {
        val entries =
            listOf(
                episode("S1E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1),
                episode(
                    "S2E1",
                    DownloadTaskStatus.PAUSED,
                    seasonId = seasonTwoId,
                    seasonName = "第 2 季",
                ),
                episode(
                    "S3E1",
                    DownloadTaskStatus.RUNNING,
                    seasonId = UUID.randomUUID(),
                    seasonName = "第 3 季",
                ),
            )
        val show = DownloadHierarchyBuilder.build(entries).single()

        val key = DownloadDrilldownRules.autoExpandSeasonKey(show)

        assertEquals(
            "第 3 季",
            show.children
                .filterIsInstance<DownloadHierarchySubContainer>()
                .first { it.key == key }
                .title,
        )
    }

    @Test
    fun `没有进行中时展开第一个未完成的季 全完成不展开`() {
        val paused =
            DownloadHierarchyBuilder.build(
                    listOf(
                        episode("S1E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1),
                        episode(
                            "S2E1",
                            DownloadTaskStatus.FAILED,
                            seasonId = seasonTwoId,
                            seasonName = "第 2 季",
                        ),
                    )
                )
                .single()
        assertEquals("第 2 季", titleOf(paused, DownloadDrilldownRules.autoExpandSeasonKey(paused)))

        val allCompleted =
            DownloadHierarchyBuilder.build(listOf(episode("E1", DownloadTaskStatus.COMPLETED)))
                .single()
        assertNull(DownloadDrilldownRules.autoExpandSeasonKey(allCompleted))
    }

    @Test
    fun `详情聚合 = 容器内条目字节与速度之和`() {
        val entries =
            listOf(
                episode(
                    "E1",
                    DownloadTaskStatus.COMPLETED,
                    episodeIndex = 1,
                    sizeBytes = 1000L,
                ),
                episode(
                    "E2",
                    DownloadTaskStatus.RUNNING,
                    episodeIndex = 2,
                    sizeBytes = 2000L,
                    downloadedBytes = 500L,
                    speed = 128L,
                ),
            )
        val show = DownloadHierarchyBuilder.build(entries).single()

        val aggregate = DownloadDrilldownRules.aggregate(show)

        assertEquals(1500L, aggregate.downloadedBytes)
        assertEquals(3000L, aggregate.totalBytes)
        assertEquals(128L, aggregate.speedBytesPerSecond)
    }

    @Test
    fun `季展开后行序 = 季卡加该季剧集 折叠季只留季卡`() {
        val entries =
            listOf(
                episode("S1E1", DownloadTaskStatus.COMPLETED, episodeIndex = 1),
                episode("S1E2", DownloadTaskStatus.COMPLETED, episodeIndex = 2),
                episode(
                    "S2E1",
                    DownloadTaskStatus.COMPLETED,
                    seasonId = seasonTwoId,
                    seasonName = "第 2 季",
                ),
            )
        val show = DownloadHierarchyBuilder.build(entries).single()
        val seasonOne = show.children.first() as DownloadHierarchySubContainer

        val collapsed = detailRows(show, emptySet())
        assertEquals(
            listOf("第 1 季", "第 2 季"),
            collapsed.map { (it as DownloadDetailRow.Season).season.title },
        )

        val expanded = detailRows(show, setOf(seasonOne.key))
        assertEquals(
            listOf("season", "item", "item", "season"),
            expanded.map {
                when (it) {
                    is DownloadDetailRow.Season -> "season"
                    is DownloadDetailRow.Item -> "item"
                }
            },
        )
        assertEquals(
            listOf("S1E1", "S1E2"),
            expanded.filterIsInstance<DownloadDetailRow.Item>().map { it.entry.name },
        )
    }

    private fun titleOf(container: DownloadHierarchyContainer, key: String?): String? =
        container.children
            .filterIsInstance<DownloadHierarchySubContainer>()
            .firstOrNull { it.key == key }
            ?.title
}
