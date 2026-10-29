package com.zhangwenkang.cinefin.utils

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlacementIntentTest {

    @Test
    fun `public music intent path preserves placement across restart and source refresh`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("44444444-4444-4444-8444-444444444444")
            val sourceId = "placement-source"
            val placement =
                DownloadPlacement(
                    batchId = "album-batch-42",
                    policy = ContainerPolicy.ALBUM_BATCH,
                    workKey = "music|server-a|user-a|album-42",
                )
            try {
                fixture.prepareMusicOrigin(itemId, "placement round trip", sourceId)

                val receipt =
                    fixture.downloader.receiveMusicBatch(
                        listOf(
                            fixture
                                .completeMusicRequest(itemId, "placement round trip")
                                .copy(placement = placement)
                        )
                    )

                assertEquals(1, receipt.acceptedRequests.size)
                assertEquals(placement, receipt.acceptedRequests.single().placement)
                fixture.downloader.recoverOnStartup()
                assertTrue(
                    "意图必须解析为 source 才能验证 source task 映射：${fixture.logs}",
                    fixture.dao.awaitInsertedSourceCount(1),
                )
                val persisted =
                    fixture.downloader.mediaSidecar().allRecords().requireComplete().single {
                        it.itemId == itemId.toString() && it.sourceId == sourceId
                    }
                assertEquals(placement, persisted.placement)

                val restarted = fixture.newDownloader()
                val task =
                    restarted.refreshDownloadTasks().single {
                        it.itemId == itemId && it.sourceId == sourceId
                    }

                assertEquals(ContainerPolicy.ALBUM_BATCH, task.placement.policy)
                assertEquals("album-batch-42", task.placement.batchId)
                assertEquals(placement.workKey, task.placement.workKey)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `same work key leaves stay independent after intent refresh`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val firstItemId = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val secondItemId = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val sharedPlacement =
            DownloadPlacement(
                batchId = "shared-batch",
                policy = ContainerPolicy.ALBUM_BATCH,
                workKey = "music|server-a|user-a|shared-work",
            )
        try {
            fixture.prepareMusicOrigin(firstItemId, "leaf one", "source-leaf-one")
            fixture.prepareMusicOrigin(secondItemId, "leaf two", "source-leaf-two")
            val firstRequest =
                fixture
                    .completeMusicRequest(firstItemId, "leaf one")
                    .copy(placement = sharedPlacement)
            val secondRequest =
                fixture
                    .completeMusicRequest(secondItemId, "leaf two")
                    .copy(placement = sharedPlacement)

            val receipt = fixture.downloader.receiveMusicBatch(listOf(firstRequest, secondRequest))

            assertEquals(2, receipt.acceptedIds.size)
            fixture.downloader.recoverOnStartup()
            assertTrue(
                "同一 workKey 的两个意图都必须解析为独立 source：${fixture.logs}",
                fixture.dao.awaitInsertedSourceCount(2),
            )
            val tasks =
                fixture.newDownloader().refreshDownloadTasks().filter {
                    it.itemId == firstItemId || it.itemId == secondItemId
                }

            assertEquals("同一 workKey 的两个叶子不得合并", 2, tasks.size)
            assertEquals(
                "每个叶子必须保留独立 canonical identity",
                2,
                tasks.map { it.downloadIdentity().canonicalKey }.toSet().size,
            )
            assertEquals(setOf(sharedPlacement), tasks.map { it.placement }.toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `v1 v2 and empty v3 placement read back as legacy`() {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val v1ItemId = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val v2ItemId = UUID.fromString("88888888-8888-4888-8888-888888888888")
        val emptyItemId = UUID.fromString("99999999-9999-4999-8999-999999999999")
        try {
            val v1 =
                listOf(
                        "MUSIC",
                        v1ItemId.toString(),
                        Base64.getEncoder().encodeToString("旧专辑".toByteArray()),
                        Base64.getEncoder().encodeToString("旧歌手".toByteArray()),
                        "1",
                    )
                    .joinToString("\t")
            val v2 =
                listOf(
                        "v2",
                        "MUSIC",
                        url64(v2ItemId.toString()),
                        url64("专辑"),
                        url64("歌手"),
                        "2",
                        url64(SERVER_ID),
                        url64(USER_ID.toString()),
                        url64("source-v2"),
                        url64("v2"),
                    )
                    .joinToString("\t")
            val emptyPlacement =
                DownloadMediaCodec.encodeLine(
                    DownloadMediaRecord(
                        itemId = emptyItemId.toString(),
                        kind = DownloadMediaKind.MUSIC,
                        placement = DownloadPlacement.LEGACY,
                    )
                )
            fixture.sidecarFile().writeText(listOf(v1, v2, emptyPlacement).joinToString("\n"))

            val records =
                fixture.downloader.mediaSidecar().allRecords().requireComplete().associateBy {
                    it.itemId
                }

            assertEquals(3, records.size)
            assertEquals(
                setOf(DownloadPlacement.LEGACY),
                records.values.map { it.placement }.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `v3 sidecar writer persists placement columns`() {
        val record =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.VIDEO,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = SOURCE_ID,
                placement =
                    DownloadPlacement(
                        batchId = "batch-a",
                        policy = ContainerPolicy.SHOW,
                        workKey = WORK_KEY,
                    ),
            )

        val columns = DownloadMediaCodec.encodeLine(record).split("\t")

        assertEquals("v3", columns.first())
        assertEquals(13, columns.size)
        assertEquals(record, DownloadMediaCodec.decodeLine(columns.joinToString("\t")))
    }

    @Test
    fun `same work key keeps independent leaf identities`() {
        val first = record(ITEM_ID, SOURCE_ID, WORK_KEY)
        val second = record(SECOND_ITEM_ID, SECOND_SOURCE_ID, WORK_KEY)

        val decodedFirst = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(first))
        val decodedSecond = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(second))

        assertEquals(first.placement, decodedFirst?.placement)
        assertEquals(second.placement, decodedSecond?.placement)
        assertNotEquals(decodedFirst?.canonicalIdentity(), decodedSecond?.canonicalIdentity())
    }

    @Test
    fun `all container policies survive sidecar round trip`() {
        ContainerPolicy.entries.forEach { policy ->
            val record =
                record(ITEM_ID, SOURCE_ID, WORK_KEY)
                    .copy(
                        placement = DownloadPlacement("batch-$policy", policy, "$WORK_KEY-$policy")
                    )

            val decoded = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(record))

            assertEquals(record.placement, decoded?.placement)
        }
    }

    private fun record(
        itemId: UUID,
        sourceId: String,
        workKey: String,
    ) =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = DownloadMediaKind.VIDEO,
            serverId = SERVER_ID,
            userId = USER_ID.toString(),
            sourceId = sourceId,
            placement = DownloadPlacement("batch-a", ContainerPolicy.VIDEO_WORK, workKey),
        )

    private fun url64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SECOND_ITEM_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server-a"
        const val SOURCE_ID = "source-a"
        const val SECOND_SOURCE_ID = "source-b"
        const val WORK_KEY = "video-work-a"
    }
}
