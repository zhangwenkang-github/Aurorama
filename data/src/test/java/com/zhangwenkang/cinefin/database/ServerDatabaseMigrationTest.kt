package com.zhangwenkang.cinefin.database

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDatabaseMigrationTest {

    @Test
    fun `Room 15 到 16 仅追加书籍任务与删除代次且保留旧行`() {
        val fixtureFile = Files.createTempFile("server-database-v15-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV15Fixture()
                connection.insertV15Rows()
                assertEquals(15, connection.userVersion)

                connection.openV15Database()

                assertEquals(16, connection.userVersion)
                connection.assertMigratedRows()
            }

            connect(fixtureFile).use { connection ->
                assertEquals(16, connection.userVersion)
                connection.openV15Database()
                connection.assertMigratedRows()
            }
        } finally {
            fixtureFile.delete()
        }
    }

    private fun connect(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    private fun Connection.createV15Fixture() {
        val schemaDir = locateSchemaDir(version = 15)
        val database =
            Json.parseToJsonElement(File(schemaDir, "15.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val fixtureTableNames =
            listOf(
                "servers",
                "users",
                "sources",
                "downloadIntents",
                "downloadDeleteBatches",
                "downloadDeleteReceipts",
            )
        val createSqlByTable =
            database
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject }
                .associate {
                    val tableName = it.getValue("tableName").jsonPrimitive.content
                    tableName to it.getValue("createSql").jsonPrimitive.content
                }

        createStatement().use { statement ->
            fixtureTableNames.forEach { tableName ->
                statement.execute(
                    createSqlByTable.getValue(tableName).replace("\${TABLE_NAME}", tableName)
                )
            }
        }
        createStatement().use { it.execute("PRAGMA user_version = 15") }
    }

    private fun Connection.insertV15Rows() {
        createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO servers(id, name, currentServerAddressId, currentUserId)
                VALUES ('server-a', 'Home', NULL, 'user-a')
                """
            )
            statement.execute(
                """
                INSERT INTO users(id, name, serverId, accessToken)
                VALUES ('user-a', 'Reader', 'server-a', 'fixture-token')
                """
            )
            statement.execute(
                """
                INSERT INTO sources(
                    id, itemId, name, type, path, taskStatus, failureReason, updatedAt,
                    allowOffline, downloadedBytes, totalBytes, taskGeneration
                )
                VALUES (
                    'source-a', '00000000-0000-0000-0000-000000000041',
                    'book.epub', 'REMOTE', '/fixture/book.epub', 'COMPLETED', NULL, 101,
                    1, 41, 100, 7
                )
                """
            )
            statement.execute(
                """
                INSERT INTO downloadIntents(
                    itemId, name, albumName, artist, trackIndex, status, failureReason,
                    failureDetail, createdAt, updatedAt, taskGeneration
                )
                VALUES (
                    '00000000-0000-0000-0000-000000000042',
                    'track.flac', 'Legacy Album', 'Legacy Artist', 2, 'COMPLETED',
                    NULL, NULL, 102, 103, 8
                )
                """
            )
            statement.execute(
                """
                INSERT INTO downloadDeleteBatches(id, requestedCount, createdAt, completedAt)
                VALUES ('batch-a', 1, 104, NULL)
                """
            )
            statement.execute(
                """
                INSERT INTO downloadDeleteReceipts(
                    batchId, canonicalKey, itemId, sourceId, serverId, userId, ordinal,
                    mediaKind, outcome, detail, updatedAt
                )
                VALUES (
                    'batch-a', 'BOOK:server-a:user-a:book-a',
                    '00000000-0000-0000-0000-000000000041', 'source-a',
                    'server-a', 'user-a', 0, 'BOOK', 'PENDING', 'legacy receipt', 105
                )
                """
            )
        }
    }

    private fun Connection.openV15Database() {
        if (userVersion == 15) {
            generatedMigrationSql().forEach { sql -> execute(sql) }
            createStatement().use { it.execute("PRAGMA user_version = 16") }
        }
    }

    private fun Connection.generatedMigrationSql(): List<String> {
        val migrationFile = locateGeneratedMigrationFile()
        val source = migrationFile.readText()
        val statements =
            Regex("""\bexecSQL\("((?:\\.|[^"\\])*)"\)""")
                .findAll(source)
                .map { match -> match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
                .toList()
        assertEquals("AutoMigration 15→16 必须只发出一条 ALTER 与一条 CREATE", 2, statements.size)
        assertTrue(statements.any { it.contains("ADD COLUMN `verifiedGeneration` INTEGER") })
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS `bookDownloadTasks`") })
        return statements
    }

    private fun Connection.assertMigratedRows() {
        assertEquals(listOf(1, 1, 1, 1, 1, 1), tableCounts())
        assertKeyValues(
            sql =
                """
                SELECT name, currentUserId FROM servers WHERE id = 'server-a'
                """,
            expected = listOf("Home", "user-a"),
        )
        assertKeyValues(
            sql = "SELECT name, serverId FROM users WHERE id = 'user-a'",
            expected = listOf("Reader", "server-a"),
        )
        assertKeyValues(
            sql =
                """
                SELECT itemId, taskStatus, downloadedBytes, totalBytes, taskGeneration
                FROM sources WHERE id = 'source-a'
                """,
            expected =
                listOf(
                    "00000000-0000-0000-0000-000000000041",
                    "COMPLETED",
                    41L,
                    100L,
                    7L,
                ),
        )
        assertKeyValues(
            sql =
                """
                SELECT name, albumName, status, taskGeneration
                FROM downloadIntents
                WHERE itemId = '00000000-0000-0000-0000-000000000042'
                """,
            expected = listOf("track.flac", "Legacy Album", "COMPLETED", 8L),
        )
        assertKeyValues(
            sql =
                """
                SELECT canonicalKey, itemId, sourceId, outcome, detail, verifiedGeneration
                FROM downloadDeleteReceipts
                WHERE batchId = 'batch-a'
                """,
            expected =
                listOf(
                    "BOOK:server-a:user-a:book-a",
                    "00000000-0000-0000-0000-000000000041",
                    "source-a",
                    "PENDING",
                    "legacy receipt",
                    null,
                ),
        )
        assertTrue(tableColumns("bookDownloadTasks").containsAll(BOOK_COLUMNS))
    }

    private fun Connection.tableCounts(): List<Int> =
        listOf(
                "servers",
                "users",
                "sources",
                "downloadIntents",
                "downloadDeleteBatches",
                "downloadDeleteReceipts",
            )
            .map { table -> scalar("SELECT COUNT(*) FROM $table").toInt() }

    private fun Connection.assertKeyValues(sql: String, expected: List<Any?>) {
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue("fixture row missing", result.next())
                expected.forEachIndexed { index, value ->
                    assertEquals(
                        "column ${index + 1}",
                        value?.toString(),
                        result.getObject(index + 1)?.toString(),
                    )
                }
            }
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private fun Connection.tableColumns(tableName: String): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$tableName`)").use { result ->
                buildSet {
                    while (result.next()) {
                        add(result.getString("name"))
                    }
                }
            }
        }

    private fun Connection.scalar(sql: String): Long =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }

    private val Connection.userVersion: Int
        get() = scalar("PRAGMA user_version").toInt()

    private fun locateSchemaDir(version: Int): File =
        generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
            .map { it.resolve("data/schemas/com.zhangwenkang.cinefin.database.ServerDatabase") }
            .firstOrNull { it.resolve("$version.json").isFile }
            ?: error("找不到 ServerDatabase schema $version")

    private fun locateGeneratedMigrationFile(): File =
        generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
            .map {
                it.resolve(
                    "data/build/generated/ksp/debug/kotlin/com/zhangwenkang/cinefin/database/" +
                        "ServerDatabase_AutoMigration_15_16_Impl.kt"
                )
            }
            .firstOrNull(File::isFile) ?: error("找不到 Room KSP 生成的 AutoMigration 15→16")

    private companion object {
        val BOOK_COLUMNS =
            setOf(
                "canonicalKey",
                "itemId",
                "serverId",
                "userId",
                "title",
                "targetPath",
                "downloadedBytes",
                "totalBytes",
                "status",
                "failureReason",
                "resumeValidator",
                "taskGeneration",
                "createdAt",
                "updatedAt",
            )
    }
}
