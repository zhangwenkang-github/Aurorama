package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalDownloadProjectionContractTest {

    @Test
    fun `page and notification consume one canonical active projection`() {
        val completed =
            task(
                itemId = UUID(0L, 900L),
                sourceId = "completed",
                status = DownloadTaskStatus.RUNNING,
                totalBytes = 99_000L,
            )
        val active =
            (1L..12L).map { index ->
                task(
                    itemId = UUID(0L, 1_000L + index),
                    sourceId = "episode-$index",
                    status =
                        if (index == 1L) DownloadTaskStatus.PAUSED else DownloadTaskStatus.RUNNING,
                    downloadedBytes = index * 100L,
                    totalBytes = index * 10_000L,
                    updatedAt = index,
                    taskGeneration = 1L,
                )
            }
        val olderDuplicate =
            active
                .first()
                .copy(
                    downloadedBytes = 1L,
                    totalBytes = 2L,
                    updatedAt = 0L,
                )
        val snapshot =
            DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                tasks = listOf(completed) + active + olderDuplicate,
                downloadedCanonicalKeys = setOf(completed.downloadIdentity().canonicalKey),
                samplingEpochMs = 42L,
            )
        val notification = aggregateDownloadNotification(snapshot)

        val pageProjection = snapshot.projection
        val notificationProjection = notification.projection

        assertEquals(pageProjection.identitySet, notificationProjection.identitySet)
        assertEquals(pageProjection.activeCount, notificationProjection.activeCount)
        assertEquals(pageProjection.downloadedBytes, notificationProjection.downloadedBytes)
        assertEquals(pageProjection.totalBytes, notificationProjection.totalBytes)
        assertEquals(
            pageProjection.actionEligibleIdentities,
            notificationProjection.actionEligibleIdentities,
        )
        assertEquals(42L, pageProjection.samplingEpochMs)
        assertEquals(12, pageProjection.activeCount)
        assertEquals(
            11,
            pageProjection.actionEligibleIdentities.getValue(CanonicalDownloadAction.PAUSE).size,
        )
        assertEquals(
            1,
            pageProjection.actionEligibleIdentities.getValue(CanonicalDownloadAction.RESUME).size,
        )
        assertEquals(11, notification.actionSourceIds.getValue(CanonicalDownloadAction.PAUSE).size)
        assertEquals(1, notification.actionSourceIds.getValue(CanonicalDownloadAction.RESUME).size)
        assertEquals(12, notification.actionSourceIds.getValue(CanonicalDownloadAction.DELETE).size)
        assertEquals(
            12,
            pageProjection.actionEligibleIdentities.getValue(CanonicalDownloadAction.DELETE).size,
        )
    }

    @Test
    fun `single task notification uses snapshot bytes`() {
        val single =
            task(
                itemId = UUID(0L, 1_500L),
                sourceId = "unknown-total",
                status = DownloadTaskStatus.RUNNING,
                downloadedBytes = 25L,
                totalBytes = -1L,
            )
        val snapshot =
            DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                tasks = listOf(single),
                downloadedCanonicalKeys = emptySet(),
                samplingEpochMs = 43L,
            )

        val notification = aggregateDownloadNotification(snapshot)

        assertEquals(snapshot.projection, notification.projection)
        assertEquals(snapshot.projection.downloadedBytes, notification.task?.downloadedBytes)
        assertEquals(snapshot.projection.totalBytes, notification.task?.totalBytes)
    }

    private fun task(
        itemId: UUID,
        sourceId: String,
        status: DownloadTaskStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        updatedAt: Long = 0L,
        taskGeneration: Long = 1L,
    ): DownloadTask =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = sourceId,
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = updatedAt,
            taskGeneration = taskGeneration,
            serverId = "server-a",
            userId = "user-a",
        )
}
