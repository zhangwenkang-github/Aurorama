package com.zhangwenkang.cinefin.presentation.offline

import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchySubContainer
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W36：离线媒体可见性（「允许离线模式观看」过滤）与层级组织。 */
class OfflineVisibilityTest {

    private val seriesId = UUID.randomUUID()
    private val seasonId = UUID.randomUUID()

    @Test
    fun hiddenEntriesAreFilteredByDefault() {
        val visible = sampleVideo(name = "允许的", allowOffline = true)
        val hidden = sampleVideo(name = "关闭的", allowOffline = false)

        assertEquals(
            listOf("允许的"),
            OfflineMediaVisibility.visibleEntries(listOf(visible, hidden), includeHidden = false)
                .map { it.name },
        )
        assertEquals(
            listOf("允许的", "关闭的"),
            OfflineMediaVisibility.visibleEntries(listOf(visible, hidden), includeHidden = true)
                .map { it.name },
        )
    }

    @Test
    fun episodesAreOrganizedIntoShowThenSeason() {
        val entries =
            listOf(
                sampleEpisode(name = "第 1 集", episodeIndex = 1, allowOffline = true),
                sampleEpisode(name = "第 2 集", episodeIndex = 2, allowOffline = true),
            )

        val containers = OfflineMediaVisibility.buildHierarchy(entries, includeHidden = false)
        assertEquals(1, containers.size)
        val show = containers.first()
        assertEquals("测试剧集", show.title)
        assertEquals(2, show.totalCount)

        val season = show.children.single() as DownloadHierarchySubContainer
        assertEquals("第 1 季", season.title)
        assertEquals(listOf("第 1 集", "第 2 集"), season.children.map { it.entry.name })
    }

    @Test
    fun closingOneEpisodeRemovesItFromShowAggregate() {
        val entries =
            listOf(
                sampleEpisode(name = "第 1 集", episodeIndex = 1, allowOffline = true),
                sampleEpisode(name = "第 2 集", episodeIndex = 2, allowOffline = false),
            )

        val containers = OfflineMediaVisibility.buildHierarchy(entries, includeHidden = false)
        val show = containers.single()
        assertEquals(1, show.totalCount)
        assertEquals("1/1 集 · 已完成", show.detail)
    }

    @Test
    fun musicIsGroupedByAlbumWithTrackOrder() {
        val entries =
            listOf(
                sampleMusic(name = "曲目 B", trackIndex = 2),
                sampleMusic(name = "曲目 A", trackIndex = 1),
            )

        val containers = OfflineMediaVisibility.buildHierarchy(entries, includeHidden = false)
        val album = containers.single()
        assertEquals("测试专辑", album.title)
        assertEquals(listOf("曲目 A", "曲目 B"), album.children.map { it.childName() })
    }

    @Test
    fun hiddenBookDisappearsButManageViewKeepsIt() {
        val entries =
            listOf(
                sampleBook(name = "开着的书", allowOffline = true),
                sampleBook(name = "关着的书", allowOffline = false),
            )

        assertTrue(
            OfflineMediaVisibility.buildHierarchy(entries, includeHidden = false).none {
                it.title == "关着的书"
            }
        )
        assertTrue(
            OfflineMediaVisibility.buildHierarchy(entries, includeHidden = true).any {
                it.title == "关着的书"
            }
        )
    }

    @Test
    fun offlineEntriesBecomeCompletedHierarchyEntries() {
        val containers =
            OfflineMediaVisibility.buildHierarchy(
                listOf(sampleVideo(name = "电影", allowOffline = true)),
                includeHidden = false,
            )
        val movie = containers.single()
        assertEquals("电影", movie.title)
        assertTrue(movie.canOpen)
    }

    @Test
    fun offlineLibraryOnlyKeepsVideos() {
        // W36 补充要求：离线媒体库只展示已下载节目（视频）；音乐 / 书籍走各自入口。
        val entries =
            listOf(
                sampleVideo(name = "电影", allowOffline = true),
                sampleMusic(name = "曲目", trackIndex = 1),
                sampleBook(name = "书", allowOffline = true),
            )

        val videos = OfflineMediaVisibility.videoOnly(entries)
        assertEquals(listOf("电影"), videos.map { it.name })
        assertEquals(
            listOf("电影"),
            OfflineMediaVisibility.buildHierarchy(videos, includeHidden = false).map { it.title },
        )
    }

    private fun sampleVideo(name: String, allowOffline: Boolean) =
        OfflineMediaEntry(
            itemId = UUID.randomUUID(),
            name = name,
            kind = OfflineMediaEntryKind.VIDEO,
            sizeBytes = 1024L,
            allowOffline = allowOffline,
        )

    private fun sampleEpisode(name: String, episodeIndex: Int, allowOffline: Boolean) =
        OfflineMediaEntry(
            itemId = UUID.randomUUID(),
            name = name,
            kind = OfflineMediaEntryKind.VIDEO,
            isEpisode = true,
            seriesId = seriesId,
            seasonId = seasonId,
            seriesName = "测试剧集",
            seasonName = "第 1 季",
            episodeIndex = episodeIndex,
            seasonIndex = 1,
            allowOffline = allowOffline,
        )

    private fun sampleMusic(name: String, trackIndex: Int) =
        OfflineMediaEntry(
            itemId = UUID.randomUUID(),
            name = name,
            kind = OfflineMediaEntryKind.MUSIC,
            albumName = "测试专辑",
            artist = "测试艺人",
            trackIndex = trackIndex,
        )

    private fun sampleBook(name: String, allowOffline: Boolean) =
        OfflineMediaEntry(
            itemId = UUID.randomUUID(),
            name = name,
            kind = OfflineMediaEntryKind.BOOK,
            allowOffline = allowOffline,
        )
}

private fun com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyChild.childName():
    String =
    when (this) {
        is com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyLeaf -> entry.name
        is DownloadHierarchySubContainer -> title
    }
