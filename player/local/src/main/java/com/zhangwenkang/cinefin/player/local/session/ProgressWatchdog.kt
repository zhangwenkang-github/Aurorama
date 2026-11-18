package com.zhangwenkang.cinefin.player.local.session

import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackRequest
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryPhase
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryRequest
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryStatus
import com.zhangwenkang.cinefin.player.local.domain.playbackMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.playerProgressFallbackSourceKind
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionInput
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionRules
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionState
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import java.util.UUID

/**
 * 进度看门狗（W2-T3b）：`refreshPlaybackSession` 的「判定 + 分发」唯一入口。
 *
 * 输入是 ViewModel 采集好的只读快照（播放器状态、会话键、seek 字段、媒体身份来源字段、恢复 job 现状）。
 *
 * 输出是 [WatchdogPlan]——一份有序计划，ViewModel 按序执行副作用（写回会话状态、retain 恢复 job、发事件、cancel / start 恢复、UI 投影）。
 *
 * 持有进度兜底（[PlayerProgressFallbackController]）与进度恢复（[PlayerProgressRecoveryController]）两个控制器；ViewModel
 * 只经本类转发。
 *
 * 决策顺序与 T3b 之前的 `refreshPlaybackSession` 逐行等价：terminal 仍只由 [PlaybackSessionRules.advance]
 * 产生，兜底与恢复的调用时序、短路条件不变。
 *
 * 本类保持纯 Kotlin，不 import `android.*` / `androidx.*`；只临时复用 `presentation` 包的会话类型（T1
 * 已确立的反向依赖，后续任务再搬迁）。
 */
internal class ProgressWatchdog {
    private val fallbackController = PlayerProgressFallbackController()
    private val recoveryController = PlayerProgressRecoveryController()

    /**
     * 推进一个看门狗周期：判定顺序固定为
     *
     * ① `advance` 推进会话状态（terminal 唯一起源）→ ② 恢复 job 归属判定 → ③ 兜底评估 → ④ 恢复观察 → ⑤ 抑制判定 → ⑥ 恢复启动 → ⑦ UI
     * 投影。
     *
     * ②/④ 的控制器清除在本类内完成（控制器归本类所有），ViewModel 只按计划清理协程侧。
     */
    fun evaluate(input: WatchdogInput): WatchdogPlan {
        val session = input.session
        val sessionKey = session.key
        val next = PlaybackSessionRules.advance(input.sessionState, session, input.nowElapsedMs)
        val terminal = next.terminal
        val retainsOwnership =
            recoveryRetains(
                activeTerminal = input.recoveryOwnership.terminal,
                activeSessionKey = input.recoveryOwnership.sessionKey,
                currentTerminal = terminal,
                currentSessionKey = sessionKey,
            )
        if (!retainsOwnership) recoveryController.cancel()
        val fallbackRequest =
            fallbackController.evaluate(input.fallback.toInput(session.playWhenReady, terminal))
        val recoveryInput = input.recoveryInput(next)
        val observation = recoveryController.observe(recoveryInput)
        val cancelRecovery =
            observation.phase == PlayerProgressRecoveryPhase.CONFIRMED ||
                observation.phase == PlayerProgressRecoveryPhase.CANCELLED
        if (cancelRecovery) recoveryController.cancel()
        val suppressTerminal = fallbackController.suppressesTerminal(sessionKey)
        val ownershipActive = input.recoveryOwnership.active && retainsOwnership && !cancelRecovery
        val startRecovery =
            if (terminal != null && !suppressTerminal && !ownershipActive) {
                recoveryController.begin(recoveryInput)?.let { request -> request to terminal }
            } else {
                null
            }
        return WatchdogPlan(
            newSessionState = next,
            retainTerminal = terminal,
            fallbackEventToken = fallbackRequest?.token,
            cancelRecovery = cancelRecovery,
            startRecovery = startRecovery,
            suppressTerminal = suppressTerminal,
            sessionUiBase =
                PlaybackSessionRules.project(
                    next,
                    session,
                    suppressTerminal = suppressTerminal,
                ),
        )
    }

    /** 进度兜底事件发送失败：本代次不再重发。 */
    fun cancelFallback() = fallbackController.cancelCurrentGeneration()

    /** 兜底事件被播放页接受：只有仍匹配当前快照的请求才算兑现。 */
    fun consumeFallback(
        requestToken: String,
        snapshot: ProgressFallbackSnapshot,
        playWhenReady: Boolean,
        terminal: PlayerViewModel.PlaybackRecovery?,
    ): PlayerProgressFallbackRequest? =
        fallbackController.consume(requestToken, snapshot.toInput(playWhenReady, terminal))

    /** 显式退出 / 新起播：连恢复状态一起清空（协程侧由 ViewModel 另行 cancel）。 */
    fun cancelRecovery() = recoveryController.cancel()

    fun isRecoveryCurrent(request: PlayerProgressRecoveryRequest): Boolean =
        recoveryController.isCurrent(request)

    fun nextRecoveryAttempt(
        request: PlayerProgressRecoveryRequest
    ): PlayerProgressRecoveryRequest? = recoveryController.nextAttempt(request)

    fun progressRecoveryStatus(): PlayerProgressRecoveryStatus? = recoveryController.status()

