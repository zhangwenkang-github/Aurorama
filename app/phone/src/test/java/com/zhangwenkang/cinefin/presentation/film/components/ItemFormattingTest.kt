package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 剧集号格式化的回归测试。
 *
 * 背景（2026-10-01 首页验收缺陷）：`"S$this.parentIndexNumber"` 被 Kotlin 解析为 「`$this` 对象插值 + 字面量
 * `.parentIndexNumber`」，首页头图把整段 `FindroidEpisode` 打印了出来。 这里直接断言输出，防止字符串模板再次写错。
 */
class ItemFormattingTest {
    @Test
    fun episodeCode_formatsSeasonAndEpisodeNumbers() {
        val episode = episode(parentIndexNumber = 1, indexNumber = 1)

        assertEquals("S1 E1", episode.episodeCode())
        assertEquals("S1", episode.seasonCode())
        assertEquals("E1", episode.indexCode())
    }

    @Test
    fun episodeCode_fallsBackToQuestionMarkWhenNumbersAreMissing() {
        // DTO 缺号会被映射成 0，格式化层不应把它当合法号位打印
        val episode = episode(parentIndexNumber = 0, indexNumber = 0)

        assertEquals("S? E?", episode.episodeCode())
    }

    private fun episode(parentIndexNumber: Int, indexNumber: Int) =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "第 1 集",
            originalTitle = null,
            overview = "",
            indexNumber = indexNumber,
            indexNumberEnd = null,
            parentIndexNumber = parentIndexNumber,
            sources = emptyList(),
            played = false,
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
