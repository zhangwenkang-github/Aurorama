package com.zhangwenkang.cinefin.database

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDatabaseRetrySqlTest {

    @Test
    fun `真实 DAO SQL 只允许 FAILED 代次重试且不打断 active worker`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(status = "FAILED", generation = 3)
            connection.insertSource(id = "running", status = "RUNNING", generation = 7)
            connection.insertSource(id = "pending", status = "PENDING", generation = 9)

            assertEquals(1, connection.markRetry("failed", now = 100L))
            assertEquals(0, connection.markRetry("running", now = 100L))
            assertEquals(0, connection.markRetry("pending", now = 100L))

            assertEquals(
                SourceState("PENDING", null, 4),
                connection.sourceState("failed"),
            )
            assertEquals(SourceState("RUNNING", null, 7), connection.sourceState("running"))
            assertEquals(SourceState("PENDING", null, 9), connection.sourceState("pending"))
        }
    }

    private fun Connection.createSourcesTable() {
        createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE sources (
                    id TEXT NOT NULL PRIMARY KEY,
                    taskStatus TEXT,
                    failureReason TEXT,
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    nextRetryAt INTEGER NOT NULL DEFAULT 0,
                    taskGeneration INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """
            )
        }
    }

    private fun Connection.insertSource(
        id: String = "failed",
        status: String,
        generation: Long,
    ) {
        prepareStatement("INSERT INTO sources(id, taskStatus, taskGeneration) VALUES (?, ?, ?)")
            .use { statement ->
                statement.setString(1, id)
                statement.setString(2, status)
                statement.setLong(3, generation)
                statement.executeUpdate()
            }
    }

    private fun Connection.markRetry(id: String, now: Long): Int {
        val sql =
            bindNamedParameters(
                ServerDatabaseDao.MARK_SOURCE_RETRY_SQL,
                mapOf("id" to id, "now" to now),
            )
        return prepareStatement(sql).use { statement -> statement.executeUpdate() }
    }

    private fun Connection.sourceState(id: String): SourceState =
        prepareStatement(
                "SELECT taskStatus, failureReason, taskGeneration FROM sources WHERE id = ?"
            )
            .use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    result.next()
                    SourceState(
                        status = result.getString(1),
                        failureReason = result.getString(2),
                        generation = result.getLong(3),
                    )
                }
            }

    private fun bindNamedParameters(sql: String, values: Map<String, Any>): String {
        var bound = sql
        values.forEach { (name, value) ->
            val literal =
                when (value) {
                    is Number -> value.toString()
                    else -> "'${value.toString().replace("'", "''")}'"
                }
            bound = bound.replace(":$name", literal)
        }
        return bound
    }

    private data class SourceState(
        val status: String?,
        val failureReason: String?,
        val generation: Long,
    )
}
