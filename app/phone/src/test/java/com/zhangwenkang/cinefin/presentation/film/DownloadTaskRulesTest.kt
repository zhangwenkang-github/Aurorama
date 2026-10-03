package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadResumeStrategy
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W50 自研下载引擎任务状态机 / 恢复策略 / 退避纯函数单测。 */
class DownloadTaskRulesTest {

    @Test
    fun `持久化状态优先_无状态时按路径推断`() {
        assertEquals(
            DownloadTaskStatus.COMPLETED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.COMPLETED.name,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.FAILED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.FAILED.name,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.PAUSED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.PAUSED.name,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.COMPLETED,
            DownloadTaskRules.resolveStatus(persistedStatus = null, pathIsPartial = false),
        )
        assertEquals(
            DownloadTaskStatus.FAILED,
            DownloadTaskRules.resolveStatus(persistedStatus = null, pathIsPartial = true),
        )
    }

    @Test
    fun `中断的运行中任务降级为等待调度而不是失败`() {
        assertEquals(
            DownloadTaskStatus.PENDING,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.RUNNING.name,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.PENDING,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.PENDING.name,
                pathIsPartial = true,
            ),
        )
    }

    @Test
    fun `旧DownloadManager进行中任务需重下_已完成与新引擎任务不误判`() {
        assertTrue(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = 42L,
                status = DownloadTaskStatus.RUNNING,
                pathIsPartial = true,
            )
        )
        assertTrue(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = 42L,
                status = DownloadTaskStatus.PAUSED,
                pathIsPartial = true,
            )
        )
        assertTrue(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = 42L,
                status = null,
                pathIsPartial = true,
            )
        )
        assertFalse(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = 42L,
                status = DownloadTaskStatus.COMPLETED,
                pathIsPartial = false,
            )
        )
        assertFalse(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = null,
                status = DownloadTaskStatus.RUNNING,
                pathIsPartial = true,
            )
        )
        assertFalse(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 1,
                downloadId = 42L,
                status = DownloadTaskStatus.RUNNING,
                pathIsPartial = true,
            )
        )
    }

    @Test
    fun `恢复策略_暂停续传_残片失效重下_空间不足阻止`() {
        assertEquals(
            DownloadResumeStrategy.RESUME,
            DownloadTaskRules.resumeStrategy(DownloadTaskStatus.PAUSED, null),
        )
        assertEquals(
            DownloadResumeStrategy.RESUME,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            ),
        )
        assertEquals(
            DownloadResumeStrategy.RESTART,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.CANNOT_RESUME,
            ),
        )
        assertEquals(
            DownloadResumeStrategy.RESTART,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.CANCELLED,
            ),
        )
        assertEquals(
            DownloadResumeStrategy.BLOCKED,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.STORAGE_INSUFFICIENT,
            ),
        )
    }

    @Test
    fun `自动重试资格_网络无限_服务器限次_鉴权与空间不自动`() {
        assertTrue(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.PENDING,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
                retryCount = 99,
            )
        )
        assertTrue(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.SERVER_ERROR,
                retryCount = DownloadTaskRules.MAX_SERVER_RETRIES - 1,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.SERVER_ERROR,
                retryCount = DownloadTaskRules.MAX_SERVER_RETRIES,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.AUTHENTICATION,
                retryCount = 0,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.STORAGE_INSUFFICIENT,
                retryCount = 0,
            )
        )
        assertTrue(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.CANNOT_RESUME,
                retryCount = DownloadTaskRules.MAX_RESUME_RETRIES - 1,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.CANNOT_RESUME,
                retryCount = DownloadTaskRules.MAX_RESUME_RETRIES,
            )
        )
    }

    @Test
    fun `指数退避_30秒起步翻倍并封顶30分钟`() {
        assertEquals(0L, DownloadTaskRules.backoffDelayMs(0))
        assertEquals(30_000L, DownloadTaskRules.backoffDelayMs(1))
        assertEquals(60_000L, DownloadTaskRules.backoffDelayMs(2))
        assertEquals(120_000L, DownloadTaskRules.backoffDelayMs(3))
        assertEquals(
            DownloadTaskRules.BACKOFF_MAX_MS,
            DownloadTaskRules.backoffDelayMs(20),
        )
    }

    @Test
    fun `续传偏移_文件缺失归零_记录超出截断`() {
        assertEquals(
            0L,
            DownloadTaskRules.initialOffset(1_000L, fileExists = false, fileLength = 0L),
        )
        assertEquals(
            1_000L,
            DownloadTaskRules.initialOffset(1_000L, fileExists = true, fileLength = 1_000L),
        )
        assertEquals(
            600L,
            DownloadTaskRules.initialOffset(1_000L, fileExists = true, fileLength = 600L),
        )
        assertEquals(
            600L,
            DownloadTaskRules.initialOffset(0L, fileExists = true, fileLength = 600L),
        )
    }

    @Test
    fun `同时下载数钳制到1到8`() {
        assertEquals(2, DownloadTaskRules.DEFAULT_CONCURRENT_TASKS)
        assertEquals(1, DownloadTaskRules.coerceConcurrency(0))
        assertEquals(1, DownloadTaskRules.coerceConcurrency(1))
        assertEquals(2, DownloadTaskRules.coerceConcurrency(2))
        assertEquals(8, DownloadTaskRules.coerceConcurrency(8))
        assertEquals(8, DownloadTaskRules.coerceConcurrency(9))
        assertEquals(8, DownloadTaskRules.coerceConcurrency(100))
    }

    @Test
    fun `任务操作可用性与分组`() {
        assertTrue(DownloadTaskRules.canPause(DownloadTaskStatus.RUNNING))
        assertTrue(DownloadTaskRules.canPause(DownloadTaskStatus.PENDING))
        assertFalse(DownloadTaskRules.canPause(DownloadTaskStatus.PAUSED))
        assertTrue(DownloadTaskRules.canResume(DownloadTaskStatus.PAUSED))
        assertTrue(DownloadTaskRules.canRetry(DownloadTaskStatus.FAILED))
        assertEquals(
            DownloadTaskGroup.ACTIVE,
            DownloadTaskRules.groupKey(DownloadTaskStatus.PAUSED),
        )
        assertEquals(
            DownloadTaskGroup.FAILED,
            DownloadTaskRules.groupKey(DownloadTaskStatus.FAILED),
        )
        assertEquals(
            DownloadTaskGroup.COMPLETED,
            DownloadTaskRules.groupKey(DownloadTaskStatus.COMPLETED),
        )
    }

    @Test
    fun `活动队列状态只含排队下载与暂停`() {
        assertTrue(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.PENDING))
        assertTrue(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.RUNNING))
        assertTrue(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.PAUSED))
        assertFalse(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.COMPLETED))
        assertFalse(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.FAILED))
    }
}
