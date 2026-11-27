package com.zhangwenkang.cinefin.player.local.presentation

/**
 * 2.0.3-A Bug 11：内核切换失败后的恢复策略（纯函数 + 进程级熔断，便于单测）。
 *
 * 背景（真机反馈）：切内核失败后播放页无条件走 `recreate()` 兜底重启，重启又把"当前条目"按裸 id + 裸 kind 重写成 Intent——条目一旦进入 `getEpisode`
 * 的 fail-closed 分支（服务器把它标成 Video / 缺 seriesId）， 每点一次就重启一次、每次都失败，表现成"该条目再也无法播放"；Pad 上更是任何条目都进不去。
 *
 * 这里的策略只有一条底线：**旧内核还能用时绝不重启页面**；确实没有可用实例时才允许重启一次， 且同一「条目 + 目标内核」在窗口内失败超过上限就熔断，改由错误卡片把选择权交回用户。
 */

/** 重启兜底要对准的条目（与宿主 Intent 的 `itemId` / `itemKind` / `mediaSourceId` 一一对应）。 */
data class PlaybackRestartSpec(
    val itemId: String,
    val itemKind: String,
    val mediaSourceId: String? = null,
)

/** 切换失败后的处置：是否整页重启、重启对准哪个条目。 */
data class BackendSwitchRecoveryPlan(
    val restart: Boolean,
    val target: PlaybackRestartSpec?,
)

/**
 * 内核自愈事件（2.0.3-F1 P3-6）。
 *
 * 持久化内核在本机建不出来、[PlayerHolder] 自动回落到 ExoPlayer 并复位偏好时发出：宿主据此用既有的 内核切换提示条告知用户（不静默改写用户选择）。
 *
 * @param id 单调递增序号：播放页用"已提示过的最大 id"去重，播放页重建后也能补提示一次。
 */
data class BackendSelfHeal(
    val id: Long,
    val failedBackend: String,
    val recoveredBackend: String,
)

/**
 * 新建目标内核实例失败时的自愈目标。
 *
 * 持久化的 `pref_player_backend` 落在某个内核上而该内核在本机建不出来（libmpv 初始化失败、vo 配置坏档等）时，
 * 继续按偏好重建只会让**每一次**起播都失败——整机进入"任何条目都播不了"的死锁。这里给出可回落的备选内核： 只允许回落到 ExoPlayer（系统内置，不依赖本地原生库）；ExoPlayer
 * 本身失败时返回 null，由调用方原样抛出。
 */
fun backendFallbackAfterCreateFailure(requested: String): String? =
    if (requested != PlayerHolder.BACKEND_EXOPLAYER) PlayerHolder.BACKEND_EXOPLAYER else null

/**
 * 切换失败后是否允许走整页重启兜底。
 *
 * - `keptPreviousBackend = true`：旧实例还在播，重启只会丢队列与位置，永远不重启；
 * - 没有重启目标：重建后拿不到条目，重启等于把播放页关掉，也不重启；
 * - 其余情况按熔断器决定（同一「条目 + 目标内核」短时间内反复失败即熔断）。
 */
fun shouldRestartAfterSwitchFailure(
    keptPreviousBackend: Boolean,
    hasRestartTarget: Boolean,
    circuitClosed: Boolean,
): Boolean = !keptPreviousBackend && hasRestartTarget && circuitClosed

/**
 * 内核切换 / 整页重启的进程级熔断器。
 *
 * 进程级而不是偏好级：`recreate()` 会重建播放页的 ViewModel，只有进程级单例能跨重建记得住失败次数；
 * 进程重启后计数清零也符合预期（用户重新进入时允许再试一次）。命中上限即"熔断"， 之后同一组合的切换请求被丢弃，交给错误卡片让用户显式换内核或重试。
 */
class BackendSwitchCircuitBreaker(
    private val maxFailures: Int = MAX_FAILURES,
    private val windowMs: Long = WINDOW_MS,
) {
    private data class Failure(
        val itemKey: String,
        val target: String,
        val attempts: Int,
        val atMs: Long,
    )

    private var last: Failure? = null

    /** 当前是否允许再试（未熔断）。不同条目 / 不同目标内核视为新组合，直接放行。 */
    fun allows(itemKey: String, target: String, nowMs: Long): Boolean {
        val failure = last ?: return true
        if (failure.itemKey != itemKey || failure.target != target) return true
        if (nowMs - failure.atMs > windowMs) return true
        return failure.attempts < maxFailures
    }

    /** 记一次失败；同一组合在窗口内累加，超窗口或换组合重新计数。 */
    fun recordFailure(itemKey: String, target: String, nowMs: Long) {
        val previous = last
        val attempts =
            if (
                previous != null &&
                    previous.itemKey == itemKey &&
                    previous.target == target &&
                    nowMs - previous.atMs <= windowMs
            ) {
                previous.attempts + 1
            } else {
                1
            }
        last = Failure(itemKey = itemKey, target = target, attempts = attempts, atMs = nowMs)
    }

    /** 播放真的出画面 / 用户显式重试：清空历史，下一次失败从 1 重新计数。 */
    fun reset() {
        last = null
    }

    companion object {
        /** 同一「条目 + 目标内核」允许的重启兜底次数：正常一次就够，第二次再失败说明重启救不了。 */
        const val MAX_FAILURES = 2

        /** 熔断窗口：超过这个时长的旧失败不再压制新的切换请求。 */
        const val WINDOW_MS = 60_000L
    }
}
