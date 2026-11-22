package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W7-T4 下载中心展示口径（需求 37/39/44/45/46/51）：
 *
 * 「其他」折叠组 / 同级排序（名称 / 下载时间 / 大小）/ 书籍类型分区与格式徽标 / 媒体筛选状态分槽。
 */
class DownloadCenterDisplayRulesTest {

    private val showId = UUID.randomUUID()
    private val seasonOneId = UUID.randomUUID()
    private val seasonTwoId = UUID.randomUUID()
    private val specialSeasonId = UUID.randomUUID()

    private fun episode(
        name: String,
        status: DownloadTaskStatus = DownloadTaskStatus.COMPLETED,
        seasonId: UUID = seasonOneId,
        seasonName: String? = "第 1 季",
        episodeIndex: Int = 1,
        sizeBytes: Long = 100L,
        updatedAt: Long = 0L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.VIDEO,
            status = status,
            sourceId = if (status == DownloadTaskStatus.COMPLETED) "src-$name" else null,
            sizeBytes = sizeBytes,
            totalBytes = sizeBytes,
            updatedAt = updatedAt,
            seriesId = showId,
            seasonId = seasonId,
            seriesName = "示例剧",
            seasonName = seasonName,
            episodeIndex = episodeIndex,
        )

    private fun movie(
        name: String,
        status: DownloadTaskStatus = DownloadTaskStatus.COMPLETED,
        sizeBytes: Long = 100L,
        updatedAt: Long = 0L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.VIDEO,
            status = status,
            sourceId = if (status == DownloadTaskStatus.COMPLETED) "src-$name" else null,
            sizeBytes = sizeBytes,
            totalBytes = sizeBytes,
            updatedAt = updatedAt,
        )

    private fun track(
        name: String,
        albumName: String,
        index: Int,
        sizeBytes: Long = 100L,
        updatedAt: Long = 0L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.MUSIC,
            status = DownloadTaskStatus.COMPLETED,
            sourceId = "src-$name",
            sizeBytes = sizeBytes,
            totalBytes = sizeBytes,
            updatedAt = updatedAt,
            albumName = albumName,
            artist = "示例艺人",
            trackIndex = index,
        )

    private fun book(
        name: String,
        section: DownloadBookSection?,
        container: String?,
        sizeBytes: Long = 100L,
    ) =
        DownloadHierarchyEntry(
            itemId = UUID.randomUUID(),
            name = name,
            mediaKind = DownloadMediaKind.BOOK,
            status = DownloadTaskStatus.COMPLETED,
            sourceId = "src-$name",
            sizeBytes = sizeBytes,
            bookContainer = container,
            bookSection = section,
        )

    private fun singleShow(entries: List<DownloadHierarchyEntry>): DownloadHierarchyContainer =
        DownloadHierarchyBuilder.build(entries).single {
            it.mediaKind == DownloadMediaKind.VIDEO &&
                it.children.any { child -> child is DownloadHierarchySubContainer }
        }

    // ---- 需求 37：特别篇 / SP / 无季归属 → 显式「其他」折叠组 ----

    @Test
    fun `specials and seasonless episodes merge into one other group after seasons`() {
        val show =
            singleShow(
                listOf(
                    episode("S1E1", seasonId = seasonOneId, seasonName = "第 1 季", episodeIndex = 1),
                    episode(
                        "SP1",
                        seasonId = specialSeasonId,
                        seasonName = "Specials",
                        episodeIndex = 1,
                    ),
                    episode(
                        "SP2",
                        seasonId = specialSeasonId,
                        seasonName = "Specials",
                        episodeIndex = 2,
                    ),
                    episode(
                        "无季集",
                        seasonId = UUID.randomUUID(),
                        seasonName = null,
                        episodeIndex = 3,
                    ),
                )
            )

        assertEquals(
            listOf("第 1 季", "其他"),
            show.children.filterIsInstance<DownloadHierarchySubContainer>().map { it.title },
        )
        val seasons = show.children.filterIsInstance<DownloadHierarchySubContainer>()
        val other = seasons[1]
        assertTrue(other.otherGroup)
        assertFalse(seasons[0].otherGroup)
        assertEquals(listOf("SP1", "SP2", "无季集"), other.children.map { it.entry.name })
        assertEquals(4, show.totalCount)
    }

