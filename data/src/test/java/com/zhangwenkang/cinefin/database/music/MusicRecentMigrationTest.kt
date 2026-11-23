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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T2（F1b）：MusicDatabase 5 → 6 迁移实测。
 *
 * 用 v5 schema 建真实 SQLite 库文件 + 插入旧最近播放行，再把 [musicRecentMigration5To6Statements] 产出的语句原样执行，
 * 验证「旧行保留 + 身份列可空且为 null + 可重放（幂等）」。不依赖设备（跑在 `:data:testDebugUnitTest`）。
 */
class MusicRecentMigrationTest {

    @Test
    fun `5 到 6 只给最近播放追加两个可空身份列并保留旧行`() {
        val fixtureFile = Files.createTempFile("music-database-v5-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV5RecentTable()
                connection.insertV5RecentRow()
                assertEquals(5, connection.userVersion)

                val statements = connection.migration5To6Statements()
                assertEquals("5→6 必须只发出两条 ADD COLUMN", 2, statements.size)
                statements.forEach { sql ->
                    assertTrue(
                        "5→6 只能 ALTER ADD COLUMN：$sql",
                        sql.trimStart().startsWith("ALTER TABLE"),
                    )
                    assertTrue("5→6 的身份列必须可空：$sql", !sql.contains("NOT NULL"))
                }
                statements.forEach { connection.execute(it) }
                connection.execute("PRAGMA user_version = 6")

                assertEquals(6, connection.userVersion)
                val columns = connection.columns("music_recent")
                assertTrue("缺列 music_recent.serverId", "serverId" in columns)
                assertTrue("缺列 music_recent.userId", "userId" in columns)
                connection.query(
                    "SELECT itemId, name, playedAt, serverId, userId FROM music_recent"
                ) { result ->
                    assertTrue(result.next())
                    assertEquals("00000000-0000-0000-0000-000000000501", result.getString(1))
                    assertEquals("旧最近播放", result.getString(2))
                    assertEquals(1700000000000L, result.getLong(3))
                    assertNull(result.getString(4))
                    assertNull(result.getString(5))
                }

                // 幂等：同一份迁移再跑一次不报错，且不再产生语句
                assertTrue("迁移必须幂等", connection.migration5To6Statements().isEmpty())
            }
        } finally {
            fixtureFile.delete()
        }
    }

    @Test
    fun `迁移后可以写入服务器身份`() {
        val fixtureFile = Files.createTempFile("music-database-v5-identity-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV5RecentTable()
                connection.insertV5RecentRow()
                connection.migration5To6Statements().forEach { connection.execute(it) }

                connection.execute(
                    "UPDATE music_recent SET serverId = 'server-a', userId = 'user-a' WHERE itemId = " +
                        "'00000000-0000-0000-0000-000000000501'"
                )
                connection.query("SELECT serverId, userId FROM music_recent") { result ->
                    assertTrue(result.next())
                    assertEquals("server-a", result.getString(1))
                    assertEquals("user-a", result.getString(2))
                }
            }
        } finally {
            fixtureFile.delete()
        }
    }

    @Test
    fun `导出的 6 版 schema 含可空身份列`() {
        val database =
            Json.parseToJsonElement(File(locateSchemaDir(6), "6.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val entity =
            database
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == "music_recent" }
        val fields =
            entity
                .getValue("fields")
                .jsonArray
                .map { it.jsonObject }
                .associate { it.getValue("columnName").jsonPrimitive.content to it }

        listOf("serverId", "userId").forEach { column ->
            val field = fields.getValue(column)
            // Room 导出可空列时省略 notNull（缺省即 false）
            assertEquals(
                "$column 必须可空",
                false,
                field["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
            assertEquals("TEXT", field.getValue("affinity").jsonPrimitive.content)
        }
    }

    private fun Connection.migration5To6Statements(): List<String> =
        musicRecentMigration5To6Statements { table ->
            columns(table)
        }

    private fun connect(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    private fun Connection.createV5RecentTable() {
        val schemaDir = locateSchemaDir(5)
        val database =
            Json.parseToJsonElement(File(schemaDir, "5.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        database
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject }
            .filter { entity ->
                entity.getValue("tableName").jsonPrimitive.content == "music_recent"
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
        execute("PRAGMA user_version = 5")
    }

    private fun Connection.insertV5RecentRow() {
        execute(
            "INSERT INTO music_recent " +
                "(itemId, name, albumName, artist, imageUri, runtimeTicks, playedAt) VALUES " +
                "('00000000-0000-0000-0000-000000000501', '旧最近播放', '专辑', '歌手', NULL, " +
                "1800000000, 1700000000000)"
        )
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
