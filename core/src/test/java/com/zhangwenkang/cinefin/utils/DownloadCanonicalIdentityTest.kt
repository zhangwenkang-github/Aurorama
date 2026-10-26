package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCanonicalIdentityTest {

    private val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `control plane 对同 UUID 不同 provenance 的任务不折叠`() {
        val tasks =
            listOf(
                task("source-a", "server-a", "user-a"),
                task("source-b", "server-b", "user-a"),
                task("source-a", "server-a", "user-b"),
            )

        assertEquals(
            3,
            DownloadControlPlaneRules.activeUniqueIntents(
                    tasks = tasks,
                    downloadedCanonicalKeys = emptySet(),
                )
                .size,
        )
    }

    @Test
    fun `source 也进入 canonical identity 而不是只按 item 去重`() {
        val first = task("source-a", "server-a", "user-a")
        val second = first.copy(sourceId = "source-b")

        assertNotEquals(first.downloadIdentity(), second.downloadIdentity())
    }

    @Test
    fun `未知来源不得与另一未知或完整来源互认`() {
        val unknownA =
            DownloadCanonicalIdentity.of(
                mediaKind = DownloadMediaKind.MUSIC,
                itemId = itemId.toString(),
                serverId = null,
                userId = null,
                sourceId = null,
            )
        val unknownB = unknownA.copy()
        val complete =
            DownloadCanonicalIdentity.of(
                mediaKind = DownloadMediaKind.MUSIC,
                itemId = itemId.toString(),
                serverId = "server-a",
                userId = "user-a",
                sourceId = null,
            )

        assertFalse(unknownA.matchesProvenance(unknownB))
        assertFalse(unknownA.matchesProvenance(complete))
        assertFalse(complete.matchesProvenance(unknownA))
        assertNull(unknownA.foldKey)
        assertTrue(unknownA.matchesLegacyItemId(DownloadMediaKind.MUSIC, itemId.toString()))
    }

    private fun task(
        sourceId: String,
        serverId: String,
        userId: String,
    ) =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = "同 UUID",
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = DownloadTaskStatus.PENDING,
            failureReason = null,
            downloadedBytes = 1L,
            totalBytes = 2L,
            updatedAt = 1L,
            mediaKind = DownloadMediaKind.MUSIC,
            serverId = serverId,
            userId = userId,
        )
}
