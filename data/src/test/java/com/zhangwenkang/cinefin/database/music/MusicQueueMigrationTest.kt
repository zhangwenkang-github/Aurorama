package com.zhangwenkang.cinefin.database.music

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

/**
 * W5 T5（MUSIC-09/12）：MusicDatabase 3 → 4 迁移实测。
 *
 * 手段与 `MusicMetadataMigrationTest` 一致：用 v3 schema 建真实 SQLite 库文件 + 插入旧行，再把
 * `musicQueueMigration3To4Statements` 产出的语句原样执行，验证「旧行保留 + 新列带默认值 + 可重放（幂等）」。 不依赖设备（跑在
 * `:data:testDebugUnitTest`），并回读导出的 4.json 钉死「实体默认值 == 迁移默认值」。
 */
class MusicQueueMigrationTest {

    @Test
    fun `3 到 4 只追加两个带默认值的列并保留旧行`() {
        val fixtureFile = Files.createTempFile("music-database-v3-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV3Tables()
                connection.insertV3Rows()
                assertEquals(3, connection.userVersion)

                val statements = migrationStatements(connection)
                assertEquals("3→4 必须只发出两条 ADD COLUMN", 2, statements.size)
                statements.forEach { sql ->
                    assertTrue(
                        "3→4 只能 ALTER ADD COLUMN：$sql",
                        sql.trimStart().startsWith("ALTER TABLE"),
                    )
                    assertTrue("3→4 追加列必须带默认值：$sql", sql.contains("DEFAULT"))
                }
                statements.forEach { connection.execute(it) }
                connection.execute("PRAGMA user_version = 4")

                assertEquals(4, connection.userVersion)
                connection.assertNewColumns()
                connection.assertLegacyRowsPreserved()

                // 幂等：同一份迁移再跑一次不报错，且不再产生语句
                assertTrue("迁移必须幂等", migrationStatements(connection).isEmpty())
            }
        } finally {
            fixtureFile.delete()
        }
    }

    @Test
    fun `迁移后可以写入接下来播放分区条目`() {
        val fixtureFile = Files.createTempFile("music-database-v3-upnext-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV3Tables()
                connection.insertV3Rows()
                migrationStatements(connection).forEach { connection.execute(it) }

                connection.execute(
                    "INSERT INTO music_queue_items " +
                        "(position, itemId, name, mediaSourceId, playbackPositionMs, " +
                        "mediaSourceUri, thumbnailUri, indexNumber, kind) VALUES " +
                        "(10, '00000000-0000-0000-0000-0000000000c1', 'UpNext', 'src-u', 0, " +
                        "'https://example.test/upnext', NULL, NULL, 'UP_NEXT')"
                )
                connection.execute(
                    "UPDATE music_queue_state SET shuffleKeepCurrentFirst = 1 WHERE id = 0"
                )

                connection.query(
                    "SELECT name, kind FROM music_queue_items WHERE kind = 'UP_NEXT'"
                ) { result ->
                    assertTrue(result.next())
                    assertEquals("UpNext", result.getString("name"))
                }
                connection.query("SELECT shuffleKeepCurrentFirst FROM music_queue_state") { result
                    ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        } finally {
            fixtureFile.delete()
        }
    }

    @Test
    fun `4 到 5 只追加不可用列并保留旧行`() {
        val fixtureFile = Files.createTempFile("music-database-v4-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV3Tables()
                connection.insertV3Rows()
                migrationStatements(connection).forEach { connection.execute(it) }
                connection.execute("PRAGMA user_version = 4")

                val statements = connection.migration4To5Statements()
                assertEquals("4→5 必须只发出一条 ADD COLUMN", 1, statements.size)
                assertTrue(
                    "4→5 只能 ALTER ADD COLUMN：${statements.single()}",
                    statements.single().trimStart().startsWith("ALTER TABLE"),
                )
                assertTrue(
                    "4→5 追加列必须带默认值：${statements.single()}",
                    statements.single().contains("DEFAULT 0"),
                )
                statements.forEach { connection.execute(it) }
                connection.execute("PRAGMA user_version = 5")

                assertEquals(5, connection.userVersion)
                assertTrue(
                    "缺列 music_queue_items.unavailable",
                    "unavailable" in connection.columns("music_queue_items"),
                )
                connection.query(
                    "SELECT name, unavailable FROM music_queue_items ORDER BY position"
                ) { result ->
                    assertTrue(result.next())
                    assertEquals("First", result.getString("name"))
                    assertEquals("旧行默认可播", 0, result.getInt("unavailable"))
                }
                assertTrue("迁移必须幂等", connection.migration4To5Statements().isEmpty())
            }
        } finally {
            fixtureFile.delete()
        }
    }

    @Test
    fun `导出的 5 版 schema 与迁移默认值一致`() {
        val database =
            Json.parseToJsonElement(File(locateSchemaDir(5), "5.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val entity =
            database
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == "music_queue_items" }
        val field =
            entity
                .getValue("fields")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("columnName").jsonPrimitive.content == "unavailable" }

        assertEquals("0", field["defaultValue"]?.jsonPrimitive?.content)
        assertEquals("INTEGER", field.getValue("affinity").jsonPrimitive.content)
        assertEquals(true, field.getValue("notNull").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `导出的 4 版 schema 与迁移默认值一致`() {
        val database =
            Json.parseToJsonElement(File(locateSchemaDir(4), "4.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val entities = database.getValue("entities").jsonArray.map { it.jsonObject }

        fun columnDefaults(table: String, column: String): String? {
            val entity = entities.first { it.getValue("tableName").jsonPrimitive.content == table }
            val fields = entity.getValue("fields").jsonArray.map { it.jsonObject }
            val field = fields.first { it.getValue("columnName").jsonPrimitive.content == column }
            return field["defaultValue"]?.jsonPrimitive?.content
        }

        assertEquals("'MAIN'", columnDefaults("music_queue_items", "kind"))
        assertEquals("0", columnDefaults("music_queue_state", "shuffleKeepCurrentFirst"))
    }

    private fun migrationStatements(connection: Connection): List<String> =
        musicQueueMigration3To4Statements { table ->
            connection.columns(table)
        }

    private fun Connection.migration4To5Statements(): List<String> =
        musicQueueMigration4To5Statements { table ->
            columns(table)
        }

    private fun connect(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    private fun Connection.createV3Tables() {
        val schemaDir = locateSchemaDir(3)
        val database =
            Json.parseToJsonElement(File(schemaDir, "3.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        database
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject }
            .filter { entity ->
                entity.getValue("tableName").jsonPrimitive.content in
                    setOf("music_queue_items", "music_queue_state")
            }
            .forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val createSql =
                    entity
                        .getValue("createSql")
                        .jsonPrimitive
                        .content
                        .replace("\${TABLE_NAME}", table)
                execute(createSql)
            }
        execute("PRAGMA user_version = 3")
    }

    private fun Connection.insertV3Rows() {
        execute(
            "INSERT INTO music_queue_items " +
                "(position, itemId, name, mediaSourceId, playbackPositionMs, mediaSourceUri, " +
                "thumbnailUri, indexNumber) VALUES " +
                "(0, '00000000-0000-0000-0000-0000000000b1', 'First', 'src-a', 1234, " +
                "'https://example.test/first', 'https://example.test/thumb', 3), " +
                "(1, '00000000-0000-0000-0000-0000000000b2', 'Second', 'src-b', 5678, " +
                "'https://example.test/second', NULL, NULL)"
        )
        execute(
            "INSERT INTO music_queue_state " +
                "(id, currentIndex, positionMs, repeatMode, shuffleEnabled, source, sourceId, " +
                "savedAt, shuffleOrder, shuffleSeed) VALUES " +
                "(0, 1, 5678, 'ALL', 1, 'PLAYLIST', " +
                "'00000000-0000-0000-0000-0000000000b3', 987654321, '1,0', 42)"
        )
    }

    private fun Connection.assertNewColumns() {
        val items = columns("music_queue_items")
        assertTrue("缺列 music_queue_items.kind", "kind" in items)
        val state = columns("music_queue_state")
        assertTrue(
            "缺列 music_queue_state.shuffleKeepCurrentFirst",
            "shuffleKeepCurrentFirst" in state,
        )
    }

    private fun Connection.assertLegacyRowsPreserved() {
        query(
            "SELECT position, name, playbackPositionMs, kind FROM music_queue_items " +
                "ORDER BY position"
        ) { result ->
            assertTrue(result.next())
            assertEquals(0, result.getInt("position"))
            assertEquals("First", result.getString("name"))
            assertEquals(1234L, result.getLong("playbackPositionMs"))
            assertEquals("旧行默认归主队列", "MAIN", result.getString("kind"))
            assertTrue(result.next())
            assertEquals("Second", result.getString("name"))
            assertEquals("MAIN", result.getString("kind"))
        }
        query(
            "SELECT currentIndex, positionMs, repeatMode, shuffleEnabled, shuffleOrder, " +
                "shuffleSeed, shuffleKeepCurrentFirst FROM music_queue_state"
        ) { result ->
            assertTrue(result.next())
            assertEquals(1, result.getInt("currentIndex"))
            assertEquals(5678L, result.getLong("positionMs"))
            assertEquals("ALL", result.getString("repeatMode"))
            assertEquals(1, result.getInt("shuffleEnabled"))
            assertEquals("1,0", result.getString("shuffleOrder"))
            assertEquals(42L, result.getLong("shuffleSeed"))
            assertEquals("旧行默认全随机", 0, result.getInt("shuffleKeepCurrentFirst"))
        }
    }

    private fun Connection.columns(table: String): Set<String> = buildSet {
        query("PRAGMA table_info(`$table`)") { result ->
            while (result.next()) {
                add(result.getString("name"))
            }
        }
    }

    private fun <T> Connection.query(sql: String, read: (java.sql.ResultSet) -> T): T =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> read(result) }
        }

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private val Connection.userVersion: Int
        get() =
            query("PRAGMA user_version") { result ->
                assertTrue(result.next())
                result.getInt(1)
            }

    private fun locateSchemaDir(version: Int): File =
        generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
            .map {
                it.resolve("data/schemas/com.zhangwenkang.cinefin.database.music.MusicDatabase")
            }
            .firstOrNull { it.resolve("$version.json").isFile }
            ?: error("找不到 MusicDatabase schema $version")
}
