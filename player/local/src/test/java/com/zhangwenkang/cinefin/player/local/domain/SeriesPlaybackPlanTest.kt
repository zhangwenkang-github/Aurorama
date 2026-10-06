package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidSeason
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W76-B11：系列详情页「播放」的起播决策单测。
 *
 * 覆盖修复前的两条静默分支：a) NextUp 缺失 / 不可用时要回退到「第一季第一集」；b) 整剧没有可播的集时**不能**静默返回 null（用户看到的是空载播放页、无提示），调用方据此抛
 * [PlaybackStartException]。
 */
class SeriesPlaybackPlanTest {
    private val seriesId = UUID.randomUUID()
    private val seasonOneId = UUID.randomUUID()
    private val seasonTwoId = UUID.randomUUID()

    private fun episode(seasonId: UUID, indexNumber: Int, id: UUID = UUID.randomUUID()) =
        FindroidEpisode(
            id = id,
            name = "第 $indexNumber 集",
            originalTitle = null,
            overview = "",
            indexNumber = indexNumber,
            indexNumberEnd = null,
            parentIndexNumber = if (seasonId == seasonTwoId) 2 else 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "灼眼的夏娜",
            seasonId = seasonId,
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun season(id: UUID, indexNumber: Int, episodes: List<FindroidEpisode>) =
        FindroidSeason(
            id = id,
            name = "第 $indexNumber 季",
            seriesId = seriesId,
            seriesName = "灼眼的夏娜",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = indexNumber,
            episodes = episodes,
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = episodes.size,
            images = FindroidImages(),
        )

    /** 整剧队列：S1 两集 + S2 一集，按「季号 → 集号」排序（= `loadSeriesEpisodes` 的口径）。 */
    private val queue =
        listOf(
            episode(seasonOneId, 1),
            episode(seasonOneId, 2),
            episode(seasonTwoId, 1),
        )

    @Test
    fun `nextUp 命中队列时从续播那一集起播`() {
        val nextUp = queue[1]

        val plan = planSeriesPlayback(nextUp, queue)

        assertNotNull(plan)
        assertEquals(queue, plan!!.episodes)
        assertEquals(1, plan.startIndex)
        assertEquals(nextUp.id, plan.episodes[plan.startIndex].id)
    }

    @Test
    fun `nextUp 不在队列里时回退第一季第一集`() {
        // 服务器给的 NextUp 属于别的季 / 已被过滤掉：不能把 -1 当起播下标（起播即失败）
        val strayNextUp = episode(UUID.randomUUID(), 7)

        val plan = planSeriesPlayback(strayNextUp, queue)

        assertEquals(0, plan!!.startIndex)
        assertEquals(queue.first().id, plan.episodes[plan.startIndex].id)
    }

    @Test
    fun `没有 nextUp 时从第一季第一集起播`() {
        val plan = planSeriesPlayback(null, queue)

        assertEquals(0, plan!!.startIndex)
        assertEquals(queue.first().id, plan.episodes[plan.startIndex].id)
    }

    @Test
    fun `整剧取空时返回 null 由调用方给可见提示`() {
        assertNull(planSeriesPlayback(null, emptyList()))
        assertNull(planSeriesPlayback(episode(seasonOneId, 1), emptyList()))
    }

    @Test
    fun `兜底季取季号最小的那一季`() {
        val s3 = season(UUID.randomUUID(), 3, emptyList())
        val s1 = season(seasonOneId, 1, emptyList())
        val s2 = season(seasonTwoId, 2, emptyList())

        assertEquals(s1.id, pickFallbackSeason(listOf(s3, s1, s2))?.id)
        assertNull(pickFallbackSeason(emptyList()))
    }

    @Test
    fun `兜底季季号相同时取先出现的那一季`() {
        val first = season(seasonOneId, 1, emptyList())
        val second = season(UUID.randomUUID(), 1, emptyList())

        assertEquals(first.id, pickFallbackSeason(listOf(first, second))?.id)
    }

    @Test
    fun `起播失败的异常带可提示的中文消息`() {
        val error = PlaybackStartException("这部剧暂时没有可播放的剧集，请稍后重试")

        // 播放页把 localizedMessage 直接 Toast 出来：为空就等于「无提示」，正是本缺陷的现象
        assertTrue(error.localizedMessage?.isNotBlank() == true)
    }
}
