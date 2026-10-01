package com.zhangwenkang.cinefin.player.local.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import java.util.UUID
import org.junit.Assert.assertEquals
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
}
