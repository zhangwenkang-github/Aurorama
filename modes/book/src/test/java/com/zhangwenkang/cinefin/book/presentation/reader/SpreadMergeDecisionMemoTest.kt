package com.zhangwenkang.cinefin.book.presentation.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W49：判定记忆只缓存明确结论——「未就绪」不写入（可重试），「明确不合并」写入 null 结论（不再复算）。
 *
 * 背景：W48 真机在 LTR 冷启动 / 切 RTL 重建缓存瞬间观察到某槽位判定取到 null 后被缓存，该轮不再复算。
 */
class SpreadMergeDecisionMemoTest {

    @Test
    fun `先未就绪后有效 下一次请求重算并命中`() = runBlocking {
        val memo = SpreadMergeDecisionMemo()
        var calls = 0
        val first =
            memo.resolve(7) {
                calls++
                SpreadMergeDecisionResult.NotReady
            }
        assertEquals(SpreadMergeDecisionResult.NotReady, first)
        assertNull("未就绪不能留下结论", memo.cached(7))

        val decision = decision()
        val second =
            memo.resolve(7) {
                calls++
                SpreadMergeDecisionResult.Ready(decision)
            }
        assertEquals(2, calls)
        assertEquals(SpreadMergeDecisionResult.Ready(decision), second)

        // 已有结论：不再复算（哪怕 compute 仍会返回未就绪）。
        val third =
            memo.resolve(7) {
                calls++
                SpreadMergeDecisionResult.NotReady
            }
        assertEquals("已有明确结论后不再复算", 2, calls)
        assertEquals(SpreadMergeDecisionResult.Ready(decision), third)
    }

    @Test
    fun `明确不合并缓存 null 结论 后续不再重算`() = runBlocking {
        val memo = SpreadMergeDecisionMemo()
        var calls = 0
        val first =
            memo.resolve(3) {
                calls++
                SpreadMergeDecisionResult.Ready(null)
            }
        assertEquals(SpreadMergeDecisionResult.Ready(null), first)
        assertEquals(SpreadMergeDecisionResult.Ready(null), memo.cached(3))

        val second =
            memo.resolve(3) {
                calls++
                SpreadMergeDecisionResult.Ready(decision())
            }
        assertEquals("明确不合并后不再复算", 1, calls)
        assertEquals(SpreadMergeDecisionResult.Ready(null), second)
    }

    @Test
    fun `未就绪只影响当前页对 其他页对照常判定`() = runBlocking {
        val memo = SpreadMergeDecisionMemo()
        memo.resolve(5) { SpreadMergeDecisionResult.NotReady }
        val decision = decision()
        assertEquals(
            SpreadMergeDecisionResult.Ready(decision),
            memo.resolve(9) { SpreadMergeDecisionResult.Ready(decision) },
        )
        assertNull(memo.cached(5))
        assertTrue(memo.cached(9) is SpreadMergeDecisionResult.Ready)
    }

    private fun decision(): SpreadMergeDecision =
        SpreadMergeDecision(
            geometry = SpreadMergeGeometry(targetHeightPx = 1447, combinedAspect = 1.4144f),
            trimFirstPx = 0,
            trimSecondPx = 0,
            thumbWidthFirstPx = 128,
            thumbWidthSecondPx = 128,
            evidence =
                SpreadSeamEvidence(
                    continuity = 0.92f,
                    variation = 42f,
                    correlation = 0.88f,
                    difference = 0.04f,
                ),
        )
}
