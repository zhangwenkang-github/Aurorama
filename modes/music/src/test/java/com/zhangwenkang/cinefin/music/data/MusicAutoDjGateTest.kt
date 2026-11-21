package com.zhangwenkang.cinefin.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W5-T12（MUSIC-14）：Auto DJ 六格闸门（设计定稿五 §3）——每一格正反两面全覆盖。
 *
 * 基准输入 = 全部放行（开关开、服务器种子、无睡眠挂起、非错误队尾、未达刹车、InstantMix 有货）。
 */
class MusicAutoDjGateTest {

    private fun input(
        enabled: Boolean = true,
        seedIsServerItem: Boolean = true,
        sleepStopPending: Boolean = false,
        endedByError: Boolean = false,
        autoAppendBatches: Int = 0,
        instantMixItemCount: Int? = AUTO_DJ_INSTANT_MIX_LIMIT,
    ) =
        AutoDjGateInput(
            enabled = enabled,
            seedIsServerItem = seedIsServerItem,
            sleepStopPending = sleepStopPending,
            endedByError = endedByError,
            autoAppendBatches = autoAppendBatches,
            instantMixItemCount = instantMixItemCount,
        )

    @Test
    fun `第 1 格 开关关则跳过 开则放行`() {
        val disabled = autoDjGate(input(enabled = false))
        assertEquals(AutoDjDecision.SKIP, disabled.decision)
        assertEquals(AutoDjGateReason.DISABLED, disabled.reason)

        assertEquals(AutoDjDecision.APPEND, autoDjGate(input(enabled = true)).decision)
    }

    @Test
    fun `第 2 格 本地种子跳过 服务器种子放行`() {
        val local = autoDjGate(input(seedIsServerItem = false))
        assertEquals(AutoDjDecision.SKIP, local.decision)
        assertEquals(AutoDjGateReason.SEED_NOT_SERVER, local.reason)

        assertEquals(AutoDjDecision.APPEND, autoDjGate(input(seedIsServerItem = true)).decision)
    }

    @Test
    fun `第 3 格 睡眠播完当前曲目停止挂起则跳过 未挂起放行`() {
        val sleep = autoDjGate(input(sleepStopPending = true))
        assertEquals(AutoDjDecision.SKIP, sleep.decision)
        assertEquals(AutoDjGateReason.SLEEP_STOP_PENDING, sleep.reason)

        assertEquals(AutoDjDecision.APPEND, autoDjGate(input(sleepStopPending = false)).decision)
    }

    @Test
    fun `第 4 格 错误跳曲导致的队尾跳过 自然播完放行`() {
        val error = autoDjGate(input(endedByError = true))
        assertEquals(AutoDjDecision.SKIP, error.decision)
        assertEquals(AutoDjGateReason.ENDED_BY_ERROR, error.reason)

        assertEquals(AutoDjDecision.APPEND, autoDjGate(input(endedByError = false)).decision)
    }

    @Test
    fun `第 5 格 连续续播满 3 批刹车 未满放行`() {
        val brake = autoDjGate(input(autoAppendBatches = AUTO_DJ_MAX_BATCHES))
        assertEquals(AutoDjDecision.SKIP, brake.decision)
        assertEquals(AutoDjGateReason.BATCH_LIMIT, brake.reason)

        assertEquals(
            AutoDjDecision.APPEND,
            autoDjGate(input(autoAppendBatches = AUTO_DJ_MAX_BATCHES - 1)).decision,
        )
    }

    @Test
    fun `第 6 格 InstantMix 失败或空则跳过 有货放行`() {
        val failed = autoDjGate(input(instantMixItemCount = null))
        // null = 尚未拉取：只评估前五格，通过后放行到拉取阶段
        assertEquals(AutoDjDecision.APPEND, failed.decision)

        val empty = autoDjGate(input(instantMixItemCount = 0))
        assertEquals(AutoDjDecision.SKIP, empty.decision)
        assertEquals(AutoDjGateReason.INSTANT_MIX_UNAVAILABLE, empty.reason)

        assertEquals(AutoDjDecision.APPEND, autoDjGate(input(instantMixItemCount = 1)).decision)
    }

    @Test
    fun `判定顺序固定 开关关优先于其余各格`() {
        val result =
            autoDjGate(
                input(
                    enabled = false,
                    seedIsServerItem = false,
                    sleepStopPending = true,
                    endedByError = true,
                    autoAppendBatches = AUTO_DJ_MAX_BATCHES,
                    instantMixItemCount = 0,
                )
            )
        assertEquals(AutoDjGateReason.DISABLED, result.reason)

        val second =
            autoDjGate(
                input(
                    seedIsServerItem = false,
                    sleepStopPending = true,
                    endedByError = true,
                    autoAppendBatches = AUTO_DJ_MAX_BATCHES,
                    instantMixItemCount = 0,
                )
            )
        assertEquals(AutoDjGateReason.SEED_NOT_SERVER, second.reason)

        val third =
            autoDjGate(
                input(
                    sleepStopPending = true,
                    endedByError = true,
                    autoAppendBatches = AUTO_DJ_MAX_BATCHES,
                    instantMixItemCount = 0,
                )
            )
        assertEquals(AutoDjGateReason.SLEEP_STOP_PENDING, third.reason)

        val fourth =
            autoDjGate(
                input(
                    endedByError = true,
                    autoAppendBatches = AUTO_DJ_MAX_BATCHES,
                    instantMixItemCount = 0,
                )
            )
        assertEquals(AutoDjGateReason.ENDED_BY_ERROR, fourth.reason)

        val fifth =
            autoDjGate(input(autoAppendBatches = AUTO_DJ_MAX_BATCHES, instantMixItemCount = 0))
        assertEquals(AutoDjGateReason.BATCH_LIMIT, fifth.reason)
    }
}
