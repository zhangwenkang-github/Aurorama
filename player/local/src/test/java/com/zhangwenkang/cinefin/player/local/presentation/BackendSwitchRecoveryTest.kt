package com.zhangwenkang.cinefin.player.local.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0.3-A Bug 11：内核切换失败后的恢复策略。
 *
 * 锁死的场景（用户真机反馈）：切内核失败 → 播放页无条件 recreate → 重启再失败 → 该条目 / 该设备
 * 再也播不了。这里钉住两条底线：旧内核可用时绝不整页重启；同一组合反复失败必须熔断。
 */
class BackendSwitchRecoveryTest {

    @Test
    fun `kept previous backend never restarts the page`() {
        assertFalse(
            shouldRestartAfterSwitchFailure(
                keptPreviousBackend = true,
                hasRestartTarget = true,
                circuitClosed = true,
            )
        )
        // 即使熔断器放行、目标也齐全，旧实例可用时仍然不重启（重启只会丢队列与位置）
        assertFalse(
            shouldRestartAfterSwitchFailure(
                keptPreviousBackend = true,
                hasRestartTarget = true,
                circuitClosed = false,
            )
        )
    }

    @Test
    fun `restart needs a target and a closed circuit`() {
        assertTrue(
            shouldRestartAfterSwitchFailure(
                keptPreviousBackend = false,
                hasRestartTarget = true,
                circuitClosed = true,
            )
        )
        // 没有条目：重建后的播放页会直接退出，重启等于把页面关掉
        assertFalse(
            shouldRestartAfterSwitchFailure(
                keptPreviousBackend = false,
                hasRestartTarget = false,
                circuitClosed = true,
            )
        )
        // 熔断：反复重启只会把播放页钉死在失败循环里
        assertFalse(
            shouldRestartAfterSwitchFailure(
                keptPreviousBackend = false,
                hasRestartTarget = true,
                circuitClosed = false,
            )
        )
    }

    @Test
    fun `persisted backend self heals to exoplayer only when the target has a fallback`() {
        assertEquals(PlayerHolder.BACKEND_EXOPLAYER, backendFallbackAfterCreateFailure("mpv"))
        assertNull(backendFallbackAfterCreateFailure(PlayerHolder.BACKEND_EXOPLAYER))
    }

    @Test
    fun `circuit breaker opens after repeated failures for the same item and target`() {
        val breaker = BackendSwitchCircuitBreaker()
        val item = "item-1"
        val target = "mpv"

        assertTrue(breaker.allows(item, target, nowMs = 0L))
        breaker.recordFailure(item, target, nowMs = 0L)
        assertTrue(breaker.allows(item, target, nowMs = 1_000L))
        breaker.recordFailure(item, target, nowMs = 1_000L)
        // 第二次失败 = 熔断：重启兜底不再重复
        assertFalse(breaker.allows(item, target, nowMs = 2_000L))
        // 换条目 / 换目标内核是新组合，照常放行
        assertTrue(breaker.allows("item-2", target, nowMs = 2_000L))
        assertTrue(breaker.allows(item, "exoplayer", nowMs = 2_000L))
    }

    @Test
    fun `circuit breaker window and reset restore the retry budget`() {
        val breaker = BackendSwitchCircuitBreaker()
        breaker.recordFailure("item-1", "mpv", nowMs = 0L)
        breaker.recordFailure("item-1", "mpv", nowMs = 0L)
        assertFalse(breaker.allows("item-1", "mpv", nowMs = 0L))

        // 窗口外的旧失败不再压制新请求（时间越界后重新获得一次机会）
        assertTrue(
            breaker.allows(
                "item-1",
                "mpv",
                nowMs = BackendSwitchCircuitBreaker.WINDOW_MS + 1L,
            )
        )

        breaker.recordFailure("item-1", "mpv", nowMs = 0L)
        breaker.recordFailure("item-1", "mpv", nowMs = 0L)
        breaker.reset()
        assertTrue(breaker.allows("item-1", "mpv", nowMs = 0L))
    }
}
