package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.CanonicalDownloadAction
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.downloadIdentity
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDownloadProjectionConsumerTest {

    @Test
    fun `downloads hierarchy consumes the canonical snapshot projection`() {
        val completed =
            task(
                itemId = UUID(0L, 1_000L),
                sourceId = "completed",
                episodeIndex = 99,
                totalBytes = 99_000L,
            )
        val active =
            (1L..12L).map { index ->
                task(
                    itemId = UUID(0L, 2_000L + index),
                    sourceId = "episode-$index",
                    episodeIndex = index.toInt(),
                    downloadedBytes = index * 100L,
                    totalBytes = index * 10_000L,
                    updatedAt = index,
                )
            }
        val snapshot =
            DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                tasks = listOf(completed) + active + active.first().copy(updatedAt = 0L),
                downloadedCanonicalKeys = setOf(completed.downloadIdentity().canonicalKey),
                samplingEpochMs = 44L,
            )
        val entries =
            snapshot.tasks.map { leaf ->
                DownloadHierarchyEntry(
                    itemId = leaf.task.itemId,
                    name = leaf.task.name,
                    mediaKind = DownloadMediaKind.VIDEO,
                    status = leaf.task.status,
                    sourceId = leaf.task.sourceId,
                    serverId = leaf.task.serverId,
                    userId = leaf.task.userId,
                    task = leaf.task,
                    sizeBytes = leaf.totalBytes,
                    downloadedBytes = leaf.downloadedBytes,
                    totalBytes = leaf.totalBytes,
                    updatedAt = leaf.task.updatedAt,
                    seriesId = SERIES_ID,
                    seasonId = SEASON_ID,
                    episodeIndex = leaf.task.episodeIndex,
                    canonicalActions = leaf.actionCapabilities,
                )
            }

        val container = DownloadHierarchyBuilder.build(entries).single()
        val pageActions =
            container
                .taskActionSpecs()
                .mapNotNull { spec ->
                    when (spec.action) {
                        DownloadContainerTaskAction.PAUSE -> CanonicalDownloadAction.PAUSE
                        DownloadContainerTaskAction.RESUME -> CanonicalDownloadAction.RESUME
                        DownloadContainerTaskAction.RETRY -> null
                    }
                }
                .toSet() +
                if (container.canDelete) setOf(CanonicalDownloadAction.DELETE) else emptySet()

        assertEquals(
            snapshot.projection.identitySet,
            container.allEntries().map { it.canonicalIdentity.canonicalKey }.toSet(),
        )
        assertEquals(snapshot.projection.activeCount, container.allEntries().size)
        assertEquals(snapshot.projection.downloadedBytes, container.downloadedBytes)
        assertEquals(snapshot.projection.totalBytes, container.totalBytes)
        assertEquals(
            snapshot.projection.actionEligibleIdentities.keys,
            pageActions,
        )
        assertTrue(container.canDelete)
    }

    private fun task(
        itemId: UUID,
        sourceId: String,
        episodeIndex: Int,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        updatedAt: Long = 0L,
    ): DownloadTask =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = sourceId,
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = DownloadTaskStatus.RUNNING,
            failureReason = null,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = updatedAt,
            taskGeneration = 1L,
            seriesId = SERIES_ID,
            seasonId = SEASON_ID,
            episodeIndex = episodeIndex,
        )

    private companion object {
        val SERIES_ID: UUID = UUID(0L, 100L)
        val SEASON_ID: UUID = UUID(0L, 200L)
    }
}
