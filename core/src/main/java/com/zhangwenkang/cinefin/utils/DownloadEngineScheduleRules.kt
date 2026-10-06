package com.zhangwenkang.cinefin.utils

/**
 * W76-Q7 加固：入队时对「下载引擎唯一工作链」当前状态的调度决策。
 *
 * 背景（W76-B9 遗留④）：删除任务后再次入队**偶发**不立即启动。排查入队链后的最强假设 = 竞态—— 新任务落库 `PENDING` 发生在「运行中的 worker
 * 已判定队列为空、正准备退出」与「worker 真正结束」之间； 入队方此时观察到唯一工作链仍是 `RUNNING` → 走「复用运行中 worker」分支直接返回，于是这条新任务没有任何
 * 调度者。此处把该决策抽成纯函数，并让「复用运行中 worker」这一分支额外触发一次 `kick`（见 `DownloaderImpl`），由运行中的 worker 在退出前消费，从而吃掉窗口。
 */
internal enum class EngineScheduleDecision {
    /** 链上已有运行中的 worker：交给它继续领取；调用方需置一次 `kick` 以确保它在退出前再确认队列。 */
    REUSE_RUNNING,
    /** 链上有未完成但**未运行**的 worker（等待退避 / 网络）：用户动作取消延迟并立即重启。 */
    RESTART_NOW,
    /** 链上已无未完成工作：入队即时 worker。 */
    ENQUEUE_NOW,
    /** 有未完成且非运行状态的 worker，但非用户动作：不打扰（等它自己醒）。 */
    WAIT,
}

internal object DownloadEngineScheduleRules {

    /**
     * @param hasRunningWorker 唯一工作链上是否存在 `RUNNING` 的 worker
     * @param hasUnfinishedWorker 是否存在任何**未完成**（`ENQUEUED` / `RUNNING` / `BLOCKED`）的 worker
     * @param forceStart 是否为用户动作（新下载 / 继续 / 重试）驱动的强制启动
     */
    fun decide(
        hasRunningWorker: Boolean,
        hasUnfinishedWorker: Boolean,
        forceStart: Boolean,
    ): EngineScheduleDecision =
        when {
            hasRunningWorker -> EngineScheduleDecision.REUSE_RUNNING
            !hasUnfinishedWorker -> EngineScheduleDecision.ENQUEUE_NOW
            forceStart -> EngineScheduleDecision.RESTART_NOW
            else -> EngineScheduleDecision.WAIT
        }
}
