package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W68：退出播放页时的共享实例释放策略（条件写反 = P1「退出后播放按钮失效」回归）。 */
class PlayerReleasePolicyTest {

    @Test
    fun musicSessionAlwaysKeepsSharedPlayer() {
        assertFalse(
            "音乐在后台播放：即使视频后台播放开关关闭也不能释放共享实例",
            shouldReleasePlayerOnExit(musicSessionActive = true, backgroundAudioEnabled = false),
        )
        assertFalse(
            shouldReleasePlayerOnExit(musicSessionActive = true, backgroundAudioEnabled = true)
        )
    }

    @Test
    fun backgroundAudioKeepsSharedPlayer() {
        assertFalse(
            shouldReleasePlayerOnExit(musicSessionActive = false, backgroundAudioEnabled = true)
        )
    }

    @Test
    fun foregroundOnlyPlaybackReleasesSharedPlayer() {
        assertTrue(
            "普通前台播放结束（无后台播放、无音乐会话）才释放实例",
            shouldReleasePlayerOnExit(musicSessionActive = false, backgroundAudioEnabled = false),
        )
    }

    @Test
    fun explicitVideoExitOverridesBackgroundAudioForPlayerAndService() {
        assertEquals(
            PlayerExitAction.STOP_AND_RELEASE,
            playerExitAction(
                musicSessionActive = false,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            ),
        )
        assertTrue(
            shouldReleasePlayerOnExit(
                musicSessionActive = false,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            )
        )
        assertTrue(
            shouldStopPlaybackServiceOnExit(
                musicSessionActive = false,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            )
        )
    }

    @Test
    fun ordinaryBackgroundingPreservesBackgroundVideo() {
        assertEquals(
            PlayerExitAction.PRESERVE_BACKGROUND_VIDEO,
            playerExitAction(
                musicSessionActive = false,
                explicitVideoExit = false,
                backgroundAudioEnabled = true,
            ),
        )
        assertFalse(
            shouldReleasePlayerOnExit(
                musicSessionActive = false,
                explicitVideoExit = false,
                backgroundAudioEnabled = true,
            )
        )
        assertFalse(
            shouldStopPlaybackServiceOnExit(
                musicSessionActive = false,
                explicitVideoExit = false,
                backgroundAudioEnabled = true,
            )
        )
    }

    @Test
    fun attachedMusicWinsEvenWhenVideoExitIsExplicit() {
        assertEquals(
            PlayerExitAction.PRESERVE_MUSIC,
            playerExitAction(
                musicSessionActive = true,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            ),
        )
        assertFalse(
            shouldReleasePlayerOnExit(
                musicSessionActive = true,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            )
        )
        assertFalse(
            shouldStopPlaybackServiceOnExit(
                musicSessionActive = true,
                explicitVideoExit = true,
                backgroundAudioEnabled = true,
            )
        )
    }

    @Test
    fun explicitExitControllerAdvancesOnceAndRejectsStaleWork() {
        val controller = PlayerExplicitExitController()
        val beforeExit = controller.generation

        val exitGeneration = controller.begin()

        assertNotNull(exitGeneration)
        assertFalse(controller.isCurrent(beforeExit))
        assertTrue(controller.isCurrent(requireNotNull(exitGeneration)))
        assertNull(controller.begin())
        assertEquals(exitGeneration, controller.generation)
    }
}
