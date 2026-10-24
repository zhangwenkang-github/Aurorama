package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMediaSidecarCodecTest {

    @Test
    fun `v2 sidecar 完整 provenance 可无损 round-trip`() {
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
            )

        val encoded = DownloadMediaCodec.encodeLine(record)

        assertTrue(encoded.startsWith("v2\t"))
        assertEquals(record, DownloadMediaCodec.decodeLine(encoded))
    }

    @Test
    fun `v2 尾部来源为空时仍可 round-trip 并保持未知`() {
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
    fun `legacy 五列记录可读但 provenance 为未知`() {
        val album = java.util.Base64.getEncoder().encodeToString("旧专辑".toByteArray())
        val artist = java.util.Base64.getEncoder().encodeToString("旧歌手".toByteArray())

        val decoded = DownloadMediaCodec.decodeLine("MUSIC\t$ITEM_ID\t$album\t$artist\t3")

        assertEquals("旧专辑", decoded?.albumName)
        assertEquals("旧歌手", decoded?.artist)
        assertEquals(3, decoded?.trackIndex)
        assertNull(decoded?.provenance())
    }

    @Test
    fun `坏行不可解析且缺媒体 source 的记录不生成 folding provenance`() {
        assertNull(DownloadMediaCodec.decodeLine("v2\tMUSIC\tbroken"))
        assertNull(DownloadMediaCodec.decodeLine("v2\tMUSIC\tbroken\t\t\t1\t\t\t\t")?.provenance())

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

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server\tA"
        const val SOURCE_ID = "source\nA"
    }
}
