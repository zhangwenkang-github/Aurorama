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
            connection.insertSource(id = "paused", status = "PAUSED", generation = 11)
            connection.insertSource(id = "completed", status = "COMPLETED", generation = 13)
            connection.insertSource(id = "deleting", status = "DELETING", generation = 15)

            assertEquals(1, connection.markRetry("failed", now = 100L))
            assertEquals(0, connection.markRetry("running", now = 100L))
            assertEquals(0, connection.markRetry("pending", now = 100L))
            assertEquals(0, connection.markRetry("paused", now = 100L))
            assertEquals(0, connection.markRetry("completed", now = 100L))
            assertEquals(0, connection.markRetry("deleting", now = 100L))

            assertEquals(
                SourceState("PENDING", null, 4),
                connection.sourceState("failed"),
            )
            assertEquals(SourceState("RUNNING", null, 7), connection.sourceState("running"))
            assertEquals(SourceState("PENDING", null, 9), connection.sourceState("pending"))
            assertEquals(SourceState("PAUSED", null, 11), connection.sourceState("paused"))
            assertEquals(SourceState("COMPLETED", null, 13), connection.sourceState("completed"))
            assertEquals(SourceState("DELETING", null, 15), connection.sourceState("deleting"))
        }
    }

    @Test
    fun `PAUSED 继续只接受 PAUSED 且推进一代`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "failed", status = "FAILED", generation = 2)
            connection.insertSource(id = "paused", status = "PAUSED", generation = 4)
            connection.insertSource(id = "running", status = "RUNNING", generation = 6)
            connection.insertSource(id = "pending", status = "PENDING", generation = 8)
            connection.insertSource(id = "completed", status = "COMPLETED", generation = 10)
            connection.insertSource(id = "deleting", status = "DELETING", generation = 12)

            assertEquals(0, connection.markResume("failed", expectedGeneration = 2, now = 100L))
            assertEquals(1, connection.markResume("paused", expectedGeneration = 4, now = 100L))
            assertEquals(0, connection.markResume("running", expectedGeneration = 6, now = 100L))
            assertEquals(0, connection.markResume("pending", expectedGeneration = 8, now = 100L))
            assertEquals(
                0,
                connection.markResume("completed", expectedGeneration = 10, now = 100L),
            )
            assertEquals(
                0,
                connection.markResume("deleting", expectedGeneration = 12, now = 100L),
            )
            assertEquals(SourceState("FAILED", null, 2), connection.sourceState("failed"))
            assertEquals(SourceState("PENDING", null, 5), connection.sourceState("paused"))
            assertEquals(SourceState("RUNNING", null, 6), connection.sourceState("running"))
            assertEquals(SourceState("PENDING", null, 8), connection.sourceState("pending"))
            assertEquals(SourceState("COMPLETED", null, 10), connection.sourceState("completed"))
            assertEquals(SourceState("DELETING", null, 12), connection.sourceState("deleting"))
        }
    }

    @Test
    fun `旧 PAUSED 代次不能恢复同状态的新代次`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "paused", status = "PAUSED", generation = 6)

            assertEquals(0, connection.markResume("paused", expectedGeneration = 4, now = 100L))
            assertEquals(SourceState("PAUSED", null, 6), connection.sourceState("paused"))

            assertEquals(1, connection.markResume("paused", expectedGeneration = 6, now = 100L))
            assertEquals(SourceState("PENDING", null, 7), connection.sourceState("paused"))
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

    private fun Connection.markResume(id: String, expectedGeneration: Long, now: Long): Int {
        val sql =
            bindNamedParameters(
                ServerDatabaseDao.MARK_SOURCE_RESUME_SQL,
                mapOf(
                    "id" to id,
                    "expectedGeneration" to expectedGeneration,
                    "now" to now,
                ),
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
