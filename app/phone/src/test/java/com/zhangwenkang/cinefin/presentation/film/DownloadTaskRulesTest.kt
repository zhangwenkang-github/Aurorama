package com.zhangwenkang.cinefin.presentation.film

import android.app.DownloadManager
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadResumeStrategy
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W32 下载任务状态机 / 恢复策略纯函数单测。 */
class DownloadTaskRulesTest {

    @Test
    fun `DownloadManager 状态优先于持久化状态`() {
        assertEquals(
            DownloadTaskStatus.COMPLETED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.PAUSED.name,
                managerStatus = DownloadManager.STATUS_SUCCESSFUL,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.RUNNING,
            DownloadTaskRules.resolveStatus(
                persistedStatus = null,
                managerStatus = DownloadManager.STATUS_RUNNING,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.PAUSED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = null,
                managerStatus = DownloadManager.STATUS_PAUSED,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.FAILED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = null,
                managerStatus = DownloadManager.STATUS_FAILED,
                pathIsPartial = true,
            ),
        )
    }

    @Test
    fun `没有系统任务时回落到持久化状态`() {
        assertEquals(
            DownloadTaskStatus.PAUSED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.PAUSED.name,
                managerStatus = null,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.FAILED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = null,
                managerStatus = null,
                pathIsPartial = true,
            ),
        )
        assertEquals(
            DownloadTaskStatus.COMPLETED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = null,
                managerStatus = null,
                pathIsPartial = false,
            ),
        )
        assertEquals(
            DownloadTaskStatus.FAILED,
            DownloadTaskRules.resolveStatus(
                persistedStatus = DownloadTaskStatus.RUNNING.name,
                managerStatus = null,
                pathIsPartial = true,
            ),
        )
    }

    @Test
    fun `失败原因映射到可读分类`() {
        assertEquals(
            DownloadFailureReason.STORAGE_INSUFFICIENT,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_FAILED,
                managerReason = DownloadManager.ERROR_INSUFFICIENT_SPACE,
            ),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_PAUSED,
                managerReason = DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            ),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_PAUSED,
                managerReason = DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            ),
        )
        assertEquals(
            DownloadFailureReason.SERVER_ERROR,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_FAILED,
                managerReason = DownloadManager.ERROR_HTTP_DATA_ERROR,
            ),
        )
        assertEquals(
            DownloadFailureReason.CANNOT_RESUME,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_FAILED,
                managerReason = DownloadManager.ERROR_CANNOT_RESUME,
            ),
        )
        assertNull(
            DownloadTaskRules.resolveFailureReason(
                persistedReason = null,
                managerStatus = DownloadManager.STATUS_RUNNING,
                managerReason = DownloadManager.ERROR_UNKNOWN,
            )
        )
    }

    @Test
    fun `持久化失败原因在系统任务缺失时保留`() {
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadTaskRules.resolveFailureReason(
                persistedReason = DownloadFailureReason.NETWORK_UNAVAILABLE.name,
                managerStatus = null,
                managerReason = null,
            ),
        )
    }

    @Test
    fun `恢复策略_空间不足阻止自动重试_系统暂停等待网络自愈`() {
        assertEquals(
            DownloadResumeStrategy.BLOCKED,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.STORAGE_INSUFFICIENT,
            ),
        )
        assertEquals(
            DownloadResumeStrategy.RESTART,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            ),
        )
        assertEquals(
            DownloadResumeStrategy.WAIT_FOR_SYSTEM,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.PAUSED,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            ),
        )
    }

    @Test
    fun `只有网络与服务器类失败允许自动重试`() {
        assertTrue(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            )
        )
        assertTrue(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.SERVER_ERROR,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.STORAGE_INSUFFICIENT,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.RUNNING,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            )
        )
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
}
