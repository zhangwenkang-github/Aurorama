package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地媒体库判据（进度回写 / 错误卡文案共用同一份）。 */
class LocalPlaybackSourceTest {

    @Test
    fun `local 前缀的合成源 id 判为本地`() {
        assertTrue(isLocalPlaybackSource("local-1b4e28ba-2fa1-11d2-883f", null))
        assertTrue(isLocalPlaybackSource("LOCAL-1b4e28ba-2fa1-11d2-883f", null))
    }

    @Test
    fun `SAF content URI 判为本地`() {
        assertTrue(
            isLocalPlaybackSource(
                null,
                "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fdemo.mkv",
            )
        )
    }

    @Test
    fun `在线源不判为本地`() {
        assertFalse(isLocalPlaybackSource(null, null))
        assertFalse(isLocalPlaybackSource("", ""))
        assertFalse(
            isLocalPlaybackSource(
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "https://jellyfins.example.com/Videos/demo/stream.mkv?api_key=x",
            )
        )
        assertFalse(isLocalPlaybackSource(null, "file:///storage/emulated/0/Download/demo.mkv"))
    }
}
