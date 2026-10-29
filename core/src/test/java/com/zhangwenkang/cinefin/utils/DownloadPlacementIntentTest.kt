package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadPlacementIntentTest {

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
