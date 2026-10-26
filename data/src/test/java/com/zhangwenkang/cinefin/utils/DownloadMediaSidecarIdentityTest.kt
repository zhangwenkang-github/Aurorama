package com.zhangwenkang.cinefin.utils

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

            assertEquals(4, sidecar.allRecords().requireComplete().size)
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

    @Test
    fun `真实 sidecar 文件不会用未知身份折叠同 UUID 的两条待处理记录`() {
        val directory = Files.createTempDirectory("download-sidecar-unknown").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(directory, "download_media.tsv"))
            sidecar.put(
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    albumName = "未知专辑 A",
                )
            )
            sidecar.put(
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    albumName = "未知专辑 B",
                )
            )

            assertEquals(
                listOf("未知专辑 A", "未知专辑 B"),
                sidecar.allRecords().requireComplete().map { it.albumName },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy 未知占位在解析后原位替换而不是留下重复待处理行`() {
        val directory = Files.createTempDirectory("download-sidecar-legacy").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(directory, "download_media.tsv"))
            val pending =
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    displayName = "legacy",
                )
            sidecar.put(pending)
            sidecar.put(
                pending.copy(
                    sourceId = "source-a",
                    serverId = null,
                    userId = null,
                )
            )

            assertEquals(
                listOf(
                    DownloadMediaRecord(
                        itemId = itemId.toString(),
                        kind = DownloadMediaKind.MUSIC,
                        sourceId = "source-a",
                        displayName = "legacy",
                    )
                ),
                sidecar.allRecords().requireComplete(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `完整 pending 只替换单段相符的 partial 并保留冲突来源`() {
        val directory = Files.createTempDirectory("download-sidecar-partial-retry").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(directory, "download_media.tsv"))
            val matchingPartial =
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = "server-a",
                    displayName = "matching",
                )
            val conflictingPartial =
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = "server-b",
                    displayName = "conflicting",
                )
            val complete =
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = "server-a",
                    userId = "user-a",
                    displayName = "complete",
                )
            assertTrue(sidecar.putAll(listOf(matchingPartial, conflictingPartial)))
            assertTrue(sidecar.put(complete))

            assertEquals(
                listOf(conflictingPartial, complete),
                sidecar.allRecords().requireComplete(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sidecar 读取结果区分缺失完整损坏与不可读且拒绝覆盖损坏文件`() {
        val directory = Files.createTempDirectory("download-sidecar-read-result").toFile()
        try {
            val file = File(directory, "download_media.tsv")
            val sidecar = DownloadMediaSidecar.forFile(file)
            assertTrue(sidecar.allRecords() is DownloadMediaReadResult.Missing)

            assertTrue(sidecar.put(record("server-a", "user-a", "source-a")))
            assertTrue(sidecar.allRecords() is DownloadMediaReadResult.Complete)

            val corrupted = file.readText() + "\nbroken-row"
            file.writeText(corrupted)
            val corrupt = sidecar.allRecords()
            assertTrue(corrupt is DownloadMediaReadResult.Corrupt)
            assertEquals(listOf(2), (corrupt as DownloadMediaReadResult.Corrupt).invalidLineNumbers)
            assertFalse(
                "损坏快照不得被下一次写入静默覆盖",
                sidecar.put(record("server-b", "user-b", "source-b")),
            )
            assertEquals(corrupted, file.readText())

            assertTrue(file.delete())
            assertTrue(file.mkdirs())
            assertTrue(sidecar.allRecords() is DownloadMediaReadResult.Unreadable)
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
