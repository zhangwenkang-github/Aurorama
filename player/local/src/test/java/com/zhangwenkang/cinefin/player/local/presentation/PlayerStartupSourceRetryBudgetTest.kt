package com.zhangwenkang.cinefin.player.local.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupSourceRetryBudgetTest {
    @Test
    fun `second startup error on the same source is exhausted regardless of elapsed time`() {
        // 转码冷启动时两次上报可能相隔 65s：预算只看次数，不存在可以用来清零的时钟窗口。
        val budget = PlayerStartupSourceRetryBudget(maxAttempts = 1)

        assertTrue(budget.shouldRetry("transcode-session"))
        assertEquals(1, budget.attempts)

        assertFalse(budget.shouldRetry("transcode-session"))
        assertEquals(1, budget.attempts)
        assertFalse(budget.shouldRetry("transcode-session"))
    }

    @Test
    fun `startup completion reopens the retry budget`() {
        val budget = PlayerStartupSourceRetryBudget(maxAttempts = 1)
        assertTrue(budget.shouldRetry("transcode-session"))
        assertFalse(budget.shouldRetry("transcode-session"))

        budget.reset()

        assertEquals(0, budget.attempts)
        assertTrue(budget.shouldRetry("transcode-session"))
    }

    @Test
    fun `new media id reopens the retry budget`() {
        val budget = PlayerStartupSourceRetryBudget(maxAttempts = 1)
        assertTrue(budget.shouldRetry("transcode-a"))
        assertFalse(budget.shouldRetry("transcode-a"))

        assertTrue(budget.shouldRetry("transcode-b"))
        assertEquals(1, budget.attempts)
        assertFalse(budget.shouldRetry("transcode-b"))
    }

    @Test
    fun `budget consumes exactly the configured attempts`() {
        val budget = PlayerStartupSourceRetryBudget(maxAttempts = 2)

        assertTrue(budget.shouldRetry("transcode-session"))
        assertTrue(budget.shouldRetry("transcode-session"))
        assertEquals(2, budget.attempts)
        assertFalse(budget.shouldRetry("transcode-session"))
    }
}
