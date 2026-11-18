package com.zhangwenkang.cinefin.player.local.session

import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryPhase
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionInput
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionRules
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看门狗决策顺序的等价性核对表：每个用例钉住 `refreshPlaybackSession` 的一个原有步骤。
 *
 * 输入一律是脚本化快照，控制器用真实实例，确保判定走生产规则。
 */
class ProgressWatchdogTest {
    @Test
    fun `terminal 产生且未抑制时按序给出状态、retain 与恢复计划`() {
        val watchdog = ProgressWatchdog()
        val input = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        val now = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS

        val plan = watchdog.evaluate(watchdogInput(started, input, now))

        // ① advance 是 terminal 的唯一起源，计划里的状态与规则逐值一致
        val expected = PlaybackSessionRules.advance(started, input, now)
        assertEquals(expected, plan.newSessionState)
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", plan.newSessionState.terminal?.kind)
        // ② retain 拿到的是新状态里的 terminal
        assertEquals(plan.newSessionState.terminal, plan.retainTerminal)
        // ③ 非兜底源：不发事件，也不抑制
        assertNull(plan.fallbackEventToken)
        assertFalse(plan.suppressTerminal)
        // ④ 首次观察回到 IDLE，不取消
        assertFalse(plan.cancelRecovery)
        // ⑥ 有 terminal、未抑制、ownership 空闲 → 启动恢复
        val (request, terminal) = requireNotNull(plan.startRecovery)
        assertEquals(1, request.attempt)
        assertEquals(sessionKey, request.sessionKey)
        assertEquals(terminal, plan.newSessionState.terminal)
        // ⑦ 投影等于规则直算结果，且不含 progressRecovery 行（由 VM 用控制器现状补）
        assertEquals(PlaybackSessionRules.project(expected, input), plan.sessionUiBase)
        assertNull(plan.sessionUiBase.progressRecovery)
        assertTrue(plan.sessionUiBase.loading)
        assertEquals(
            PlayerProgressRecoveryPhase.RETRYING,
            watchdog.progressRecoveryStatus()?.phase,
        )
    }

    @Test
    fun `无 terminal 时不启动恢复也不发兜底事件`() {
        val watchdog = ProgressWatchdog()

        val plan =
            watchdog.evaluate(
                watchdogInput(PlaybackSessionState(), playbackInput(), nowElapsedMs = 1_000L)
            )

        assertNull(plan.newSessionState.terminal)
        assertNull(plan.startRecovery)
        assertNull(plan.fallbackEventToken)
        assertFalse(plan.suppressTerminal)
        assertNull(watchdog.progressRecoveryStatus())
    }

    @Test
    fun `兜底接手同一代次时不启动恢复且投影按抑制计算`() {
        val watchdog = ProgressWatchdog()
        val input = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        val now = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS

        val plan =
            watchdog.evaluate(watchdogInput(started, input, now, fallback = remoteHlsMpvSnapshot()))

        // ③ 兜底评估在恢复之前：事件 token 已产出
        assertTrue(requireNotNull(plan.fallbackEventToken).startsWith("progress-fallback:"))
        // ⑤ 抑制判定在观察之后、启动之前
        assertTrue(plan.suppressTerminal)
        // ⑥ 被抑制 → 不启动恢复
        assertNull(plan.startRecovery)
        assertEquals(
            PlaybackSessionRules.project(
                plan.newSessionState,
                input,
                suppressTerminal = true,
            ),
            plan.sessionUiBase,
        )
    }

