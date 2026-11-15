package com.zhangwenkang.cinefin.utils

import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        updatedAt: Long = 0L,
        taskGeneration: Long = 0L,
        serverId: String? = null,
        userId: String? = null,
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
            updatedAt = updatedAt,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
            taskGeneration = taskGeneration,
            serverId = serverId,
            userId = userId,
        )

    @Test
    fun `canonical_transform_matches_real_page_and_notification`() {
        val completed =
            task(
                itemId = UUID(0L, 900L),
                sourceId = "completed",
                status = DownloadTaskStatus.RUNNING,
                serverId = "server-a",
                userId = "user-a",
            )
        val active =
            (1L..12L).map { index ->
                task(
                    itemId = UUID(0L, 1_000L + index),
                    sourceId = "episode-$index",
                    status = DownloadTaskStatus.RUNNING,
                    downloadedBytes = index * 100L,
                    totalBytes = index * 10_000L,
                    updatedAt = index,
                    taskGeneration = 1L,
                    serverId = "server-a",
                    userId = "user-a",
                )
            }
        val olderDuplicate =
            active
                .first()
                .copy(
                    downloadedBytes = 1L,
                    totalBytes = 2L,
                    updatedAt = 1L,
                    taskGeneration = 1L,
                )
        val newerDuplicate =
            active
                .first()
                .copy(
                    downloadedBytes = 99L,
                    totalBytes = 999L,
                    updatedAt = 99L,
                    taskGeneration = 2L,
                )
        val allTasks = listOf(completed) + active + olderDuplicate + newerDuplicate
        val completedKeys = setOf(completed.downloadIdentity().canonicalKey)

        val snapshot =
            DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                tasks = allTasks,
                downloadedCanonicalKeys = completedKeys,
                samplingEpochMs = 42L,
            )
        val notification = aggregateDownloadNotification(snapshot)

        assertEquals(12, snapshot.activeCount)
        assertEquals(snapshot.activeCount, notification.activeCount)
        assertEquals(snapshot.downloadedBytes, notification.task?.downloadedBytes)
        assertEquals(snapshot.totalBytes, notification.task?.totalBytes)
        assertEquals(snapshot.sortedIdentityKeys, notification.identityKeys)
        assertEquals(
            identityHash(snapshot.sortedIdentityKeys),
            identityHash(notification.identityKeys),
        )
        assertEquals(42L, notification.samplingEpochMs)
        assertEquals(
            snapshot.tasks.sumOf(CanonicalActiveTask::downloadedBytes),
            snapshot.downloadedBytes,
        )
        assertEquals(snapshot.tasks.sumOf(CanonicalActiveTask::totalBytes), snapshot.totalBytes)
        assertEquals(
            999L,
            snapshot.tasks
                .single { leaf -> leaf.identity == active.first().downloadIdentity() }
                .totalBytes,
        )
    }

    @Test
    fun `old epoch async completion cannot replace latest canonical snapshot`() {
        val owner = CanonicalActiveTaskSnapshotOwner(clock = { 1L })
        val olderEpoch = owner.beginSampling()
        val newerEpoch = owner.beginSampling()
        val newer =
            owner.publish(
                samplingEpochMs = newerEpoch,
                tasks =
                    listOf(
                        task(
                            sourceId = "newer",
                            status = DownloadTaskStatus.RUNNING,
                            totalBytes = 200L,
                        )
                    ),
                downloadedCanonicalKeys = emptySet(),
            )
        val stale =
            owner.publish(
                samplingEpochMs = olderEpoch,
                tasks =
                    listOf(
                        task(
                            sourceId = "stale",
                            status = DownloadTaskStatus.RUNNING,
                            totalBytes = 100L,
                        )
                    ),
                downloadedCanonicalKeys = emptySet(),
            )

        assertNotNull(newer)
        assertNull(stale)
        assertEquals(newer, owner.latest())
        assertEquals(200L, owner.latest().totalBytes)
    }

    @Test
    fun `same raw task set still publishes a new canonical epoch`() {
        val owner = CanonicalActiveTaskSnapshotOwner(clock = { 10L })
        val tasks =
            listOf(
                task(
                    sourceId = "same",
                    status = DownloadTaskStatus.PAUSED,
                    downloadedBytes = 40L,
                    totalBytes = 100L,
                )
            )
        val paused =
            owner.publish(
                samplingEpochMs = owner.beginSampling(),
                tasks = tasks,
                downloadedCanonicalKeys = emptySet(),
            )
        val resumed =
            owner.publish(
                samplingEpochMs = owner.beginSampling(),
                tasks = tasks,
                downloadedCanonicalKeys = emptySet(),
            )

        assertNotNull(paused)
        assertNotNull(resumed)
        assertEquals(paused?.downloadedBytes, resumed?.downloadedBytes)
        assertEquals(paused?.totalBytes, resumed?.totalBytes)
        assertTrue(requireNotNull(resumed).samplingEpochMs > requireNotNull(paused).samplingEpochMs)
    }

    @Test
    fun `活动唯一意图按 canonical identity 去重并剔除已完成落盘`() {
        val active = UUID.randomUUID()
        val completed = UUID.randomUUID()
        val completedTask =
            task(
                itemId = completed,
                sourceId = "completed",
                status = DownloadTaskStatus.PAUSED,
            )
        val tasks =
            listOf(
                task(itemId = active, sourceId = "active-a", status = DownloadTaskStatus.PENDING),
                task(itemId = active, sourceId = "active-b", status = DownloadTaskStatus.RUNNING),
                completedTask,
                task(sourceId = "failed", status = DownloadTaskStatus.FAILED),
            )

        val projection =
            DownloadControlPlaneRules.activeUniqueIntents(
                tasks = tasks,
                downloadedCanonicalKeys = setOf(completedTask.downloadIdentity().canonicalKey),
            )

        assertEquals(listOf("active-a", "active-b"), projection.map { it.sourceId })
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
    fun `persisted intent statuses produce queued resolving and source pending phases`() {
        val phases =
            listOf(
                    DownloadIntentPhase.QUEUED,
                    DownloadIntentPhase.RESOLVING,
                    DownloadIntentPhase.SOURCE_PENDING,
                )
                .map { intentPhase ->
                    DownloadControlPlaneRules.phase(
                        status = DownloadTaskStatus.PENDING,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        failureReason = null,
                        nextRetryAt = 0L,
                        nowMs = 0L,
                        intentPhase = intentPhase,
                    )
                }

        assertEquals(
            listOf(
                DownloadTaskPhase.QUEUED,
                DownloadTaskPhase.RESOLVING,
                DownloadTaskPhase.SOURCE_PENDING,
            ),
            phases,
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
                    serverId = "server-a",
                    userId = "user-a",
                )
            } +
                DownloadMusicIntentRequest(
                    itemId = UUID(0L, 50L),
                    name = "重复",
                    albumName = "专辑",
                    artist = "歌手",
                    trackIndex = 50,
                    serverId = "server-a",
                    userId = "user-a",
                )

        val normalized = DownloadControlPlaneRules.normalizeMusicBatch(requests)

        assertEquals(100, normalized.size)
        assertEquals("曲目 1", normalized.first().name)
        assertEquals("曲目 100", normalized.last().name)
    }

    @Test
    fun `placement 不参与意图 identity 与去重`() {
        val itemId = UUID(0L, 501L)
        val first =
            DownloadMusicIntentRequest(
                itemId = itemId,
                name = "曲目",
                albumName = "专辑",
                artist = "歌手",
                trackIndex = 1,
                serverId = "server-a",
                userId = "user-a",
                sourceId = "source-a",
                placement = DownloadPlacement("batch-a", ContainerPolicy.ALBUM_BATCH, "work-a"),
            )
        val second =
            first.copy(placement = DownloadPlacement("batch-b", ContainerPolicy.FLAT, "work-b"))

        assertEquals(first.canonicalIdentity, second.canonicalIdentity)
        assertEquals(first.acceptanceKey, second.acceptanceKey)
        assertEquals(
            listOf(first),
            DownloadControlPlaneRules.normalizeMusicBatch(listOf(first, second)),
        )
        val legacy =
            DownloadMusicIntentRequest(
                itemId = itemId,
                name = "曲目",
                albumName = "专辑",
                artist = "歌手",
                trackIndex = 1,
                serverId = "server-a",
                userId = "user-a",
                sourceId = "source-a",
            )
        assertEquals(DownloadPlacement.LEGACY, legacy.placement)
    }

    @Test
    fun `失败全集按完整 canonical identity 去重`() {
        val itemId = UUID.randomUUID()
        val failedTasks =
            listOf(
                task(itemId = itemId, sourceId = "a", status = DownloadTaskStatus.FAILED),
                task(itemId = itemId, sourceId = "b", status = DownloadTaskStatus.FAILED),
                task(sourceId = "active", status = DownloadTaskStatus.RUNNING),
            )

        assertEquals(
            listOf("a", "b"),
            DownloadControlPlaneRules.retryableFailureIntents(failedTasks).map { it.sourceId },
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

    private fun identityHash(keys: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(keys.joinToString(separator = "\n").toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
