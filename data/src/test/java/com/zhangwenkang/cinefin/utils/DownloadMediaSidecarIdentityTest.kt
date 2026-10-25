package com.zhangwenkang.cinefin.utils

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMediaSidecarIdentityTest {

    private val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `真实 sidecar 文件保留同 UUID 的不同 server user source 记录`() {
        val directory = Files.createTempDirectory("download-sidecar-identity").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(directory, "download_media.tsv"))
            val records =
                listOf(
                    record("server-a", "user-a", "source-a"),
                    record("server-b", "user-a", "source-a"),
                    record("server-a", "user-b", "source-a"),
                    record("server-a", "user-a", "source-b"),
                )
            records.forEach(sidecar::put)

            assertEquals(4, sidecar.allRecords().size)
            assertEquals(4, sidecar.records().size)
            records.forEach { record ->
                assertEquals(
                    record,
                    sidecar.records().getValue(record.canonicalIdentity().canonicalKey),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun record(serverId: String, userId: String, sourceId: String) =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = DownloadMediaKind.MUSIC,
            serverId = serverId,
            userId = userId,
            sourceId = sourceId,
        )
}
