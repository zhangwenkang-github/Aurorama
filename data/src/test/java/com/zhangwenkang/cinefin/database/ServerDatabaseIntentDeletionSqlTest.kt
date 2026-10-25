package com.zhangwenkang.cinefin.database

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDatabaseIntentDeletionSqlTest {

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
            assertEquals(IntentState("FAILED", 7L), connection.intentState("failed"))
            assertEquals(IntentState("FAILED", 4L), connection.intentState("received"))
        }
    }

    private data class IntentState(val status: String, val generation: Long)

    private fun Connection.createDownloadIntentsTable() {
        createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE downloadIntents (
                    itemId TEXT NOT NULL PRIMARY KEY,
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
    ) {
        prepareStatement(
                """
                INSERT INTO downloadIntents(itemId, status, taskGeneration)
                VALUES (?, ?, ?)
                """
            )
            .use { statement ->
                statement.setString(1, itemId)
                statement.setString(2, status)
                statement.setLong(3, generation)
                statement.executeUpdate()
            }
    }

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
                SELECT status, taskGeneration
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
                        )
                    }
                }
            }
}