    @Test
    fun `恢复 job 仍活跃时计划不重复启动恢复`() {
        val input = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        val now = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS
        val terminal = requireNotNull(PlaybackSessionRules.advance(started, input, now).terminal)
        val activeOwnership =
            RecoveryOwnershipSnapshot(active = true, terminal = terminal, sessionKey = sessionKey)

        val busyWatchdog = ProgressWatchdog()
        val busyPlan =
            busyWatchdog.evaluate(
                watchdogInput(started, input, now, recoveryOwnership = activeOwnership)
            )
        val idleWatchdog = ProgressWatchdog()
        val idlePlan =
            idleWatchdog.evaluate(
                watchdogInput(
                    started,
                    input,
                    now,
                    recoveryOwnership = RecoveryOwnershipSnapshot(false, null, null),
                )
            )

        // ownership 活跃时步骤⑥ 短路；控制器也确实没被登记（否则 STATUS 会变 RETRYING）
        assertNull(busyPlan.startRecovery)
        assertNull(busyWatchdog.progressRecoveryStatus())
        // 同样的输入、ownership 空闲 → 启动
        assertTrue(requireNotNull(idlePlan.startRecovery).first.sessionKey == sessionKey)
        assertEquals(
            PlayerProgressRecoveryPhase.RETRYING,
            idleWatchdog.progressRecoveryStatus()?.phase,
        )
    }

