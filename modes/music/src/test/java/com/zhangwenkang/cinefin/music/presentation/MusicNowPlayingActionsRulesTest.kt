package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** W3-T18 · §4.5：全屏播放页底部三键与 ⋮ 菜单的单入口划分（纯函数）。 */
class MusicNowPlayingActionsRulesTest {

    @Test
    fun `bottom row keeps effects lyrics queue in design order`() {
        assertEquals(
            listOf(
                NowPlayingBottomAction.EFFECTS,
                NowPlayingBottomAction.LYRICS,
                NowPlayingBottomAction.QUEUE,
            ),
            nowPlayingBottomActions(),
        )
    }

    @Test
    fun `menu only carries actions that are not in the bottom row`() {
        val actions = nowPlayingMenuActions(hasSongMeta = true)

        assertEquals(
            listOf(
                NowPlayingMenuAction.PLAY_MODE,
                NowPlayingMenuAction.FAVORITE,
                NowPlayingMenuAction.LYRIC_OVERLAY,
                NowPlayingMenuAction.SLEEP_TIMER,
            ),
            actions,
        )
    }

    @Test
    fun `menu hides favorite without song metadata`() {
        val actions = nowPlayingMenuActions(hasSongMeta = false)

        assertEquals(
            listOf(
                NowPlayingMenuAction.PLAY_MODE,
                NowPlayingMenuAction.LYRIC_OVERLAY,
                NowPlayingMenuAction.SLEEP_TIMER,
            ),
            actions,
        )
    }

    @Test
    fun `menu never repeats queue or effects entries`() {
        val actions = nowPlayingMenuActions(hasSongMeta = true)

        assertEquals(false, actions.any { it.name == "QUEUE" || it.name == "EFFECTS" })
    }
}
