package com.zhangwenkang.cinefin.presentation.video

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W53 视频模式页：库门控 / 聚合游标 / 显示方式取值的纯逻辑。 */
class VideoAggregateTest {
    @Test
    fun picksVideoLibrariesIncludingHomeVideosAndMixedInServerOrder() {
        val movies = library("电影", CollectionType.Movies)
        val home = library("其他", CollectionType.HomeVideos)
        val mixed = library("混剪", CollectionType.Mixed)
        val shows = library("动漫", CollectionType.TvShows)
        val music = library("音乐", CollectionType.Music)
        val playlists = library("Playlists", CollectionType.Playlists)

        val picked = pickVideoLibraries(listOf(movies, home, mixed, shows, music, playlists))

        assertEquals(listOf(movies, home, mixed, shows), picked)
    }

    @Test
    fun aggregateItemTypesCoverHomeVideoEntries() {
        // homevideos 库（「其他」）的条目是 `Video` 类型：不带上它该库点开是空网格（W73 #16）。
        assertEquals(
            listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO),
            VIDEO_AGGREGATE_TYPES,
        )
    }

    @Test
    fun cursorStaysInLibraryWhilePageIsFull() {
        val next =
            advanceVideoAggregateCursor(
                cursor = VideoAggregateCursor(libraryIndex = 0, startIndex = 0),
                fetched = 30,
                requested = 30,
            )

        assertEquals(VideoAggregateCursor(libraryIndex = 0, startIndex = 30), next)
    }

    @Test
    fun cursorMovesToNextLibraryWhenPageIsShort() {
        // 短页 = 这个库已经取完（服务器不会再给更多）→ 下一个库从头开始。
        val next =
            advanceVideoAggregateCursor(
                cursor = VideoAggregateCursor(libraryIndex = 0, startIndex = 60),
                fetched = 12,
                requested = 30,
            )

        assertEquals(VideoAggregateCursor(libraryIndex = 1, startIndex = 0), next)
    }

    @Test
    fun cursorSkipsEmptyLibrary() {
        val next =
            advanceVideoAggregateCursor(
                cursor = VideoAggregateCursor(libraryIndex = 1, startIndex = 0),
                fetched = 0,
                requested = 30,
            )

        assertEquals(VideoAggregateCursor(libraryIndex = 2, startIndex = 0), next)
    }

    @Test
    fun aggregateFinishesAfterLastLibrary() {
        assertTrue(
            isVideoAggregateFinished(VideoAggregateCursor(libraryIndex = 2, startIndex = 0), 2)
        )
        assertFalse(
            isVideoAggregateFinished(VideoAggregateCursor(libraryIndex = 1, startIndex = 150), 2)
        )
    }

    @Test
    fun displayModeFallsBackToCards() {
        assertEquals(VideoDisplayMode.Cards, VideoDisplayMode.fromString(null))
        assertEquals(VideoDisplayMode.Cards, VideoDisplayMode.fromString("bogus"))
        assertEquals(VideoDisplayMode.Aggregated, VideoDisplayMode.fromString("aggregated"))
        assertEquals("cards", VideoDisplayMode.defaultValue.value)
    }

    private fun library(name: String, type: CollectionType) =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = name,
            type = type,
            images = FindroidImages(),
        )
}