    @Test
    fun `终端换代时先释放旧恢复状态再按新代次重新开始`() {
        val watchdog = ProgressWatchdog()
        val stalled = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), stalled, 1_000L)
        val stalledNow = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS
        val oldPlan = watchdog.evaluate(watchdogInput(started, stalled, stalledNow))
        val (oldRequest, oldTerminal) = requireNotNull(oldPlan.startRecovery)
        val oldOwnership =
            RecoveryOwnershipSnapshot(
                active = true,
                terminal = oldTerminal,
                sessionKey = sessionKey,
            )

        // 暂停：规则清掉终端，retain 判定必须释放旧恢复
        val pausedInput = stalled.copy(playWhenReady = false)
        val movedPlan =
            watchdog.evaluate(
                watchdogInput(
                    oldPlan.newSessionState,
                    pausedInput,
                    stalledNow + 1_000L,
                    recoveryOwnership = oldOwnership,
                )
            )

        assertNull(movedPlan.retainTerminal)
        assertFalse(watchdog.isRecoveryCurrent(oldRequest))
        assertNull(watchdog.progressRecoveryStatus())
        // 释放已由 retain 判定完成，观察只剩 IDLE（与原实现一致：先 cancel 再 observe）
        assertFalse(movedPlan.cancelRecovery)
        assertNull(movedPlan.startRecovery)
    }

    @Test
    fun `观察确认恢复时取消恢复并重新开始新一轮`() {
        val watchdog = ProgressWatchdog()
        val stalled = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), stalled, 1_000L)
        val stalledNow = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS
        val firstPlan = watchdog.evaluate(watchdogInput(started, stalled, stalledNow))
        val (firstRequest, firstTerminal) = requireNotNull(firstPlan.startRecovery)
        // baselineWasLoading 记录自启动那一刻；随后状态里的 wasLoading 已翻成 false
        val recoveredState = firstPlan.newSessionState.copy(wasLoading = false)

        val confirmed =
            watchdog.evaluate(
                watchdogInput(
                    recoveredState,
                    playbackInput(
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                        enginePositionMs = 1_000L,
                    ),
                    stalledNow + 1_000L,
                    recoveryOwnership = RecoveryOwnershipSnapshot(true, firstTerminal, sessionKey),
                )
            )

        // terminal 仍在 → 第 ⑥ 步仍可启动；确认/取消一律先释放旧恢复
        assertFalse(watchdog.isRecoveryCurrent(firstRequest))
        assertTrue(confirmed.cancelRecovery)
        assertTrue(requireNotNull(confirmed.startRecovery).first.attempt == 1)
    }

    @Test
    fun `错误与 seek 失败清空代次并投影错误`() {
        val watchdog = ProgressWatchdog()
        val stalled = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), stalled, 1_000L)
        val failed =
            watchdog.evaluate(
                watchdogInput(
                    started,
                    stalled.copy(hasPlayerError = true),
                    1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS,
                )
            )

        assertNull(failed.newSessionState.terminal)
        assertNull(failed.startRecovery)
        assertTrue(failed.sessionUiBase.showPlayerError)

        val seekFailed =
            watchdog.evaluate(
                watchdogInput(
                    started,
                    stalled.copy(hasSeekFailure = true),
                    1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS,
                )
            )

        assertNull(seekFailed.newSessionState.terminal)
        assertNull(seekFailed.startRecovery)
        assertTrue(seekFailed.sessionUiBase.showSeekFailure)
    }

    @Test
    fun `兜底事件被消费后不再重发`() {
        val watchdog = ProgressWatchdog()
        val input = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        val now = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS
        val snapshot = remoteHlsMpvSnapshot()
        val plan = watchdog.evaluate(watchdogInput(started, input, now, fallback = snapshot))
        val token = requireNotNull(plan.fallbackEventToken)

        val consumed =
            watchdog.consumeFallback(
                requestToken = token,
                snapshot = snapshot,
                playWhenReady = true,
                terminal = plan.newSessionState.terminal,
            )
        val again =
            watchdog.consumeFallback(
                requestToken = token,
                snapshot = snapshot,
                playWhenReady = true,
                terminal = plan.newSessionState.terminal,
            )

        assertEquals(token, consumed?.token)
        assertNull(again)
    }

    @Test
    fun `兜底发送失败后本代次不再重发`() {
        val watchdog = ProgressWatchdog()
        val input = playbackInput()
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        val now = 1_000L + PlaybackSessionRules.STALL_TIMEOUT_MS
        val snapshot = remoteHlsMpvSnapshot()
        val first = watchdog.evaluate(watchdogInput(started, input, now, fallback = snapshot))

        assertTrue(first.fallbackEventToken != null)
        watchdog.cancelFallback()
        val second = watchdog.evaluate(watchdogInput(started, input, now, fallback = snapshot))

        // 同会话代次已被记为 attempted；抑制也随之解除
        assertNull(second.fallbackEventToken)
        assertFalse(second.suppressTerminal)
        assertTrue(requireNotNull(second.startRecovery).first.sessionKey == sessionKey)
    }

    private fun watchdogInput(
        sessionState: PlaybackSessionState,
        session: PlaybackSessionInput,
        nowElapsedMs: Long,
        fallback: ProgressFallbackSnapshot = localSnapshot(),
        recoveryOwnership: RecoveryOwnershipSnapshot =
            RecoveryOwnershipSnapshot(active = false, terminal = null, sessionKey = null),
    ): WatchdogInput =
        WatchdogInput(
            nowElapsedMs = nowElapsedMs,
            sessionState = sessionState,
            session = session,
            fallback = fallback,
            recoveryOwnership = recoveryOwnership,
        )

    private fun playbackInput(
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false,
        isBuffering: Boolean = true,
        isReady: Boolean = false,
        enginePositionMs: Long? = null,
        hasPlayerError: Boolean = false,
        hasSeekFailure: Boolean = false,
    ): PlaybackSessionInput =
        PlaybackSessionInput(
            key = sessionKey,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isReady = isReady,
            enginePositionMs = enginePositionMs,
            bufferedPositionMs = null,
            hasPlayerError = hasPlayerError,
            hasSeekFailure = hasSeekFailure,
            persistedResumeMs = 0L,
        )

    private fun localSnapshot(): ProgressFallbackSnapshot =
        ProgressFallbackSnapshot(
            sessionKey = sessionKey,
            itemId = EPISODE_ID,
            mediaSourceId = SOURCE_ID,
            sourceUri = "https://media.example/movie.mp4",
            container = "mp4",
            mimeType = "video/mp4",
            backend = "exoplayer",
            autoFallbackEnabled = true,
        )

    private fun remoteHlsMpvSnapshot(): ProgressFallbackSnapshot =
        localSnapshot()
            .copy(
                sourceUri = "https://media.example/stream.m3u8",
                container = "hls",
                mimeType = "application/x-mpegURL",
                backend = "mpv",
            )

    private companion object {
        val EPISODE_ID: UUID = UUID.fromString("6f1d0a5c-0b3e-4c8e-9d97-2f3a4b5c6d7e")
        const val SOURCE_ID = "media-source-a"
        const val sessionKey = "episode-a|media-source-a|1|source-fingerprint|x"
    }
}
