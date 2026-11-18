package com.zhangwenkang.cinefin.player.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPolicyTest {

    private var nowMs = 1_000L

    private fun policy(): ErrorPolicy =
        ErrorPolicy(
            nowMs = { nowMs },
            sourceResolutionMessage = { "源解析失败" },
            backendName = { "exoplayer" },
            maxStartupSourceRetries = 1,
        )

    @Test
    fun `新起播清空已发布错误并重置起播预算`() {
        val policy = policy()
        assertTrue(policy.shouldRetryStartupSource("media-a"))
        policy.onStartupResolutionFailed()
        assertNotNull(policy.current)

        policy.onNewPlaybackAttempt()

        assertNull(policy.current)
        assertEquals(0, policy.startupSourceAttempts)
        assertTrue(policy.shouldRetryStartupSource("media-a"))
    }

    @Test
    fun `起播源耗尽在会话已出帧时不发布错误`() {
        val policy = policy()

        val published = policy.onStartupSourceExhausted(sessionHadProgress = true)

        assertNull(published)
        assertNull(policy.current)
    }

    @Test
    fun `起播源耗尽在已有错误时不重复发布`() {
        val policy = policy()
        policy.onStartupSourceFailed()
        val existing = policy.current

        val published = policy.onStartupSourceExhausted(sessionHadProgress = false)

        assertNull(published)
        assertSame(existing, policy.current)
    }

    @Test
    fun `起播源耗尽在会话未出帧且无错误时发布起播源错误`() {
        val policy = policy()

        val published = policy.onStartupSourceExhausted(sessionHadProgress = false)

        assertNotNull(published)
        assertEquals(PLAYER_ERROR_STARTUP_SOURCE_FAILED, published?.codeName)
        assertSame(published, policy.current)
    }

    @Test
    fun `去重窗口内同一 key 不重复处理，过窗口或换 key 后重新处理`() {
        val policy = policy()
        val key = "exoplayer:0"
        assertEquals(1_000L, nowMs)

        assertTrue(policy.shouldHandleFailure(key))
        policy.markFailureHandled(key)
        assertEquals(1_000L, policy.lastHandledAtMs)

        nowMs += 2_999L
        assertFalse(policy.shouldHandleFailure(key))
        assertTrue(policy.shouldHandleFailure("mpv:0"))

        nowMs += 1L
        assertTrue(policy.shouldHandleFailure(key))
    }

    @Test
    fun `起播预算按 mediaId 切换重置`() {
        val policy = policy()
        assertTrue(policy.shouldRetryStartupSource("transcode-a"))
        assertEquals(1, policy.startupSourceAttempts)
        assertFalse(policy.shouldRetryStartupSource("transcode-a"))

        assertTrue(policy.shouldRetryStartupSource("transcode-b"))
        assertEquals(1, policy.startupSourceAttempts)
        assertFalse(policy.shouldRetryStartupSource("transcode-b"))
    }

    @Test
    fun `两档源错误 codeName 与内核名正确`() {
        val policy = policy()

        val resolution = policy.onStartupResolutionFailed()
        assertEquals(PLAYER_ERROR_SOURCE_RESOLUTION_FAILED, resolution.codeName)
        assertEquals("exoplayer", resolution.backend)
        assertEquals("源解析失败", resolution.message)

        val startup = policy.onStartupSourceFailed()
        assertEquals(PLAYER_ERROR_STARTUP_SOURCE_FAILED, startup.codeName)
        assertSame(startup, policy.current)

        val engine =
            policy.onPlaybackError(
                message = "解码失败",
                codeName = "ERROR_CODE_DECODING_FAILED",
            )
        assertEquals("ERROR_CODE_DECODING_FAILED", engine.codeName)
        assertEquals("解码失败", engine.message)
        assertEquals("exoplayer", engine.backend)
    }

    @Test
    fun `出画面收起错误并重开预算，手动清除不动预算`() {
        val policy = policy()
        assertTrue(policy.shouldRetryStartupSource("media-a"))
        policy.onStartupSourceFailed()

        policy.onPlaybackFileLoaded()

        assertNull(policy.current)
        assertEquals(0, policy.startupSourceAttempts)

        assertTrue(policy.shouldRetryStartupSource("media-a"))
        policy.onStartupSourceFailed()
        policy.clear()

        assertNull(policy.current)
        assertEquals(1, policy.startupSourceAttempts)
    }
}
