package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W76-Q7 单测：入队时对「下载引擎唯一工作链」状态的调度决策（纯函数）。
 *
 * 关键回归：链上有运行中的 worker（`hasRunningWorker = true`）时，无论是否用户动作都必须走
 * [EngineScheduleDecision.REUSE_RUNNING] 并触发一次 kick —— 这正是「删除后重入队偶发不启动」竞态的封堵点。
 */
class DownloadEngineScheduleRulesTest {

    @Test
    fun `运行中的 worker 一律复用并置 kick`() {
        for (forceStart in listOf(true, false)) {
            assertEquals(
                EngineScheduleDecision.REUSE_RUNNING,
                DownloadEngineScheduleRules.decide(
                    hasRunningWorker = true,
                    hasUnfinishedWorker = true,
                    forceStart = forceStart,
                ),
            )
        }
    }

    @Test
    fun `链上无未完成工作时入队即时 worker`() {
        assertEquals(
            EngineScheduleDecision.ENQUEUE_NOW,
            DownloadEngineScheduleRules.decide(
                hasRunningWorker = false,
                hasUnfinishedWorker = false,
                forceStart = true,
            ),
        )
        assertEquals(
            EngineScheduleDecision.ENQUEUE_NOW,
            DownloadEngineScheduleRules.decide(
                hasRunningWorker = false,
                hasUnfinishedWorker = false,
                forceStart = false,
            ),
        )
    }

    @Test
    fun `仅有等待中的未完成 worker 时按是否用户动作区分`() {
        assertEquals(
            EngineScheduleDecision.RESTART_NOW,
            DownloadEngineScheduleRules.decide(
                hasRunningWorker = false,
                hasUnfinishedWorker = true,
                forceStart = true,
            ),
        )
        assertEquals(
            EngineScheduleDecision.WAIT,
            DownloadEngineScheduleRules.decide(
                hasRunningWorker = false,
                hasUnfinishedWorker = true,
                forceStart = false,
            ),
        )
    }
}
