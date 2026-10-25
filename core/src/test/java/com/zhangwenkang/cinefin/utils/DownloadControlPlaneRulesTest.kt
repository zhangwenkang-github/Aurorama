package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W84：下载控制面唯一集合、阶段解释、批量接收与角标口径。 */
class DownloadControlPlaneRulesTest {

    private fun task(
        itemId: UUID = UUID.randomUUID(),
        sourceId: String = "source-$itemId",
        status: DownloadTaskStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        failureReason: DownloadFailureReason? = null,
        retryCount: Int = 0,
        nextRetryAt: Long = 0L,
    ) =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = "任务",
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = status,
            failureReason = failureReason,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = 0L,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
        )

    @Test
    fun `活动唯一意图按 itemId 去重并剔除已完成落盘`() {
        val active = UUID.randomUUID()
        val completed = UUID.randomUUID()
        val tasks =
            listOf(
                task(itemId = active, sourceId = "active-a", status = DownloadTaskStatus.PENDING),
                task(itemId = active, sourceId = "active-b", status = DownloadTaskStatus.RUNNING),
                task(
                    itemId = completed,
                    sourceId = "completed",
                    status = DownloadTaskStatus.PAUSED,
                ),
                task(sourceId = "failed", status = DownloadTaskStatus.FAILED),
            )

        val projection =
            DownloadControlPlaneRules.activeUniqueIntents(
                tasks = tasks,
                downloadedItemIds = setOf(completed),
            )

        assertEquals(listOf(active), projection.map { it.itemId })
    }

    @Test
    fun `阶段分类使用注入时钟覆盖准备到完成`() {
        val now = 1_000_000L

        assertEquals(
            DownloadTaskPhase.PREPARING,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.PENDING,
                downloadedBytes = 0L,
                totalBytes = 0L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.CONNECTING,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.RUNNING,
                downloadedBytes = 0L,
                totalBytes = 100L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.DOWNLOADING,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.RUNNING,
                downloadedBytes = 40L,
                totalBytes = 100L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.VERIFYING,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.RUNNING,
                downloadedBytes = 100L,
                totalBytes = 100L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.WAITING_RETRY,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.PENDING,
                downloadedBytes = 40L,
                totalBytes = 100L,
                failureReason = DownloadFailureReason.SERVER_ERROR,
                nextRetryAt = now + 30_000L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.PAUSED,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.PAUSED,
                downloadedBytes = 40L,
                totalBytes = 100L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.FAILED,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.FAILED,
                downloadedBytes = 40L,
                totalBytes = 100L,
                failureReason = DownloadFailureReason.AUTHENTICATION,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            DownloadTaskPhase.COMPLETED,
            DownloadControlPlaneRules.phase(
                status = DownloadTaskStatus.COMPLETED,
                downloadedBytes = 100L,
                totalBytes = 100L,
                failureReason = null,
                nextRetryAt = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `等待解释同时给出原因码与下次重试时间`() {
        val waiting =
            task(
                status = DownloadTaskStatus.PENDING,
                downloadedBytes = 40L,
                totalBytes = 100L,
                failureReason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                retryCount = 2,
                nextRetryAt = 61_000L,
            )

        val explanation = DownloadControlPlaneRules.explanation(waiting, nowMs = 1_000L)

        assertEquals(DownloadTaskPhase.WAITING_RETRY, explanation?.phase)
        assertEquals(DownloadFailureReason.NETWORK_UNAVAILABLE, explanation?.reason)
        assertEquals(2, explanation?.retryCount)
        assertEquals(61_000L, explanation?.nextRetryAt)
    }

    @Test
    fun `非等待非失败任务没有解释详情`() {
        val downloading = task(status = DownloadTaskStatus.RUNNING, downloadedBytes = 10L)

        assertNull(DownloadControlPlaneRules.explanation(downloading, nowMs = 1_000L))
    }

    @Test
    fun `角标文本 0 隐藏 1 到 99 原数 100 以上收敛`() {
        assertEquals("", DownloadControlPlaneRules.badgeText(0))
        assertEquals("1", DownloadControlPlaneRules.badgeText(1))
        assertEquals("99", DownloadControlPlaneRules.badgeText(99))
        assertEquals("99+", DownloadControlPlaneRules.badgeText(100))
        assertEquals("99+", DownloadControlPlaneRules.badgeText(1_000))
    }

    @Test
    fun `音乐接收批次去重但绝不截断`() {
        val requests =
            (1..100).map { index ->
                DownloadMusicIntentRequest(
                    itemId = UUID(0L, index.toLong()),
                    name = "曲目 $index",
                    albumName = "专辑",
                    artist = "歌手",
                    trackIndex = index,
                )
            } + DownloadMusicIntentRequest(UUID(0L, 50L), "重复", "专辑", "歌手", 50)

        val normalized = DownloadControlPlaneRules.normalizeMusicBatch(requests)

        assertEquals(100, normalized.size)
        assertEquals("曲目 1", normalized.first().name)
        assertEquals("曲目 100", normalized.last().name)
    }

    @Test
    fun `失败全集按可重试任务去重`() {
        val itemId = UUID.randomUUID()
        val failedTasks =
            listOf(
                task(itemId = itemId, sourceId = "a", status = DownloadTaskStatus.FAILED),
                task(itemId = itemId, sourceId = "b", status = DownloadTaskStatus.FAILED),
                task(sourceId = "active", status = DownloadTaskStatus.RUNNING),
            )

        assertEquals(
            listOf(itemId),
            DownloadControlPlaneRules.retryableFailureIntents(failedTasks).map { it.itemId },
        )
    }

    @Test
    fun `取消保留残片而不是强制安全重下`() {
        assertEquals(
            DownloadResumeStrategy.RESUME,
            DownloadTaskRules.resumeStrategy(
                DownloadTaskStatus.FAILED,
                DownloadFailureReason.CANCELLED,
            ),
        )
    }
}
