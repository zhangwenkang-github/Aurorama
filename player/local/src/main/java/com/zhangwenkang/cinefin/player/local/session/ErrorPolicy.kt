package com.zhangwenkang.cinefin.player.local.session

import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback

/** 源解析失败（起播期拿不到任何可播条目）。 */
const val PLAYER_ERROR_SOURCE_RESOLUTION_FAILED = "SOURCE_RESOLUTION_FAILED"

/**
 * 起播期（还没出画面）源加载失败。
 *
 * 慢启动转码（服务端 30–60s 才出流）下 media3 内部先把 IO 错误重试完才上报一次， 两次上报间隔恒大于 播放中的 30s 重试窗口； 必须按次预算收敛，否则会无限静默重试、永远
 * BUFFERING。
 */
const val PLAYER_ERROR_STARTUP_SOURCE_FAILED = "STARTUP_SOURCE_FAILED"

/** 起播期源错误的原地重试上限：慢启动转码只给一次原地重试，之后交给错误卡片（重试 = 重新解析源）。 */
internal const val STARTUP_SOURCE_RETRY_MAX = 1

/**
 * 播放错误的唯一决策者（W2-T2）。
 *
 * 持有「当前已发布的错误」这一事实源、起播期源错误的重试预算、解码失败去重窗口。
 *
 * ViewModel 只做投影（[PlayerErrorInfo] → `_uiState.playerError`）与引擎动作（切换内核、prepare 等）。
 *
 * 错误发不发、发哪一种、还剩几次重试都由本类决定。
 *
 * 时钟、文案与内核名都由构造注入：本类保持纯 Kotlin，不 import android.* / androidx.*。
 */
internal class ErrorPolicy(
    private val nowMs: () -> Long,
    private val sourceResolutionMessage: () -> String,
    private val backendName: () -> String,
    maxStartupSourceRetries: Int = STARTUP_SOURCE_RETRY_MAX,
) {
    /** 当前已发布的错误；null = 没有错误卡片。唯一事实源，外部只读。 */
    var current: PlayerErrorInfo? = null
        private set

    /** 上次接管失败的时间戳（去重日志用）。 */
    val lastHandledAtMs: Long
        get() = lastHandledAtWallMs

    /** 起播期源错误已经消耗的重试次数。 */
    val startupSourceAttempts: Int
        get() = startupSourceRetryBudget.attempts

    /** 起播期源错误的重试预算；与播放中按 30s 窗口计数的那套完全独立。 */
    private val startupSourceRetryBudget =
        PlayerStartupSourceRetryBudget(maxAttempts = maxStartupSourceRetries)

    private var lastHandledKey: String? = null
    private var lastHandledAtWallMs: Long = 0L

    /** 新起播：收起上一轮的错误，并重置起播重试预算。 */
    fun onNewPlaybackAttempt() {
        current = null
        startupSourceRetryBudget.reset()
    }

    /** 起播期源解析失败（超时 / 异常 / 拿不到条目）：发布解析失败错误。 */
    fun onStartupResolutionFailed(): PlayerErrorInfo = publish(sourceResolutionFailure())

    /** 起播期内核错误（还没出画面）：统一显示「起播源失败」，重试 = 重新解析源。 */
    fun onStartupSourceFailed(): PlayerErrorInfo = publish(startupSourceFailure())

    /** 播放中的内核错误：按引擎上报的内容发布错误。 */
    fun onPlaybackError(message: String, codeName: String): PlayerErrorInfo =
        publish(PlayerErrorInfo(message = message, codeName = codeName, backend = backendName()))

    /**
     * 进度恢复耗尽仍未出画面：补发起播源错误，否则播放器会一直 BUFFERING、用户没有重试入口。
     *
     * 已出画面或已有错误卡片时返回 null（不重复发布），调用方据此跳过后续动作。
     */
    fun onStartupSourceExhausted(fileLoaded: Boolean): PlayerErrorInfo? {
        if (fileLoaded) return null
        if (current != null) return null
        return onStartupSourceFailed()
    }

    /** 能进 READY 就说明上一次失败已经恢复：收起错误并重开起播预算。 */
    fun onPlaybackFileLoaded() {
        current = null
        startupSourceRetryBudget.reset()
    }

    /** 手动收起错误卡片 / 原地重试：清空已发布错误，不动重试预算。 */
    fun clear() {
        current = null
    }

    /** 起播期源错误的原地重试是否还有预算；换 mediaId 会重开预算。 */
    fun shouldRetryStartupSource(mediaId: String): Boolean =
        startupSourceRetryBudget.shouldRetry(mediaId)

    /** 同一次失败的重复上报（同 key + 在去重窗口内）返回 false，调用方应直接吞掉。 */
    fun shouldHandleFailure(key: String): Boolean {
        val lastKey = lastHandledKey ?: return true
        if (lastKey != key) return true
        return nowMs() - lastHandledAtWallMs >= PlayerDecodeFallback.DUPLICATE_FAILURE_WINDOW_MS
    }

    /** 记录一次已接管的失败；去重窗口从这里起算。 */
    fun markFailureHandled(key: String) {
        lastHandledKey = key
        lastHandledAtWallMs = nowMs()
    }

    private fun publish(error: PlayerErrorInfo): PlayerErrorInfo {
        current = error
        return error
    }

    private fun sourceResolutionFailure(): PlayerErrorInfo =
        PlayerErrorInfo(
            message = sourceResolutionMessage(),
            codeName = PLAYER_ERROR_SOURCE_RESOLUTION_FAILED,
            backend = backendName(),
        )

    private fun startupSourceFailure(): PlayerErrorInfo =
        PlayerErrorInfo(
            message = sourceResolutionMessage(),
            codeName = PLAYER_ERROR_STARTUP_SOURCE_FAILED,
            backend = backendName(),
        )
}

/**
 * 起播期（还没出画面）源错误的原地重试预算。
 *
 * 慢启动转码的服务端 30–60s 才出流，media3 自己会先把 IO 错误重试完再上报一次， 两次上报间隔恒大于播放中的 30s 重试窗口。
 *
 * 这里只按次数与 mediaId 归属计数、与时钟无关：同一源的第 N 次错误才消耗预算， 换了媒体源（mediaId 变化）或重新出画面（STATE_READY）后重开。
 */
internal class PlayerStartupSourceRetryBudget(private val maxAttempts: Int) {
    var attempts: Int = 0
        private set

    private var mediaId: String? = null

    fun reset() {
        attempts = 0
        mediaId = null
    }

    /** 记录一次起播期源错误；返回 false 表示预算耗尽，应交给错误卡片。 */
    fun shouldRetry(mediaId: String): Boolean {
        if (this.mediaId != mediaId) {
            this.mediaId = mediaId
            attempts = 0
        }
        if (attempts >= maxAttempts) return false
        attempts++
        return true
    }
}
