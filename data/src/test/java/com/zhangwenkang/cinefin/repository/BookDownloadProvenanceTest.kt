package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import com.zhangwenkang.cinefin.utils.DownloadProvenance
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDownloadProvenanceTest {

    @Test
    fun `书籍成功落盘后写入完整 BOOK provenance`() {
        val root = createTempDirectory(prefix = "book-provenance").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(root, "download_media.tsv"))
            val origin =
                BookDownloadOrigin(
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    itemId = ITEM_ID,
                    displayName = "书 A",
                )

            recordBookDownloadProvenance(sidecar = sidecar, origin = origin)

            val record = sidecar.allRecords().single()
            assertEquals(DownloadMediaKind.BOOK, record.kind)
            assertEquals(ITEM_ID.toString(), record.itemId)
            assertEquals(SERVER_ID, record.serverId)
            assertEquals(USER_ID.toString(), record.userId)
            assertNull(record.sourceId)
            assertEquals("书 A", record.displayName)
            assertEquals(
                DownloadProvenance(
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    itemId = ITEM_ID,
                    mediaSourceId = null,
                ),
                record.provenance(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没有完整下载来源时书籍 provenance 不写入`() {
        val root = createTempDirectory(prefix = "book-provenance-missing").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(root, "download_media.tsv"))
            val origin =
                BookDownloadOrigin(
                    serverId = null,
                    userId = USER_ID,
                    itemId = ITEM_ID,
                    displayName = "书 A",
                )

            recordBookDownloadProvenance(sidecar = sidecar, origin = origin)

            assertTrue(sidecar.allRecords().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `下载失败清理旧 BOOK provenance 且不留下完成来源`() {
        val root = createTempDirectory(prefix = "book-provenance-failed").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(root, "download_media.tsv"))
            recordBookDownloadProvenance(
                sidecar = sidecar,
                origin =
                    BookDownloadOrigin(
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        itemId = ITEM_ID,
                        displayName = "书 A",
                    ),
            )

            clearBookDownloadProvenance(sidecar, ITEM_ID)

            assertTrue(sidecar.allRecords().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server-a"
    }
}
