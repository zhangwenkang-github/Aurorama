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

            assertEquals(
                1,
                connection.markRetry("failed", expectedGeneration = 3, now = 100L),
            )
            assertEquals(
                0,
                connection.markRetry("running", expectedGeneration = 7, now = 100L),
            )
            assertEquals(
                0,
                connection.markRetry("pending", expectedGeneration = 9, now = 100L),
            )
            assertEquals(
                0,
                connection.markRetry("paused", expectedGeneration = 11, now = 100L),
            )
            assertEquals(
                0,
                connection.markRetry("completed", expectedGeneration = 13, now = 100L),
            )
            assertEquals(
                0,
                connection.markRetry("deleting", expectedGeneration = 15, now = 100L),
            )

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

    @Test
    fun `旧 PAUSED 代次不能暂停同状态的新代次`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "paused", status = "PAUSED", generation = 6)

            assertEquals(0, connection.markPause("paused", expectedGeneration = 4, now = 100L))
            assertEquals(SourceState("PAUSED", null, 6), connection.sourceState("paused"))
        }
    }

    @Test
    fun `旧 FAILED 代次不能重试同状态的新代次`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "failed", status = "FAILED", generation = 6)

            assertEquals(
                0,
                connection.markRetry(
                    "failed",
                    expectedGeneration = 4,
                    now = 100L,
                ),
            )
            assertEquals(SourceState("FAILED", null, 6), connection.sourceState("failed"))
        }
    }

    @Test
    fun `旧 FAILED 代次不能取消同状态的新代次`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "failed", status = "FAILED", generation = 6)

            assertEquals(
                0,
                connection.markCancel("failed", expectedGeneration = 4, now = 100L),
            )
            assertEquals(SourceState("FAILED", null, 6), connection.sourceState("failed"))
        }
    }

    @Test
    fun `旧 PENDING 代次不能删除新代次`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSourcesTable()
            connection.insertSource(id = "pending", status = "PENDING", generation = 6)

            assertEquals(
                0,
                connection.markDelete("pending", expectedGeneration = 4, now = 100L),
            )
            assertEquals(SourceState("PENDING", null, 6), connection.sourceState("pending"))
        }
    }

    @Test
    fun `真实 completed SQL 只接受 COMPLETED 或 legacy null final`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createProjectionSourcesTable()
            connection.insertProjectionSource("pending", "item-pending", "PENDING", "file.g1")
            connection.insertProjectionSource(
                "pending-partial",
                "item-pending-partial",
                "PENDING",
                "file.g1.download",
            )
            connection.insertProjectionSource("completed", "item-completed", "COMPLETED", "file.g1")
            connection.insertProjectionSource(
                "completed-partial",
                "item-completed-partial",
                "COMPLETED",
                "file.g1.download",
            )
            connection.insertProjectionSource("legacy", "item-legacy", null, "file")
            connection.insertProjectionSource(
                "legacy-partial",
                "item-legacy-partial",
                null,
                "file.download",
            )
            connection.insertProjectionSource("running", "item-running", "RUNNING", "file.g1")
            connection.insertProjectionSource("failed", "item-failed", "FAILED", "file.g1")
            connection.insertProjectionSource("deleting", "item-deleting", "DELETING", "file.g1")

            val completedItems =
                connection
                    .prepareStatement(
                        """
                        SELECT DISTINCT sources.itemId
                        FROM sources
                        WHERE sources.type = 'LOCAL'
                          AND $COMPLETED_LOCAL_SOURCE_SQL
                        """
                    )
                    .use { statement ->
                        statement.executeQuery().use { result ->
                            buildSet { while (result.next()) add(result.getString(1)) }
                        }
                    }
            assertEquals(
                setOf("item-multi", "item-completed", "item-legacy"),
                completedItems,
            )

            val completedSource =
                connection
                    .prepareStatement(
                        """
                        SELECT sources.id
                        FROM sources
                        WHERE sources.itemId = 'item-multi'
                          AND sources.type = 'LOCAL'
                          AND $COMPLETED_LOCAL_SOURCE_SQL
                        """
                    )
                    .use { statement ->
                        statement.executeQuery().use { result ->
                            result.next()
                            result.getString(1)
                        }
                    }
            assertEquals("multi-completed", completedSource)
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

    private fun Connection.createProjectionSourcesTable() {
        createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE sources (
                    id TEXT NOT NULL PRIMARY KEY,
                    itemId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    path TEXT NOT NULL,
                    taskStatus TEXT
                )
                """
            )
            statement.execute(
                """
                INSERT INTO sources(id, itemId, type, path, taskStatus)
                VALUES ('multi-pending', 'item-multi', 'LOCAL', 'multi-pending.g1', 'PENDING'),
                       ('multi-completed', 'item-multi', 'LOCAL', 'multi-completed.g1', 'COMPLETED')
                """
            )
        }
    }

    private fun Connection.insertProjectionSource(
        id: String,
        itemId: String,
        status: String?,
        path: String,
    ) {
        prepareStatement(
                "INSERT INTO sources(id, itemId, type, path, taskStatus) VALUES (?, ?, 'LOCAL', ?, ?)"
            )
            .use { statement ->
                statement.setString(1, id)
                statement.setString(2, itemId)
                statement.setString(3, path)
                statement.setString(4, status)
                statement.executeUpdate()
            }
    }

    private fun Connection.markRetry(
        id: String,
        expectedGeneration: Long,
        now: Long,
    ): Int =
        markWithGeneration(
            ServerDatabaseDao.MARK_SOURCE_RETRY_SQL,
            id,
            expectedGeneration,
            now,
        )

    private fun Connection.markPause(
        id: String,
        expectedGeneration: Long,
        now: Long,
    ): Int =
        markWithGeneration(
            ServerDatabaseDao.MARK_SOURCE_PAUSE_SQL,
            id,
            expectedGeneration,
            now,
        )

    private fun Connection.markCancel(
        id: String,
        expectedGeneration: Long,
        now: Long,
    ): Int =
        markWithGeneration(
            ServerDatabaseDao.MARK_SOURCE_CANCELLATION_SQL,
            id,
            expectedGeneration,
            now,
        )

    private fun Connection.markDelete(
        id: String,
        expectedGeneration: Long,
        now: Long,
    ): Int =
        markWithGeneration(
            ServerDatabaseDao.MARK_SOURCE_DELETION_SQL,
            id,
            expectedGeneration,
            now,
        )

    private fun Connection.markWithGeneration(
        sql: String,
        id: String,
        expectedGeneration: Long,
        now: Long,
    ): Int {
        val bound =
            bindNamedParameters(
                sql,
                mapOf(
                    "id" to id,
                    "expectedGeneration" to expectedGeneration,
                    "now" to now,
                ),
            )
        return prepareStatement(bound).use { statement -> statement.executeUpdate() }
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