    @Test
    fun `season grouping treats specials aliases and missing names as other`() {
        assertTrue(DownloadSeasonGrouping.isOther(null))
        assertTrue(DownloadSeasonGrouping.isOther("  "))
        assertTrue(DownloadSeasonGrouping.isOther("Specials"))
        assertTrue(DownloadSeasonGrouping.isOther(" special "))
        assertTrue(DownloadSeasonGrouping.isOther("特别篇"))
        assertFalse(DownloadSeasonGrouping.isOther("第 1 季"))
        assertFalse(DownloadSeasonGrouping.isOther("Season 2"))
    }

    // ---- 需求 51：同级排序（名称 / 下载时间 / 大小），默认 = 现有分组排序 ----

    @Test
    fun `name sort orders sibling containers without crossing levels`() {
        val containers =
            DownloadHierarchyBuilder.build(
                listOf(
                    movie("B 电影", sizeBytes = 100L, updatedAt = 10L),
                    movie("A 电影", sizeBytes = 300L, updatedAt = 30L),
                    movie("C 电影", sizeBytes = 200L, updatedAt = 20L),
                )
            )

        assertEquals(
            listOf("A 电影", "B 电影", "C 电影"),
            DownloadDrilldownRules.sortForDisplay(containers, DownloadSortMode.NAME).map {
                it.title
            },
        )
        // 默认排序仍按状态分组：全完成 → 最近更新时间倒序。
        assertEquals(
            listOf("A 电影", "C 电影", "B 电影"),
            DownloadDrilldownRules.sortForDisplay(containers).map { it.title },
        )
    }

    @Test
    fun `download time sort orders siblings by latest update descending`() {
        val containers =
            DownloadHierarchyBuilder.build(
                listOf(
                    movie("旧", updatedAt = 10L),
                    movie("最新", updatedAt = 30L),
                    movie("中间", updatedAt = 20L),
                )
            )

        assertEquals(
            listOf("最新", "中间", "旧"),
            DownloadDrilldownRules.sortForDisplay(containers, DownloadSortMode.DOWNLOAD_TIME).map {
                it.title
            },
        )
    }

    @Test
    fun `size sort orders siblings by total bytes descending`() {
        val containers =
            DownloadHierarchyBuilder.build(
                listOf(
                    movie("小", sizeBytes = 100L),
                    movie("大", sizeBytes = 900L),
                    movie("中", sizeBytes = 500L),
                )
            )

        assertEquals(
            listOf("大", "中", "小"),
            DownloadDrilldownRules.sortForDisplay(containers, DownloadSortMode.SIZE).map {
                it.title
            },
        )
    }

    @Test
    fun `detail rows sort episodes inside a season and keep other group last`() {
        val show =
            singleShow(
                listOf(
                    episode("E3", seasonId = seasonOneId, seasonName = "第 1 季", episodeIndex = 3),
                    episode("E1", seasonId = seasonOneId, seasonName = "第 1 季", episodeIndex = 1),
                    episode("E2", seasonId = seasonOneId, seasonName = "第 1 季", episodeIndex = 2),
                    episode("S2E1", seasonId = seasonTwoId, seasonName = "第 2 季", episodeIndex = 1),
                    episode(
                        "SP",
                        seasonId = specialSeasonId,
                        seasonName = "Specials",
                        episodeIndex = 1,
                    ),
                )
            )

        val seasonKeys = show.children.map { it.key }.toSet()

        val byName = detailRows(show, seasonKeys, DownloadSortMode.NAME)
        assertEquals(
            listOf("第 1 季", "第 2 季", "其他"),
            byName.filterIsInstance<DownloadDetailRow.Season>().map { it.season.title },
        )
        assertEquals(
            listOf("E1", "E2", "E3", "S2E1", "SP"),
            byName.filterIsInstance<DownloadDetailRow.Item>().map { it.entry.name },
        )

        // 默认排序（季内按集号）不受排序模式影响。
        val byDefault = detailRows(show, seasonKeys)
        assertEquals(
            listOf("E1", "E2", "E3", "S2E1", "SP"),
            byDefault.filterIsInstance<DownloadDetailRow.Item>().map { it.entry.name },
        )
    }

    // ---- 需求 44 / 45：书籍类型分区与格式徽标 ----

    @Test
    fun `book sections group by kind in declared order and fall back to e-book`() {
        val containers =
            DownloadHierarchyBuilder.build(
                listOf(
                    book("漫画书", DownloadBookSection.COMIC, "cbz"),
                    book("有声书", DownloadBookSection.AUDIOBOOK, "m4b"),
                    book("电子书", DownloadBookSection.EBOOK, "epub"),
                    book("识别不了", section = null, container = null),
                )
            )

        val groups = DownloadBookSectionRules.group(containers)

        assertEquals(
            listOf(
                DownloadBookSection.EBOOK,
                DownloadBookSection.AUDIOBOOK,
                DownloadBookSection.COMIC,
            ),
            groups.map { it.section },
        )
        assertEquals(
            listOf("电子书", "识别不了"),
            groups.first().containers.map { it.title }.sorted(),
        )
        assertEquals("M4B", groups[1].containers.single().formatLabel)
        assertEquals("CBZ", groups[2].containers.single().formatLabel)
    }

