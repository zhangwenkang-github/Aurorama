package com.zhangwenkang.cinefin.database

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMusicIntentPersistenceContractTest {

    @Test
    fun `approved v15 单 itemId DAO 与 sidecar 完整来源契约不混淆`() {
        val directory = Files.createTempDirectory("music-intent-contract").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(directory, "download_media.tsv"))
            val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
            DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE downloadIntents (
                            itemId TEXT NOT NULL,
                            name TEXT NOT NULL,
                            albumName TEXT,
                            artist TEXT,
                            trackIndex INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            failureReason TEXT,
                            failureDetail TEXT,
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0,
                            taskGeneration INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(itemId)
                        )
                        """
                            .trimIndent()
                    )
                    statement.execute(
                        "INSERT OR IGNORE INTO downloadIntents(itemId, name, trackIndex, status) VALUES('$itemId', 'A', 1, 'RECEIVED')"
                    )
                    statement.execute(
                        "INSERT OR IGNORE INTO downloadIntents(itemId, name, trackIndex, status) VALUES('$itemId', 'B', 1, 'RECEIVED')"
                    )
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM downloadIntents").use { result ->
                        result.next()
                        assertEquals(
                            "downloadIntents 仍是 approved v15 的 itemId 单主键",
                            1,
                            result.getInt(1),
                        )
                    }
                }
            }

            val accepted =
                listOf(
                    pendingRecord(itemId, "server-a", "user-a"),
                    pendingRecord(itemId, "server-b", "user-b"),
                )
            assertTrue(sidecar.putAll(accepted))

            assertEquals(
                setOf("server-a" to "user-a", "server-b" to "user-b"),
                sidecar.allRecords().mapTo(mutableSetOf()) { it.serverId to it.userId },
            )
            assertFalse(
                File("data/schemas/com.zhangwenkang.cinefin.database.ServerDatabase/16.json")
                    .exists()
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pendingRecord(
        itemId: UUID,
        serverId: String,
        userId: String,
    ) =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = DownloadMediaKind.MUSIC,
            serverId = serverId,
            userId = userId,
        )
}
