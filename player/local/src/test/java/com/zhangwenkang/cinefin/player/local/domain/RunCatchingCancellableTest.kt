package com.zhangwenkang.cinefin.player.local.domain

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W76-B11b：起播解析链的降级包装必须**让取消穿过去**。
 *
 * 真机复现：在起播窗口内退出播放页 → 协程被取消 → `runCatching` 把取消吞成「空列表」→ 走到「这部剧暂时没有可播放的剧集」兜底 → 用户只是退出播放页，却收到一句报错提示。
 */
class RunCatchingCancellableTest {
    @Test
    fun `成功时原样返回结果`() {
        val result = runCatchingCancellable { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `普通失败仍然收成 Result 交给调用方降级`() {
        val result = runCatchingCancellable<Int> { throw IllegalStateException("网络抖动") }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `取消必须抛出而不是被降级吞掉`() {
        try {
            runCatchingCancellable<Int> { throw CancellationException("Job was cancelled") }
            fail("取消异常被吞掉了：起播窗口内退出播放页会继续走降级分支并弹错误提示")
        } catch (expected: CancellationException) {
            assertEquals("Job was cancelled", expected.message)
        }
    }
}