    @Test
    fun `book format badge only shows known extensions taken from server path`() {
        assertEquals("EPUB", DownloadBookFormatRules.badge("epub"))
        assertEquals("M4B", DownloadBookFormatRules.badge("M4B"))
        assertEquals("CBZ", DownloadBookFormatRules.badge("cbz"))
        assertNull(DownloadBookFormatRules.badge(null))
        assertNull(DownloadBookFormatRules.badge("book"))
        assertNull(DownloadBookFormatRules.badge("xyz"))

        assertEquals("epub", DownloadBookFormatRules.containerOf("/media/Books/示例.epub"))
        assertEquals("cbr", DownloadBookFormatRules.containerOf("C:\\Media\\漫画.cbr"))
        assertNull(DownloadBookFormatRules.containerOf("/media/Books/无扩展名"))
        assertNull(DownloadBookFormatRules.containerOf(null))

        assertEquals(
            DownloadBookSection.COMIC,
            DownloadBookFormatRules.section("Book", "cbz"),
        )
        assertEquals(
            DownloadBookSection.AUDIOBOOK,
            DownloadBookFormatRules.section("Book", "m4b"),
        )
        assertEquals(
            DownloadBookSection.EBOOK,
            DownloadBookFormatRules.section(null, null),
        )
    }

    // ---- 需求 46：类型筛选 chips 即分区载体（折叠 / 选择 / 排序按媒体类型分槽） ----

    @Test
    fun `switch media filter keeps selection and expansion per media kind`() {
        val video = episode("E1")
        val song = track("第一首", albumName = "示例专辑", index = 1)
        val videoKey = video.key
        val songKey = song.key
        val state =
            DownloadManagerState(
                isLoading = false,
                activeContainers = DownloadHierarchyBuilder.build(listOf(video, song)),
                mediaFilter = DownloadMediaFilter.ALL,
                selectionMode = true,
                selection = listOf(videoKey),
                expandedKeys = setOf("container:VIDEO:show"),
                sortModes = mapOf(DownloadMediaFilter.ALL to DownloadSortMode.NAME),
            )

        val music = state.switchMediaFilter(DownloadMediaFilter.MUSIC)
        assertEquals(DownloadMediaFilter.MUSIC, music.mediaFilter)
        assertTrue(music.selection.isEmpty())
        assertFalse(music.selectionMode)
        assertTrue(music.expandedKeys.isEmpty())
        assertEquals(DownloadSortMode.DEFAULT, music.sortMode)

        val musicSelected =
            music.copy(selection = listOf(songKey), selectionMode = true).withCurrentSelectionSlot()
        val backToVideo = musicSelected.switchMediaFilter(DownloadMediaFilter.ALL)
        assertEquals(listOf(videoKey), backToVideo.selection)
        assertEquals(setOf("container:VIDEO:show"), backToVideo.expandedKeys)
        assertEquals(DownloadSortMode.NAME, backToVideo.sortMode)

        val backToMusic = backToVideo.switchMediaFilter(DownloadMediaFilter.MUSIC)
        assertEquals(listOf(songKey), backToMusic.selection)
    }

    @Test
    fun `select all scope follows current media filter`() {
        val video = episode("E1")
        val song = track("第一首", albumName = "示例专辑", index = 1)
        val state =
            DownloadManagerState(
                isLoading = false,
                activeContainers = DownloadHierarchyBuilder.build(listOf(video, song)),
                mediaFilter = DownloadMediaFilter.MUSIC,
            )

        assertEquals(
            listOf(song.key),
            DownloadSelectionRules.keysFor(state, DownloadTaskGroup.ACTIVE),
        )

        val videoState =
            state
                .switchMediaFilter(DownloadMediaFilter.VIDEO)
                .copy(selectionMode = true, selection = emptyList())
                .let { current ->
                    current.copy(
                        selection =
                            DownloadSelectionRules.keysFor(
                                current,
                                DownloadTaskGroup.ACTIVE,
                            )
                    )
                }

        assertEquals(listOf(video.key), videoState.selection)
        assertFalse(videoState.selection.contains(song.key))
    }
}
