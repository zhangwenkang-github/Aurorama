package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.models.FindroidChapter
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W83 #36 家庭视频缩略图续播进度：来源 / 已看完口径与首页续播一致。 */
class HomeVideoProgressTest {

    @Test
    fun `未看完按播放位置换算进度`() {
        val item = FakeHomeVideo(runtimeTicks = 1_000L, playbackPositionTicks = 250L)
        assertEquals(0.25f, item.homeVideoResumeFraction(), 0.0001f)
    }

    @Test
    fun `缺少时长时回退 playedPercentage`() {
        val item = FakeHomeVideo(playedPercentage = 42.0)
        assertEquals(0.42f, item.homeVideoResumeFraction(), 0.0001f)
    }

    @Test
    fun `已看完不显示续播进度`() {
        val item =
            FakeHomeVideo(
                runtimeTicks = 1_000L,
                playbackPositionTicks = 850L,
                played = true,
            )
        assertEquals(0f, item.homeVideoResumeFraction(), 0.0001f)
    }

    @Test
    fun `未看与越界值按零到一钳制`() {
        assertEquals(0f, FakeHomeVideo().homeVideoResumeFraction(), 0.0001f)
        assertEquals(
            1f,
            FakeHomeVideo(runtimeTicks = 100L, playbackPositionTicks = 250L)
                .homeVideoResumeFraction(),
            0.0001f,
        )
        assertEquals(0f, FakeHomeVideo(playedPercentage = -5.0).homeVideoResumeFraction(), 0.0001f)
    }
}

private class FakeHomeVideo(
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val playedPercentage: Double? = null,
    override val played: Boolean = false,
) : FindroidItem {
    override val id: UUID = UUID.randomUUID()
    override val name: String = "家庭视频"
    override val originalTitle: String? = null
    override val overview: String = ""
    override val favorite: Boolean = false
    override val canPlay: Boolean = true
    override val canDownload: Boolean = false
    override val sources: List<FindroidSource> = emptyList()
    override val unplayedItemCount: Int? = null
    override val images: FindroidImages = FindroidImages()
    override val chapters: List<FindroidChapter> = emptyList()
}
