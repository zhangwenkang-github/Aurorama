package com.zhangwenkang.cinefin.database

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDatabaseIntentDeletionSqlTest {

    @Test
    fun `完整重下只接管 RECEIVED 或 legacy FAILED 并推进 generation`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createDownloadIntentsTable()
            connection.insertIntent("received", status = "RECEIVED", generation = 3L)
            connection.insertIntent(
                "legacy",
                status = "FAILED",
                generation = 6L,
                failureDetail = "legacy",
            )
            connection.insertIntent(
                "ordinary",
                status = "FAILED",
                generation = 8L,
                failureDetail = "ordinary",
            )
            connection.insertIntent(
                "cancelled",
                status = "FAILED",
                generation = 9L,
                failureReason = "CANCELLED",
            )

            assertEquals(1, connection.supersedeForCompleteRequest("received", "legacy"))
            assertEquals(1, connection.supersedeForCompleteRequest("legacy", "legacy"))
            assertEquals(0, connection.supersedeForCompleteRequest("ordinary", "legacy"))
            assertEquals(0, connection.supersedeForCompleteRequest("cancelled", "legacy"))
            assertEquals(
                IntentState(
                    "RECEIVED",
                    4L,
                    name = "retry",
                    albumName = "Retry",
                    artist = "Provenance",
                    trackIndex = 1,
                ),
                connection.intentState("received"),
            )
            assertEquals(
                IntentState(
                    "RECEIVED",
                    7L,
                    name = "retry",
                    albumName = "Retry",
                    artist = "Provenance",
                    trackIndex = 1,
                ),
                connection.intentState("legacy"),
            )
            assertEquals(
                IntentState("FAILED", 8L, failureDetail = "ordinary"),
                connection.intentState("ordinary"),
            )
            assertEquals(
                IntentState(
                    status = "FAILED",
                    generation = 9L,
                    failureReason = "CANCELLED",
                ),
                connection.intentState("cancelled"),
            )
        }
    }

    @Test
    fun `FAILED intent 可按 expected generation 进入删除且旧代次被拒绝`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createDownloadIntentsTable()
            connection.insertIntent("failed", status = "FAILED", generation = 6L)
            connection.insertIntent("received", status = "RECEIVED", generation = 3L)

            assertEquals(0, connection.markIntentDeletion("failed", expectedGeneration = 5L))
            assertEquals(1, connection.markIntentDeletion("failed", expectedGeneration = 6L))
            assertEquals(0, connection.markIntentDeletion("received", expectedGeneration = 2L))
            assertEquals(1, connection.markIntentDeletion("received", expectedGeneration = 3L))
            assertEquals(
                IntentState("FAILED", 7L, failureReason = "CANCELLED"),
                connection.intentState("failed"),
            )
            assertEquals(
                IntentState("FAILED", 4L, failureReason = "CANCELLED"),
                connection.intentState("received"),
            )
        }
    }

    private data class IntentState(
        val status: String,
        val generation: Long,
        val name: String = "",
        val albumName: String? = null,
        val artist: String? = null,
        val trackIndex: Int = 0,
        val failureReason: String? = null,
        val failureDetail: String? = null,
    )

    private fun Connection.createDownloadIntentsTable() {
        createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE downloadIntents (
                    itemId TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    albumName TEXT,
                    artist TEXT,
                    trackIndex INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    failureReason TEXT,
                    failureDetail TEXT,
                    taskGeneration INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """
            )
        }
    }

    private fun Connection.insertIntent(
        itemId: String,
        status: String,
        generation: Long,
        failureReason: String? = null,
        failureDetail: String? = null,
    ) {
        prepareStatement(
                """
                INSERT INTO downloadIntents(
                    itemId, status, failureReason, failureDetail, taskGeneration
                )
                VALUES (?, ?, ?, ?, ?)
                """
            )
            .use { statement ->
                statement.setString(1, itemId)
                statement.setString(2, status)
                statement.setString(3, failureReason)
                statement.setString(4, failureDetail)
                statement.setLong(5, generation)
                statement.executeUpdate()
            }
    }

    private fun Connection.supersedeForCompleteRequest(
        itemId: String,
        legacyFailureDetail: String,
    ): Int =
        prepareStatement(
                bindNamedParameters(
                    ServerDatabaseDao.SUPERSEDE_DOWNLOAD_INTENT_FOR_COMPLETE_REQUEST_SQL,
                    mapOf(
                        "name" to "retry",
                        "albumName" to "Retry",
                        "artist" to "Provenance",
                        "trackIndex" to 1,
                        "updatedAt" to 100L,
                        "itemId" to itemId,
                        "legacyFailureDetail" to legacyFailureDetail,
                    ),
                )
            )
            .use { statement -> statement.executeUpdate() }

    private fun Connection.markIntentDeletion(
        itemId: String,
        expectedGeneration: Long,
    ): Int =
        prepareStatement(
                bindNamedParameters(
                    ServerDatabaseDao.MARK_DOWNLOAD_INTENT_DELETION_SQL,
                    mapOf(
                        "updatedAt" to 100L,
                        "itemId" to itemId,
                        "expectedGeneration" to expectedGeneration,
                    ),
                )
            )
            .use { statement -> statement.executeUpdate() }

    private fun bindNamedParameters(sql: String, values: Map<String, Any>): String {
        var bound = sql
        values.forEach { (name, value) ->
            val literal =
                when (value) {
                    is Number -> value.toString()
                    else -> "'${value.toString().replace("'", "''")}'"
                }
            require(bound.contains(":$name")) { "missing SQL parameter $name" }
            bound = bound.replace(":$name", literal)
        }
        return bound
    }

    private fun Connection.intentState(itemId: String): IntentState? =
        prepareStatement(
                """
                SELECT
                    status, taskGeneration, name, albumName, artist, trackIndex,
                    failureReason, failureDetail
                FROM downloadIntents
                WHERE itemId = ?
                """
            )
            .use { statement ->
                statement.setString(1, itemId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        IntentState(
                            status = result.getString("status"),
                            generation = result.getLong("taskGeneration"),
                            name = result.getString("name"),
                            albumName = result.getString("albumName"),
                            artist = result.getString("artist"),
                            trackIndex = result.getInt("trackIndex"),
                            failureReason = result.getString("failureReason"),
                            failureDetail = result.getString("failureDetail"),
                        )
                    }
                }
            }
}
