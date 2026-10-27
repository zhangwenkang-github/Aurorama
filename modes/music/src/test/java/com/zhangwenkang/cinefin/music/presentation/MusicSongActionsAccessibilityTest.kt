package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.R as CoreR
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSongActionsAccessibilityTest {

    @Test
    fun `歌曲更多菜单入口使用可理解标签`() {
        assertEquals(
            CoreR.string.music_song_actions,
            MusicSongActionsAccessibility.contentDescriptionRes,
        )
    }
}
