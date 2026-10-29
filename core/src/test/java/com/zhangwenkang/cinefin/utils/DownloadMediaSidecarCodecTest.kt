package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMediaSidecarCodecTest {

    @Test
    fun `v3 sidecar 完整 provenance 与 placement 可无损 round-trip`() {
        val record =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.MUSIC,
                albumName = "专辑\tA",
                artist = "歌手\nB",
                trackIndex = 7,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = SOURCE_ID,
                displayName = "标题 / 曲目",
                placement =
                    DownloadPlacement(
                        batchId = "专辑\t批次",
                        policy = ContainerPolicy.ALBUM_BATCH,
                        workKey = "music\nwork",
                    ),
            )

        val encoded = DownloadMediaCodec.encodeLine(record)

        assertTrue(encoded.startsWith("v3\t"))
        assertEquals(record, DownloadMediaCodec.decodeLine(encoded))
    }

    @Test
    fun `v3 尾部来源为空时仍可 round-trip 并保持未知`() {
        val unknown =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.VIDEO,
            )

        val decoded = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(unknown))

        assertEquals(unknown, decoded)
        assertNull(decoded?.provenance())
    }

    @Test
    fun `v2 记录兼容读取为 legacy placement`() {
        val encoded =
            listOf(
                    "v2",
                    "MUSIC",
                    url64(ITEM_ID.toString()),
                    url64("旧专辑"),
                    url64("旧歌手"),
                    "3",
                    url64(SERVER_ID),
                    url64(USER_ID.toString()),
                    url64(SOURCE_ID),
                    url64("旧标题"),
                )
                .joinToString("\t")

        val decoded = DownloadMediaCodec.decodeLine(encoded)

        assertEquals("旧专辑", decoded?.albumName)
        assertEquals("旧歌手", decoded?.artist)
        assertEquals(DownloadPlacement.LEGACY, decoded?.placement)
    }

    @Test
    fun `v1 五列记录兼容读取为 legacy placement 且 provenance 未知`() {
        val album = java.util.Base64.getEncoder().encodeToString("旧专辑".toByteArray())
        val artist = java.util.Base64.getEncoder().encodeToString("旧歌手".toByteArray())

        val decoded = DownloadMediaCodec.decodeLine("MUSIC\t$ITEM_ID\t$album\t$artist\t3")

        assertEquals("旧专辑", decoded?.albumName)
        assertEquals("旧歌手", decoded?.artist)
        assertEquals(3, decoded?.trackIndex)
        assertEquals(DownloadPlacement.LEGACY, decoded?.placement)
        assertNull(decoded?.provenance())
    }

    @Test
    fun `坏行不可解析且缺媒体 source 的记录不生成 folding provenance`() {
        assertNull(DownloadMediaCodec.decodeLine("v2\tMUSIC\tbroken"))
        assertNull(DownloadMediaCodec.decodeLine("v2\tMUSIC\tbroken\t\t\t1\t\t\t\t")?.provenance())
        assertNull(DownloadMediaCodec.decodeLine("v3\tMUSIC\tbroken"))

        val invalidPolicy =
            listOf(
                    "v3",
                    "MUSIC",
                    url64(ITEM_ID.toString()),
                    "",
                    "",
                    "0",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "UNKNOWN",
                    "",
                )
                .joinToString("\t")
        assertNull(DownloadMediaCodec.decodeLine(invalidPolicy))

        val missingSource =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.VIDEO,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = null,
            )
        assertNull(missingSource.provenance())
    }

    @Test
    fun `同 server user item 不同 media source 保持不同记录与身份`() {
        val first =
            DownloadMediaRecord(
                itemId = ITEM_ID.toString(),
                kind = DownloadMediaKind.VIDEO,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                sourceId = "source-a",
            )
        val second = first.copy(sourceId = "source-b")

        val decodedFirst = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(first))
        val decodedSecond = DownloadMediaCodec.decodeLine(DownloadMediaCodec.encodeLine(second))

        assertEquals(first, decodedFirst)
        assertEquals(second, decodedSecond)
        assertNotEquals(decodedFirst?.provenance(), decodedSecond?.provenance())
    }

    private fun url64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server\tA"
        const val SOURCE_ID = "source\nA"
    }
}
