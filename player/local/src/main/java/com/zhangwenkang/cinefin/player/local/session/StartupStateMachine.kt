package com.zhangwenkang.cinefin.player.local.session

/**
 * 起播相位（W2-T3a）：只描述一次起播走到哪一步。
 *
 * 错误判定、重试预算与恢复动作不在这里，仍归 [ErrorPolicy] 与 ViewModel。
 */
internal enum class StartupPhase {
    /** 没有起播在进行：初始态、显式退出后、以及起播收尾后的复位态。 */
    IDLE,

    /** 已声明起播意图，正在解析条目、拉播放信息。 */
    RESOLVING,

    /** 已拿到 startItem，即将把媒体交给播放器。 */
    PREPARING,

    /** 已出画面。 */
    READY,

    /** 起播期解析失败：超时、异常或拿不到条目。 */
    FAILED,
}

/**
 * 起播显式状态机（W2-T3a：strangler 第二步）。
 *
 * 收编 ViewModel 里的 `startupInProgress` 布尔：起播窗口等价于相位落在 [StartupPhase.RESOLVING] 或
 * [StartupPhase.PREPARING]。
 *
 * 只搬状态，不吞并错误 / 恢复逻辑；非法迁移一律拒绝（返回 false，不抛异常），避免晚到的异步结果改坏新一次起播的相位。
 *
 * 每次 [begin] 递增 [generation] 并记录 [sourceIntentId]：调用方保存返回值，就能在异步结果回来时判定归属。
 *
 * 本类保持纯 Kotlin，不依赖 android.* / androidx.*。
 */
internal class StartupStateMachine {
    /** 当前相位；只有本类按迁移表写入。 */
    var phase: StartupPhase = StartupPhase.IDLE
        private set

    /** 本次起播的代次：每次 [begin] 单调递增，复位回 IDLE 不递增。 */
    var generation: Long = 0L
        private set

    /** 本次起播的源意图 id（`SourceResolutionIntent.sourceIntentId`）；复位到 IDLE 后保留上一轮的值。 */
    var sourceIntentId: String? = null
        private set

    /** 起播窗口：与旧 `startupInProgress` 真值一致，即相位既不是 IDLE 也不是 FAILED。 */
    val startupInProgress: Boolean
        get() = phase == StartupPhase.RESOLVING || phase == StartupPhase.PREPARING

    /**
     * 新起播：从任意相位经 IDLE 复位后进入 [StartupPhase.RESOLVING]。
     *
     * 返回本次起播的代次，供调用方做归属判定。
     */
    fun begin(sourceIntentId: String): Long {
        if (phase != StartupPhase.IDLE) transitionTo(StartupPhase.IDLE)
        generation += 1L
        this.sourceIntentId = sourceIntentId
        transitionTo(StartupPhase.RESOLVING)
        return generation
    }

    /** 拿到 startItem、即将把媒体交给播放器。[StartupPhase.RESOLVING] 之外一律拒绝。 */
    fun markPreparing(): Boolean = transitionTo(StartupPhase.PREPARING)

    /** 出画面。[StartupPhase.PREPARING] 之外一律拒绝。 */
    fun markReady(): Boolean = transitionTo(StartupPhase.READY)

    /** 起播期失败；代次不是当前这次起播时拒绝，防止旧协程把新一次起播标成失败。 */
    fun markFailed(generation: Long): Boolean =
        this.generation == generation && transitionTo(StartupPhase.FAILED)

    /** 复位：显式退出 / 释放播放器，以及新起播开始前的统一入口。 */
    fun resetToIdle(): Boolean = transitionTo(StartupPhase.IDLE)

    /** 归属判定：该代次仍是当前这次起播；已复位到 IDLE 的旧代次不再归属。 */
    fun isCurrent(generation: Long): Boolean =
        phase != StartupPhase.IDLE && this.generation == generation

    private fun transitionTo(target: StartupPhase): Boolean {
        if (!isLegalTransition(phase, target)) return false
        phase = target
        return true
    }

    private companion object {
        /**
         * 合法迁移表。
         *
         * IDLE→RESOLVING；RESOLVING→PREPARING / FAILED / IDLE；PREPARING→READY / FAILED /
         * IDLE；READY→IDLE；FAILED→IDLE。
         */
        fun isLegalTransition(from: StartupPhase, to: StartupPhase): Boolean =
            when (from) {
                StartupPhase.IDLE -> to == StartupPhase.RESOLVING
                StartupPhase.RESOLVING ->
                    to == StartupPhase.PREPARING ||
                        to == StartupPhase.FAILED ||
                        to == StartupPhase.IDLE
                StartupPhase.PREPARING ->
                    to == StartupPhase.READY || to == StartupPhase.FAILED || to == StartupPhase.IDLE
                StartupPhase.READY -> to == StartupPhase.IDLE
                StartupPhase.FAILED -> to == StartupPhase.IDLE
            }
    }
}
