package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackFailure
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W5-T8（MUSIC-48）：播放失败 snackbar 文案（非阻塞提示，不提供重试按钮）。 */
class MusicPlaybackFailureCopyTest {

    private val itemId = UUID.fromString("44444444-4444-4444-8444-444444444444")

    @Test
    fun `已跳下一首的失败文案带曲名`() {
        val message =
            musicPlaybackFailureMessage(
                MusicPlaybackFailure(
                    itemId = itemId,
                    name = "晴天",
                    artist = "周杰伦",
                    skippedToNext = true,
                )
            )

        assertEquals("「晴天」播放失败，已跳到下一首", message)
    }

    @Test
    fun `队尾停播的失败文案`() {
        val message =
            musicPlaybackFailureMessage(
                MusicPlaybackFailure(
                    itemId = itemId,
                    name = "晴天",
                    artist = null,
                    skippedToNext = false,
                )
            )

        assertEquals("「晴天」播放失败，已停止播放", message)
    }

    @Test
    fun `没有曲名时使用通用文案`() {
        val message =
            musicPlaybackFailureMessage(
                MusicPlaybackFailure(itemId = null, name = "", artist = null, skippedToNext = true)
            )

        assertEquals("「当前曲目」播放失败，已跳到下一首", message)
    }
}
