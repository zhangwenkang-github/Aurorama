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

    // ---------- W4-T5：方向锁 10vp（VIDEO-26） ----------

    @Test
    fun directionLock_staysUnlockedBelowThreshold() {
        // K60 density 2.625：10vp = 26.25px
        val threshold = 10f * 2.625f
        assertEquals(
            GestureAxis.NONE,
            resolveGestureAxis(GestureAxis.NONE, 20f, 3f, threshold),
        )
        assertEquals(
            "斜向但未达 10vp 也不锁定（不反馈）",
            GestureAxis.NONE,
            resolveGestureAxis(GestureAxis.NONE, 15f, 15f, threshold),
        )
        assertEquals(
            "达锁边界即锁定",
            GestureAxis.HORIZONTAL,
            resolveGestureAxis(GestureAxis.NONE, threshold, 0f, threshold),
        )
    }

    @Test
    fun directionLock_picksDominantAxisOnce_andNeverSwitches() {
        val threshold = 26.25f
        val locked = resolveGestureAxis(GestureAxis.NONE, 40f, 12f, threshold)
        assertEquals(GestureAxis.HORIZONTAL, locked)
        assertEquals(
            "锁定后反向位移不得换向",
            GestureAxis.HORIZONTAL,
            resolveGestureAxis(locked, 5f, 300f, threshold),
        )
        val vertical = resolveGestureAxis(GestureAxis.NONE, 12f, 40f, threshold)
        assertEquals(GestureAxis.VERTICAL, vertical)
        assertEquals(
            GestureAxis.VERTICAL,
            resolveGestureAxis(vertical, 400f, 5f, threshold),
        )
    }

    @Test
    fun directionLock_diagonal30to60Degrees_keepsMainAxis() {
        val threshold = 26.25f
        // 30°：|dy| = 0.577|dx| → 横向
        assertEquals(
            GestureAxis.HORIZONTAL,
            resolveGestureAxis(GestureAxis.NONE, 60f, 34f, threshold),
        )
        // 45°：等分取横向（确定性，不随机）
        assertEquals(
            GestureAxis.HORIZONTAL,
            resolveGestureAxis(GestureAxis.NONE, 60f, 60f, threshold),
        )
        // 60°：|dx| = 0.577|dy| → 纵向
        assertEquals(
            GestureAxis.VERTICAL,
            resolveGestureAxis(GestureAxis.NONE, 34f, 60f, threshold),
        )
    }

    // ---------- W4-T5：双击连击递增（VIDEO-20） ----------

    @Test
    fun tapCombo_stepsUpToTripleWithinWindow() {
        val base = 10_000L
        val first = nextTapCombo(TapComboState(), base, direction = -1, nowMs = 1_000L)
        assertEquals("第 1 次 = 基准步长 ×1", -10_000L, first.deltaMs)
        assertEquals(-10_000L, first.accumulatedMs)

        val second = nextTapCombo(first.state, base, direction = -1, nowMs = 1_500L)
        assertEquals("窗口内第 2 次 ×2", -20_000L, second.deltaMs)
        assertEquals("HUD 累计 −30s", -30_000L, second.accumulatedMs)

        val third = nextTapCombo(second.state, base, direction = -1, nowMs = 2_000L)
        assertEquals("第 3 次 ×3", -30_000L, third.deltaMs)
        assertEquals("HUD 累计 −60s", -60_000L, third.accumulatedMs)

        val fourth = nextTapCombo(third.state, base, direction = -1, nowMs = 2_500L)
        assertEquals("第 4 次起维持 ×3 封顶", -30_000L, fourth.deltaMs)
        assertEquals(-90_000L, fourth.accumulatedMs)
    }

    @Test
    fun tapCombo_windowTimeout_resetsToFirstStep() {
        val first = nextTapCombo(TapComboState(), 15_000L, direction = 1, nowMs = 1_000L)
        val late = nextTapCombo(first.state, 15_000L, direction = 1, nowMs = 1_800L)
        assertEquals("800ms 边界仍在窗口内", 30_000L, late.deltaMs)

        val expired = nextTapCombo(late.state, 15_000L, direction = 1, nowMs = 2_601L)
        assertEquals("超窗清零后从 ×1 重新开始", 15_000L, expired.deltaMs)
        assertEquals(15_000L, expired.accumulatedMs)
        assertEquals(1, expired.state.count)
    }

    @Test
    fun tapCombo_directionChange_startsNewChain() {
        val first = nextTapCombo(TapComboState(), 15_000L, direction = 1, nowMs = 1_000L)
        val switched = nextTapCombo(first.state, 5_000L, direction = -1, nowMs = 1_200L)
        assertEquals("换方向另起一串，用该方向的基准步长", -5_000L, switched.deltaMs)
        assertEquals(-5_000L, switched.accumulatedMs)
        assertEquals(1, switched.state.count)
    }

    @Test
    fun tapCombo_usesConfiguredBaseStep() {
        // 基准档 5s / 15s 各自缩放，不写死 10/20/30
        val fwd2 =
            nextTapCombo(
                nextTapCombo(TapComboState(), 15_000L, 1, 1_000L).state,
                15_000L,
                1,
                1_100L,
            )
        assertEquals(30_000L, fwd2.deltaMs)
        val back3 =
            nextTapCombo(
                nextTapCombo(
                        nextTapCombo(TapComboState(), 5_000L, -1, 1_000L).state,
                        5_000L,
                        -1,
                        1_100L,
                    )
                    .state,
                5_000L,
                -1,
                1_200L,
            )
        assertEquals(-15_000L, back3.deltaMs)
        assertEquals(-30_000L, back3.accumulatedMs)
    }

    // ---------- W4-T5：双指连续缩放 + 平移（VIDEO-24） ----------

    @Test
    fun zoomScale_clampedTo1to3_andNaNFallsBack() {
        assertEquals(1f, clampZoomScale(0.4f))
        assertEquals(1f, clampZoomScale(1f))
        assertEquals(1.75f, clampZoomScale(1.75f))
        assertEquals(3f, clampZoomScale(9f))
        assertEquals(1f, clampZoomScale(Float.NaN))
    }

    @Test
    fun zoomPan_onlyAboveThreshold() {
        assertFalse(zoomPanEnabled(1f))
        assertFalse(zoomPanEnabled(ZOOM_PAN_THRESHOLD))
        assertTrue(zoomPanEnabled(1.06f))
        assertTrue(zoomPanEnabled(2.5f))
    }

    @Test
    fun zoomTranslation_clampedInsideContent() {
        assertEquals("1.0x 不允许平移", 0f, clampZoomTranslation(120f, 1f, 2560))
        assertEquals("2.0x 最大平移半个画面宽", 1280f, clampZoomTranslation(9000f, 2f, 2560))
        assertEquals(-1280f, clampZoomTranslation(-9000f, 2f, 2560))
        assertEquals(300f, clampZoomTranslation(300f, 2f, 2560))
        assertEquals("竖向同理", 800f, clampZoomTranslation(9000f, 2f, 1600))
        assertEquals(0f, clampZoomTranslation(500f, 3f, 0))
    }

    @Test
    fun zoomAnchor_keepsFocalPointFixed() {
        // 以画面中心为焦点：平移量按比例放大（中心处 t=0 恒为 0）
        assertEquals(
            0f,
            zoomTranslationAfterScale(translationPx = 0f, focalOffsetPx = 0f, 1f, 2f),
        )
        // 焦点在中心右侧 200px，1.0 → 2.0x：焦点处像素保持不动 ⇒ 平移变为 −200
        assertEquals(
            -200f,
            zoomTranslationAfterScale(translationPx = 0f, focalOffsetPx = 200f, 1f, 2f),
        )
        // 已经有平移时同式：焦点 200、t=−100、1→1.5 ⇒ 200 − 1.5×300 = −250
        assertEquals(
            -250f,
            zoomTranslationAfterScale(translationPx = -100f, focalOffsetPx = 200f, 1f, 1.5f),
        )
        assertEquals(
            "非法旧缩放保持原值",
            50f,
            zoomTranslationAfterScale(50f, 200f, previousScale = 0f, newScale = 2f),
        )
    }

    // ---------- W4-T5：「越久越快」长按倍速（VIDEO-23） ----------

    @Test
    fun speedRamp_stepsEveryTwoSeconds_upTo4x() {
        assertEquals(2f, rampedPlaybackSpeed(2f, 0L))
        assertEquals("不足 2s 停在基础档", 2f, rampedPlaybackSpeed(2f, 1_999L))
        assertEquals(3f, rampedPlaybackSpeed(2f, 2_000L))
        assertEquals(4f, rampedPlaybackSpeed(2f, 4_000L))
        assertEquals("封顶 4x", 4f, rampedPlaybackSpeed(2f, 60_000L))
        assertEquals("1.5 基础档 +1x", 2.5f, rampedPlaybackSpeed(1.5f, 2_000L))
        assertEquals("3.0 基础档直接封顶", 4f, rampedPlaybackSpeed(3f, 2_000L))
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
