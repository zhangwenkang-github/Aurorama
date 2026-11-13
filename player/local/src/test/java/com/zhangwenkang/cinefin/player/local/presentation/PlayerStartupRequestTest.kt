package com.zhangwenkang.cinefin.player.local.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerStartupRequestTest {
    @Test
    fun playbackStartupRequestRunsOffCallerThread() = runBlocking {
        val callerThreadId = Thread.currentThread().threadId()

        val workerThreadId = runPlaybackStartupRequest { Thread.currentThread().threadId() }

        assertNotEquals(callerThreadId, workerThreadId)
        assertEquals(callerThreadId, Thread.currentThread().threadId())
    }

    @Test
    fun failureMessageUsesFallbackWhenThrowableHasNoMessage() {
        assertEquals("播放失败，请重试", playbackStartFailureMessage(IllegalStateException(), "播放失败，请重试"))
    }

    @Test
    fun failureMessagePreservesActionableMessage() {
        assertEquals(
            "这部剧暂时没有可播放的剧集，请稍后重试",
            playbackStartFailureMessage(
                IllegalStateException("这部剧暂时没有可播放的剧集，请稍后重试"),
                "播放失败，请重试",
            ),
        )
    }
}
