package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面状态徽标口径（W73 起）：容器类（Series / Season / 文件夹）还有未看显示数字、全部看完打勾； 单片类（Movie / Episode）已看打勾。
 *
 * 纯函数 [posterStatusBadge] 是「类型 → 数字 / 打勾 / 无」的唯一真相，四个卡片组件共用；[unplayedItemCountText] 负责 `99+` 收敛文案。
 */
class ItemStatusBadgeTest {
    @Test
    fun show_withUnplayedEpisodes_reportsCount() {
        assertEquals(
            PosterStatusBadge.UnplayedCount(13),
            show(unplayedItemCount = 13).posterStatusBadge(),
        )
    }

    @Test
    fun show_fullyWatched_reportsPlayed() {
        // W73（#4）：全部看完要打勾（用户 2026-10-06「季里每集看完，季没有勾」）。
        assertEquals(
            PosterStatusBadge.Played,
            show(played = true, unplayedItemCount = 0).posterStatusBadge(),
        )
        // 计数缺省（离线 / 服务器没给）且未标已看 → 不显示。
        assertEquals(PosterStatusBadge.None, show(played = false).posterStatusBadge())
    }

    @Test
    fun season_withUnplayedEpisodes_reportsCount_andFullyWatchedReportsPlayed() {
        assertEquals(
            PosterStatusBadge.UnplayedCount(1),
            season(unplayedItemCount = 1).posterStatusBadge(),
        )
        assertEquals(PosterStatusBadge.Played, season(unplayedItemCount = 0).posterStatusBadge())
        // 服务器直接标了已看、但没给未看计数（未知季这类）。
        assertEquals(PosterStatusBadge.Played, season(played = true).posterStatusBadge())
    }

    @Test
    fun container_unplayedCountWins_overPlayedFlag() {
        // 未看计数比「已看」标记更新：还有未看条目时先显示数字（服务器的 UnplayedItemCount 口径）。
        assertEquals(
            PosterStatusBadge.UnplayedCount(3),
            season(played = true, unplayedItemCount = 3).posterStatusBadge(),
        )
    }

    @Test
    fun folder_withUnplayedChildren_reportsCount_andFullyWatchedReportsPlayed() {
        assertEquals(
            PosterStatusBadge.UnplayedCount(7),
            folder(unplayedItemCount = 7).posterStatusBadge(),
        )
        assertEquals(PosterStatusBadge.Played, folder(unplayedItemCount = 0).posterStatusBadge())
        // 计数缺省且未标已看 → 不显示。
        assertEquals(PosterStatusBadge.None, folder(unplayedItemCount = null).posterStatusBadge())
    }

    @Test
    fun movie_onlyShowsCheckWhenPlayed() {
        assertEquals(PosterStatusBadge.Played, movie(played = true).posterStatusBadge())
        assertEquals(PosterStatusBadge.None, movie(played = false).posterStatusBadge())
    }

    @Test
    fun episode_onlyShowsCheckWhenPlayed() {
        assertEquals(PosterStatusBadge.Played, episode(played = true).posterStatusBadge())
        assertEquals(PosterStatusBadge.None, episode(played = false).posterStatusBadge())
    }

    @Test
    fun unplayedItemCountText_collapsesOver99() {
        assertEquals("1", unplayedItemCountText(1))
        assertEquals("99", unplayedItemCountText(99))
        assertEquals("99+", unplayedItemCountText(100))
        assertEquals("99+", unplayedItemCountText(4096))
    }

    private fun show(played: Boolean = false, unplayedItemCount: Int? = null) =
        FindroidShow(
            id = UUID.randomUUID(),
            name = "测试剧集",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = unplayedItemCount,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
        )

    private fun season(played: Boolean = false, unplayedItemCount: Int? = null) =
        FindroidSeason(
            id = UUID.randomUUID(),
            name = "第 1 季",
            seriesId = UUID.randomUUID(),
            seriesName = "测试剧集",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = 1,
            episodes = emptyList(),
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = unplayedItemCount,
            images = FindroidImages(),
        )

    private fun folder(played: Boolean = false, unplayedItemCount: Int? = null) =
        FindroidFolder(
            id = UUID.randomUUID(),
            name = "测试文件夹",
            played = played,
            favorite = false,
            unplayedItemCount = unplayedItemCount,
            images = FindroidImages(),
        )

    private fun movie(played: Boolean = false) =
        FindroidMovie(
            id = UUID.randomUUID(),
            name = "测试电影",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun episode(played: Boolean = false) =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "第 1 集",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "测试剧集",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
