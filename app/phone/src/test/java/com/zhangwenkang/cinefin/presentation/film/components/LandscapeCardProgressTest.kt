package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.models.FindroidChapter
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W60 首页走廊卡片进度换算单测（继续观看用播放位置；继续阅读 / 收听回退 playedPercentage）。 */
class LandscapeCardProgressTest {

    @Test
    fun `有完整时长时用播放位置换算进度`() {
        val item = FakeResumeItem(runtimeTicks = 1_000L, playbackPositionTicks = 250L)
        assertEquals(0.25f, item.cardResumeFraction(), 0.0001f)
    }

    @Test
    fun `书籍没有时长时回退 playedPercentage`() {
        val item = FakeResumeItem(playedPercentage = 42.0)
        assertEquals(0.42f, item.cardResumeFraction(), 0.0001f)
    }

    @Test
    fun `位置为 0 但百分比有值时回退百分比`() {
        val item =
            FakeResumeItem(
                runtimeTicks = 1_000L,
                playbackPositionTicks = 0L,
                playedPercentage = 55.0,
            )
        assertEquals(0.55f, item.cardResumeFraction(), 0.0001f)
    }

    @Test
    fun `没有进度数据返回 0 且越界钳制`() {
        assertEquals(0f, FakeResumeItem().cardResumeFraction(), 0.0001f)
        assertEquals(1f, FakeResumeItem(playedPercentage = 130.0).cardResumeFraction(), 0.0001f)
        assertEquals(0f, FakeResumeItem(playedPercentage = -5.0).cardResumeFraction(), 0.0001f)
        assertEquals(
            1f,
            FakeResumeItem(runtimeTicks = 100L, playbackPositionTicks = 250L).cardResumeFraction(),
            0.0001f,
        )
    }
}

private class FakeResumeItem(
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val playedPercentage: Double? = null,
) : FindroidItem {
    override val id: UUID = UUID.randomUUID()
    override val name: String = "测试条目"
    override val originalTitle: String? = null
    override val overview: String = ""
    override val played: Boolean = false
    override val favorite: Boolean = false
    override val canPlay: Boolean = true
    override val canDownload: Boolean = false
    override val sources: List<FindroidSource> = emptyList()
    override val unplayedItemCount: Int? = null
    override val images: FindroidImages = FindroidImages()
    override val chapters: List<FindroidChapter> = emptyList()
}
