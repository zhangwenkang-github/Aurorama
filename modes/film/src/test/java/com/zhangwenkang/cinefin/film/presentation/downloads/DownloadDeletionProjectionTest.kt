package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDeletionProjectionTest {

    @Test
    fun `show and season consume descendant delete pending before io completes`() {
        val showId = UUID.randomUUID()
        val seasonId = UUID.randomUUID()
        val first = task("第一集", DownloadTaskStatus.PAUSED, showId, seasonId)
        val second = task("第二集", DownloadTaskStatus.PAUSED, showId, seasonId)
        val entries = listOf(entry(first), entry(second))
        val container = DownloadHierarchyBuilder.build(entries).single()
        val keys = entries.mapTo(linkedSetOf()) { it.key }
        val state =
            DownloadManagerState(
                activeContainers = listOf(container),
                pendingActions = keys.associateWith { DownloadPendingAction.DELETE },
                deletionPendingBatches = keys.associateWith { setOf(1L) },
            )

        val showProjection = state.deletionProjection(container)
        val season = container.children.filterIsInstance<DownloadHierarchySubContainer>().single()
        val seasonProjection = state.deletionProjection(season)

        assertTrue(showProjection.pending)
        assertTrue(showProjection.deleting)
        assertEquals(0, showProjection.failedCount)
        assertTrue(seasonProjection.pending)
        assertTrue(seasonProjection.deleting)
        assertEquals(0, seasonProjection.failedCount)
    }

    @Test
    fun `persisted deleting status projects deleting without pending side map`() {
        val showId = UUID.randomUUID()
        val seasonId = UUID.randomUUID()
        val deleting = task("删除中", DownloadTaskStatus.DELETING, showId, seasonId)
        val container = DownloadHierarchyBuilder.build(listOf(entry(deleting))).single()
        val state = DownloadManagerState(activeContainers = listOf(container))

        val projection = state.deletionProjection(container)

        assertEquals(DownloadHierarchyStatus.PENDING, container.status)
        assertTrue(projection.deleting)
        assertFalse(projection.pending)
        assertEquals(0, projection.failedCount)
    }

    @Test
    fun `partial failure exposes failed leaf count and converged summary`() {
        val showId = UUID.randomUUID()
        val seasonId = UUID.randomUUID()
        val failed = task("失败集", DownloadTaskStatus.PAUSED, showId, seasonId)
        val failedEntry = entry(failed)
        val container = DownloadHierarchyBuilder.build(listOf(failedEntry)).single()
        val state =
            DownloadManagerState(
                activeContainers = listOf(container),
                actionFailure = DownloadActionFailure.DELETE,
                deletionRecoveryEntries =
                    listOf(
                        DownloadDeletionRecoveryEntry(
                            entryKey = failedEntry.key,
                            canonicalIdentity = failedEntry.canonicalIdentity,
                        )
                    ),
                deletionSummary =
                    DownloadDeletionSummary(
                        containerKey = container.key,
                        totalCount = 2,
                        convergedCount = 1,
                        failedCount = 1,
                        supersededCount = 0,
                    ),
            )

        val projection = state.deletionProjection(container)

        assertFalse(projection.pending)
        assertFalse(projection.deleting)
        assertEquals(1, projection.failedCount)
        assertEquals(DownloadDeletionSummary(container.key, 2, 1, 1, 0), state.deletionSummary)
    }

    @Test
    fun `all success batch leaves no stale convergence summary`() {
        val summary =
            DownloadDeletionResult(succeededKeys = setOf("item:first", "item:second"))
                .toSummary(containerKey = "container:show")

        assertNull(summary)
    }

    private fun entry(task: DownloadTask) =
        DownloadHierarchyEntry(
            itemId = task.itemId,
            name = task.name,
            mediaKind = task.mediaKind,
            status = task.status,
            task = task,
            totalBytes = task.totalBytes,
            seriesId = task.seriesId,
            seasonId = task.seasonId,
            seriesName = task.seriesName,
            seasonName = task.seasonName,
            episodeIndex = task.episodeIndex,
        )

    private fun task(
        name: String,
        status: DownloadTaskStatus,
        seriesId: UUID,
        seasonId: UUID,
    ) =
        DownloadTask(
            itemId = UUID.randomUUID(),
            sourceId = "source-$name",
            name = name,
            path = "/tmp/$name.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = 0L,
            mediaKind = DownloadMediaKind.VIDEO,
            seriesId = seriesId,
            seasonId = seasonId,
            seriesName = "测试剧",
            seasonName = "第 1 季",
            episodeIndex = 1,
            seasonIndex = 1,
        )
}
