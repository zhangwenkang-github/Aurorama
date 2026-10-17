package com.zhangwenkang.cinefin.utils

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W67c：手势 seek 排队 / 落点纯函数测试——覆盖起播前（未就绪排队）、缓冲中（就绪立即落点）、时长未知、 增量饱和与「就绪后补上排队增量」。 */
class PlayerSeekGestureRulesTest {

    // ---------- W81：双击 / 长按分区 ----------

    @Test
    fun tapZone_usesCurrentViewWidth_andHasClosedEdges() {
        assertEquals(TapGestureZone.EDGE_OUTSIDE, tapGestureZone(-0.1f, 2560))
        assertEquals(TapGestureZone.REWIND, tapGestureZone(0f, 2560))
        assertEquals(TapGestureZone.REWIND, tapGestureZone(511.9f, 2560))
        assertEquals(TapGestureZone.REWIND, tapGestureZone(512f, 2560))
        assertEquals(TapGestureZone.TOGGLE, tapGestureZone(1024f, 2560))
        assertEquals(TapGestureZone.TOGGLE, tapGestureZone(1535.9f, 2560))
        assertEquals(TapGestureZone.FORWARD, tapGestureZone(1536f, 2560))
        assertEquals(TapGestureZone.FORWARD, tapGestureZone(2048f, 2560))
        assertEquals(TapGestureZone.FORWARD, tapGestureZone(2559.9f, 2560))
        assertEquals(TapGestureZone.EDGE_OUTSIDE, tapGestureZone(2560f, 2560))
    }

    @Test
    fun tapZone_portraitWidth_keepsSame2to1to2Semantics() {
        assertEquals(TapGestureZone.REWIND, tapGestureZone(0f, 1600))
        assertEquals(TapGestureZone.REWIND, tapGestureZone(320f, 1600))
        assertEquals(TapGestureZone.TOGGLE, tapGestureZone(640f, 1600))
        assertEquals(TapGestureZone.FORWARD, tapGestureZone(960f, 1600))
        assertEquals(TapGestureZone.FORWARD, tapGestureZone(1280f, 1600))
        assertEquals(TapGestureZone.EDGE_OUTSIDE, tapGestureZone(1600f, 1600))
        assertEquals(TapGestureZone.EDGE_OUTSIDE, tapGestureZone(0f, 0))
    }

    // ---------- W81：排除区 / 方向 / 竖滑左右 ----------

    @Test
    fun exclusion_usesCurrentViewSize_notHistoricalDisplayMetrics() {
        assertFalse(
            "横屏 view=2560×1600，历史系统度量 1600×2524 不得把 x=2000 判为边缘",
            gestureStartExcluded(
                x = 2000f,
                y = 800f,
                viewWidth = 2560,
                viewHeight = 1600,
                insetLeft = 0,
                insetTop = 0,
                insetRight = 0,
                insetBottom = 0,
            ),
        )
        assertFalse(gestureStartExcluded(2559.9f, 1599.9f, 2560, 1600, 0, 0, 0, 0))
        assertTrue(gestureStartExcluded(2560.1f, 800f, 2560, 1600, 0, 0, 0, 0))
        assertTrue(gestureStartExcluded(2000f, 1600.1f, 2560, 1600, 0, 0, 0, 0))
    }

    @Test
    fun exclusion_insetBoundaryIsAllowed_onlyOutsideIsRejected() {
        fun excluded(x: Float, y: Float) =
            gestureStartExcluded(
                x = x,
                y = y,
                viewWidth = 2560,
                viewHeight = 1600,
                insetLeft = 24,
                insetTop = 48,
                insetRight = 24,
                insetBottom = 48,
            )

        assertFalse(excluded(24f, 800f))
        assertTrue(excluded(23.9f, 800f))
        assertFalse(excluded(2536f, 800f))
        assertTrue(excluded(2536.1f, 800f))
        assertFalse(excluded(1200f, 48f))
        assertTrue(excluded(1200f, 47.9f))
        assertFalse(excluded(1200f, 1552f))
        assertTrue(excluded(1200f, 1552.1f))
    }

    @Test
    fun exclusion_invalidViewSize_doesNotBlock() {
        assertFalse(gestureStartExcluded(0f, 0f, 0, 0, 24, 48, 24, 48))
        assertFalse(gestureStartExcluded(100f, 100f, -1, 1600, 24, 48, 24, 48))
    }

    @Test
    fun scrollDirection_usesExisting2to1Threshold_andRejectsZero() {
        assertTrue(isHorizontalScroll(100f, 0f))
        assertTrue(isHorizontalScroll(100f, 199.9f))
        assertTrue(isHorizontalScroll(-100f, 50f))
        assertTrue(isHorizontalScroll(1f, 0f))
        assertFalse(isHorizontalScroll(0f, 100f))
        assertFalse(isHorizontalScroll(100f, 200f))
        assertFalse(isHorizontalScroll(0f, 0f))

        assertTrue(isVerticalScroll(0f, 100f))
        assertTrue(isVerticalScroll(100f, 200f))
        assertTrue(isVerticalScroll(-100f, -200f))
        assertFalse(isVerticalScroll(100f, 199.9f))
        assertFalse(isVerticalScroll(100f, 0f))
        assertFalse(isVerticalScroll(0f, 0f))
    }

    @Test
    fun verticalSide_midlineBelongsToBrightness() {
        assertEquals(VerticalGestureSide.BRIGHTNESS, verticalGestureSide(0f, 2560))
        assertEquals(VerticalGestureSide.BRIGHTNESS, verticalGestureSide(1279f, 2560))
        assertEquals(VerticalGestureSide.BRIGHTNESS, verticalGestureSide(1280f, 2560))
        assertEquals(VerticalGestureSide.VOLUME, verticalGestureSide(1280.1f, 2560))
        assertEquals(VerticalGestureSide.VOLUME, verticalGestureSide(2560f, 2560))
        assertEquals(VerticalGestureSide.BRIGHTNESS, verticalGestureSide(799f, 1600))
        assertEquals(VerticalGestureSide.VOLUME, verticalGestureSide(801f, 1600))
    }

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
