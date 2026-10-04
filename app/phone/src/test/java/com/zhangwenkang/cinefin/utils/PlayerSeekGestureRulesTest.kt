package com.zhangwenkang.cinefin.utils

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W67c：手势 seek 排队 / 落点纯函数测试——覆盖起播前（未就绪排队）、缓冲中（就绪立即落点）、时长未知、 增量饱和与「就绪后补上排队增量」。 */
class PlayerSeekGestureRulesTest {

    // ---------- 就绪判定 ----------

    @Test
    fun ready_requiresAttachedPreparedPlayer() {
        assertTrue(
            "已挂载 + 时长已知 + 缓冲中但位置已就位 = 可立即落点",
            isGestureSeekReady(
                playerAttached = true,
                durationMs = 1_421_000L,
                playbackState = Player.STATE_BUFFERING,
                currentPositionMs = 60_000L,
            ),
        )
        assertTrue(
            "READY 且位置为 0（从头播）也可立即落点",
            isGestureSeekReady(true, 1_421_000L, Player.STATE_READY, 0L),
        )
        assertFalse(
            "未挂载（内核切换 / 重建窗口）不能落点",
            isGestureSeekReady(false, 1_421_000L, Player.STATE_READY, 0L),
        )
        assertFalse(
            "媒体未 prepared（时长 TIME_UNSET）不能落点",
            isGestureSeekReady(true, -9_223_372_036_854_775_807L, Player.STATE_BUFFERING, 0L),
        )
        assertFalse(
            "STATE_IDLE 不能落点",
            isGestureSeekReady(true, 1_421_000L, Player.STATE_IDLE, 0L),
        )
        assertFalse(
            "prepare 刚完成、续播位尚未生效（时长已知但位置仍 0 且未 READY）：先排队",
            isGestureSeekReady(true, 1_421_000L, Player.STATE_BUFFERING, 0L),
        )
    }

    // ---------- 落点 ----------

    @Test
    fun target_relativeStepWithKnownDuration_isClamped() {
        assertEquals(75_000L, gestureSeekTarget(60_000L, 15_000L, 120_000L))
        assertEquals("末尾超出收敛到时长", 120_000L, gestureSeekTarget(110_000L, 15_000L, 120_000L))
        assertEquals("回退不过 0", 0L, gestureSeekTarget(5_000L, -15_000L, 120_000L))
    }

    @Test
    fun target_unknownDuration_hasNoUpperClamp() {
        assertEquals(
            "时长未知（加载中）：只保留下界，上界交给播放器",
            20_000L,
            gestureSeekTarget(5_000L, 15_000L, durationMs = 0L),
        )
        assertEquals(3_600_000L, gestureSeekTarget(0L, 3_600_000L, 0L))
        assertEquals(0L, gestureSeekTarget(5_000L, -15_000L, 0L))
    }

    @Test
    fun target_extremeValues_saturateWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, gestureSeekTarget(Long.MAX_VALUE - 10L, 100L, 0L))
        assertEquals(0L, gestureSeekTarget(10L, Long.MIN_VALUE, 0L))
    }

    // ---------- 排队增量 ----------

    @Test
    fun pending_accumulatesAndSaturates() {
        assertEquals(15_000L, accumulatePendingSeek(0L, 15_000L))
        assertEquals(10_000L, accumulatePendingSeek(15_000L, -5_000L))
        assertEquals(
            "正向饱和",
            GESTURE_PENDING_SEEK_MAX_ABS_MS,
            accumulatePendingSeek(GESTURE_PENDING_SEEK_MAX_ABS_MS, GESTURE_PENDING_SEEK_MAX_ABS_MS),
        )
        assertEquals(
            "负向饱和",
            -GESTURE_PENDING_SEEK_MAX_ABS_MS,
            accumulatePendingSeek(
                -GESTURE_PENDING_SEEK_MAX_ABS_MS,
                -GESTURE_PENDING_SEEK_MAX_ABS_MS,
            ),
        )
    }

    // ---------- 完整判定 ----------

    @Test
    fun decide_ready_appliesImmediately_andFlushesPending() {
        val decision =
            decideGestureSeek(
                basePositionMs = 60_000L,
                durationMs = 120_000L,
                deltaMs = 15_000L,
                pendingMs = 0L,
                playerReady = true,
            )
        assertTrue(decision.applyNow)
        assertEquals(75_000L, decision.targetMs)
        assertEquals(0L, decision.pendingMs)

        val merged =
            decideGestureSeek(
                basePositionMs = 60_000L,
                durationMs = 120_000L,
                deltaMs = 15_000L,
                pendingMs = 5_000L,
                playerReady = true,
            )
        assertTrue("就绪时把之前的排队增量一并补上", merged.applyNow)
        assertEquals(80_000L, merged.targetMs)
        assertEquals(0L, merged.pendingMs)
    }

    @Test
    fun decide_notReady_queuesInstead() {
        val first =
            decideGestureSeek(
                basePositionMs = 0L,
                durationMs = 0L,
                deltaMs = 15_000L,
                pendingMs = 0L,
                playerReady = false,
            )
        assertFalse("未就绪不落点", first.applyNow)
        assertEquals("排队 +15s", 15_000L, first.pendingMs)

        val second =
            decideGestureSeek(
                basePositionMs = 0L,
                durationMs = 0L,
                deltaMs = -5_000L,
                pendingMs = first.pendingMs,
                playerReady = false,
            )
        assertFalse(second.applyNow)
        assertEquals("累积 +10s", 10_000L, second.pendingMs)

        // 就绪后（恢复位置 206s = 真实基准）一次性应用排队增量
        val ready =
            decideGestureSeek(
                basePositionMs = 206_000L,
                durationMs = 1_421_000L,
                deltaMs = 0L,
                pendingMs = second.pendingMs,
                playerReady = true,
            )
        assertTrue(ready.applyNow)
        assertEquals("排队 seek 落到 恢复位置 + 10s", 216_000L, ready.targetMs)
        assertEquals(0L, ready.pendingMs)
    }
}