    private fun WatchdogInput.recoveryInput(
        next: PlaybackSessionState
    ): PlayerProgressRecoveryInput {
        val terminal = next.terminal
        return PlayerProgressRecoveryInput(
            sessionKey = session.key,
            mediaIdentity = fallback.mediaIdentity(),
            targetPositionMs = terminal?.positionMs ?: 0L,
            terminalKind = terminal?.kind,
            terminalToken = terminal?.token,
            playWhenReady = session.playWhenReady,
            hasPlayerError = session.hasPlayerError,
            hasSeekFailure = session.hasSeekFailure,
            enginePositionMs = session.enginePositionMs,
            bufferedPositionMs = session.bufferedPositionMs,
            isReady = session.isReady,
            isPlaying = session.isPlaying,
            wasLoading = sessionState.wasLoading,
        )
    }
}

/** 一个看门狗周期的输入快照；全部字段都由 ViewModel 采集，本类不改写。 */
internal data class WatchdogInput(
    val nowElapsedMs: Long,
    /** 采集时的会话状态（advance 之前）。 */
    val sessionState: PlaybackSessionState,
    /** 采集到的播放器读数；同时喂给 advance 与兜底 / 恢复判定。 */
    val session: PlaybackSessionInput,
    /** 进度兜底需要的媒体来源字段。 */
    val fallback: ProgressFallbackSnapshot,
    /** 恢复协程 job 的现状；步骤⑥ 的短路条件要用它。 */
    val recoveryOwnership: RecoveryOwnershipSnapshot,
)

/**
 * 进度兜底判定所需的媒体来源原始字段。
 *
 * 媒体身份与源类型都在看门狗内由这些原始字段推出，避免把 media3 的 `MediaItem` 漏进 session 包。
 */
internal data class ProgressFallbackSnapshot(
    val sessionKey: String?,
    val itemId: UUID?,
    val mediaSourceId: String?,
    val sourceUri: String?,
    val container: String?,
    val mimeType: String?,
    val backend: String?,
    val autoFallbackEnabled: Boolean,
)

/** 恢复协程 job 的只读现状：是否在跑、当前终端、所属会话键。 */
internal data class RecoveryOwnershipSnapshot(
    val active: Boolean,
    val terminal: PlayerViewModel.PlaybackRecovery?,
    val sessionKey: String?,
)

/** 一个看门狗周期的有序计划；ViewModel 只按这个顺序落副作用，不再自行判定。 */
internal data class WatchdogPlan(
    /** 步骤①：advance 产物，ViewModel 写回 `PlaybackSessionOwner`。 */
    val newSessionState: PlaybackSessionState,
    /** 步骤②：retain 恢复 job 用的终端（与当前会话键一起传给 `retainTerminal`）。 */
    val retainTerminal: PlayerViewModel.PlaybackRecovery?,
    /** 步骤③：非 null = ViewModel 发兜底事件；发送失败回调 [ProgressWatchdog.cancelFallback]。 */
    val fallbackEventToken: String?,
    /** 步骤④：观察结果是 CONFIRMED / CANCELLED，恢复 job 必须释放。 */
    val cancelRecovery: Boolean,
    /** 步骤⑥：非 null = 启动新一轮恢复（请求 + 终端）。 */
    val startRecovery: Pair<PlayerProgressRecoveryRequest, PlayerViewModel.PlaybackRecovery>?,
    /** 步骤⑤：兜底已接手本代次，terminal 与恢复都不得再投影。 */
    val suppressTerminal: Boolean,
    /** 步骤⑦：UI 投影基座（不含 `progressRecovery` 行，由 ViewModel 用控制器现状补）。 */
    val sessionUiBase: PlayerViewModel.PlaybackSessionUi,
)

/**
 * 恢复 job 是否仍归属本周期：新终端不是当前恢复终端、或会话键已换，job 与恢复状态都必须释放。
 *
 * 判定与 T3b 之前的 `ProgressRecoveryJobOwner.retainTerminal` 逐条一致（无在跑终端时不动）。
 *
 * 看门狗与 job owner 共用本函数，保证「计划里的判定」和「VM 实际执行的 retain」不会分叉。
 */
internal fun recoveryRetains(
    activeTerminal: PlayerViewModel.PlaybackRecovery?,
    activeSessionKey: String?,
    currentTerminal: PlayerViewModel.PlaybackRecovery?,
    currentSessionKey: String?,
): Boolean {
    if (activeTerminal == null) return true
    return currentTerminal != null &&
        currentTerminal.token == activeTerminal.token &&
        currentSessionKey == activeSessionKey
}

private fun ProgressFallbackSnapshot.mediaIdentity() = playbackMediaIdentity(itemId, mediaSourceId)

private fun ProgressFallbackSnapshot.toInput(
    playWhenReady: Boolean,
    terminal: PlayerViewModel.PlaybackRecovery?,
): PlayerProgressFallbackInput =
    PlayerProgressFallbackInput(
        sessionKey = sessionKey,
        mediaIdentity = mediaIdentity(),
        sourceKind =
            playerProgressFallbackSourceKind(
                sourceUri = sourceUri,
                container = container,
                mimeType = mimeType,
            ),
        backend = backend,
        autoFallbackEnabled = autoFallbackEnabled,
        playWhenReady = playWhenReady,
        terminalKind = terminal?.kind,
        terminalToken = terminal?.token,
        positionMs = terminal?.positionMs ?: -1L,
    )
