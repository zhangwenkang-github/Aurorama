package com.zhangwenkang.cinefin.player.local.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import com.zhangwenkang.cinefin.player.local.subtitle.shouldSuppressSecondaryForMpvPrimarySelection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSubtitleRoutingTest {

    private val item =
        PlayerItem(
            name = "测试片",
            itemId = UUID.fromString("0b834979-9e2e-8c13-9014-783cfd83e7ed"),
            mediaSourceId = "source",
            playbackPosition = 0L,
            subtitleSources =
                listOf(
                    PlayerSubtitleSource(
                        index = 4,
                        title = "简日双语",
                        language = "zh-Hans",
                        uri = "https://server/Subtitles/4/Stream.ass",
                        codec = "ass",
                    )
                ),
        )

    @Test
    fun `mpv 内核让位给 libass，不启用自研覆盖层`() {
        val sources = playerSubtitleSourcesForBackend(PlayerViewModel.PLAYER_BACKEND_MPV, item)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `ExoPlayer 内核仍走自研字幕清单`() {
        val sources =
            playerSubtitleSourcesForBackend(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER, item)

        assertEquals(listOf(4), sources.map { it.index })
    }

    @Test
    fun `mpv 主字幕映射不到源时立即抑制次字幕`() {
        assertTrue(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = 7,
                mappedSourceIndex = null,
            )
        )
        assertFalse(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = 7,
                mappedSourceIndex = 4,
            )
        )
        assertFalse(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = null,
                mappedSourceIndex = null,
            )
        )
    }

    @Test
    fun `缺少 DeliveryUrl 的已选文本轨走 Native 而不是 Auto`() {
        val source =
            PlayerSubtitleSource(
                index = 7,
                title = "原生文本",
                language = "zh-Hans",
                uri = "",
                codec = "subrip",
            )

        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                )
            )

        assertTrue(routing is SubtitleRouting.Native)
        assertEquals(source.index, (routing as SubtitleRouting.Native).source.index)
    }

    @Test
    fun `已选文本轨加载失败时确定性回退 Native`() {
        val source =
            PlayerSubtitleSource(
                index = 8,
                title = "失败文本",
                language = "en",
                uri = "https://server/Subtitles/8/Stream.srt",
                codec = "subrip",
            )

        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                    primaryRenderFailed = true,
                )
            )

        assertTrue(routing is SubtitleRouting.Native)
        assertEquals(source.index, (routing as SubtitleRouting.Native).source.index)
    }

    @Test
    fun `直放与转码的可渲染文本轨共享 Managed 不变量`() {
        val direct =
            PlayerSubtitleSource(
                index = 4,
                title = "简体",
                language = "zh-Hans",
                uri = "https://server/Videos/item/Subtitles/4/Stream.srt",
                codec = "subrip",
            )
        val transcode =
            direct.copy(uri = "https://server/Videos/item/Subtitles/4/Stream.srt?transcode=true")

        val directRouting =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(direct),
                    primaryIndex = direct.index,
                    primaryManaged = true,
                )
            )
        val transcodeRouting =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(transcode),
                    primaryIndex = transcode.index,
                    primaryManaged = true,
                )
            )

        assertEquals(SubtitleRouting.Managed, directRouting)
        assertEquals(directRouting, transcodeRouting)
    }
}
