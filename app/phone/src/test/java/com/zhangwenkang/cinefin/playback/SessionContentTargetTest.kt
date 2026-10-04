package com.zhangwenkang.cinefin.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** W68：通知 / 锁屏点击路由按媒体类型分派（音频 → 音乐播放界面，视频 → 视频播放页）。 */
class SessionContentTargetTest {

    @Test
    fun musicItemRoutesToMusicScreen() {
        assertEquals(SessionContentTarget.Music, sessionContentTarget(isMusicItem = true))
    }

    @Test
    fun videoItemRoutesToPlayerScreen() {
        assertEquals(SessionContentTarget.Video, sessionContentTarget(isMusicItem = false))
    }
}
