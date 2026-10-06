package com.zhangwenkang.cinefin.player.local.subtitle

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W74 #21：字幕下载「短退避重试一次」的纯逻辑回归。
 *
 * @see retryOnce
 */
class SubtitleLoadRetryTest {

    @Test
    fun `成功路径只调用一次`() = runBlocking {
        var attempts = 0
        val result =
            retryOnce(delayMs = 0L, label = "测试下载") {
                attempts++
                "内容"
            }
        assertEquals(1, attempts)
        assertEquals("内容", result)
    }

    @Test
    fun `首次失败后重试一次并返回结果`() = runBlocking {
        var attempts = 0
        val result =
            retryOnce(delayMs = 0L, label = "测试下载") {
                attempts++
                if (attempts == 1) throw IOException("瞬断") else "内容"
            }
        assertEquals("应恰好重试一次", 2, attempts)
        assertEquals("内容", result)
    }

    @Test
    fun `两次都失败时抛出第二次的异常且不无限重试`() = runBlocking {
        var attempts = 0
        val error = runCatching {
            retryOnce<String>(delayMs = 0L, label = "测试下载") {
                attempts++
                throw IOException("第 $attempts 次失败")
            }
        }
            .exceptionOrNull()
        assertEquals("最多尝试两次", 2, attempts)
        assertTrue("原样抛出异常交给调用方兜底", error is IOException)
        assertEquals("第 2 次失败", error?.message)
    }

    @Test
    fun `生产默认退避间隔是 500 毫秒内的一次短退避`() {
        assertEquals(500L, SUBTITLE_DOWNLOAD_RETRY_DELAY_MS)
    }
}
