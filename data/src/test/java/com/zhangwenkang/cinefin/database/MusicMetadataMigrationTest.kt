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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 18 → 19 迁移实测（MUSIC-35）：本地音乐元数据扩展。
 *
 * 手段与既有 `ServerDatabaseMigrationTest` 一致：用 v18 schema 建真实 SQLite 库文件 + 插入旧行， 再把 KSP 生成的
 * AutoMigration SQL 原样执行，验证「旧行保留 + 新列可空 + user_version 推进」。 不依赖设备（跑在 `:data:testDebugUnitTest`）。
 */
class MusicMetadataMigrationTest {

    @Test
    fun `Room 18 到 19 仅追加七个可空音乐元数据列并保留旧行`() {
        val fixtureFile = Files.createTempFile("server-database-v18-", ".db").toFile()
        try {
            fixtureFile.delete()
            connect(fixtureFile).use { connection ->
                connection.createV18LocalMediaItems()
                connection.insertV18Row()
                assertEquals(18, connection.userVersion)

                val statements = generatedMigration18To19Sql()
                statements.forEach { connection.execute(it) }
                connection.execute("PRAGMA user_version = 19")

                assertEquals(19, connection.userVersion)
                connection.assertNewColumns()
                connection.assertLegacyRowPreserved()
            }
        } finally {
            fixtureFile.delete()
        }
    }

    private fun connect(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    /** 用 v18 schema 里的 `createSql` 建表（与生产升级前的库结构逐字一致）。 */
    private fun Connection.createV18LocalMediaItems() {
        val schemaDir = locateSchemaDir(18)
        val database =
            Json.parseToJsonElement(File(schemaDir, "18.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val createSql =
            database
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == TABLE }
                .getValue("createSql")
                .jsonPrimitive
                .content
                .replace("\${TABLE_NAME}", TABLE)
        createStatement().use { it.execute(createSql) }
        createStatement().use { it.execute("PRAGMA user_version = 18") }
    }

    private fun Connection.insertV18Row() {
        createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO local_media_items(
                    itemId, libraryId, folderId, documentUri, documentId, relativePath,
                    name, extension, kind, sizeBytes, lastModified, title, artist, album,
                    trackIndex, durationMs, coverUri, subtitles, indexedAt
                )
                VALUES (
                    '00000000-0000-0000-0000-0000000000a1', 1, 1,
                    'content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fold.flac',
                    'primary:Music/old.flac', 'Music/old.flac', 'old.flac', 'flac', 'MUSIC',
                    1024, 200, 'Old Title', 'Old Artist', 'Old Album', 3, 210_000,
                    NULL, '', 300
                )
                """
            )
        }
    }

    private fun generatedMigration18To19Sql(): List<String> {
        val statements = readGeneratedMigrationStatements(from = 18, to = 19)
        assertEquals("AutoMigration 18→19 必须只发出七条 ADD COLUMN", 7, statements.size)
        val expected =
            setOf(
                "ALTER TABLE `local_media_items` ADD COLUMN `albumArtist` TEXT DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `discNumber` INTEGER DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `genre` TEXT DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `year` INTEGER DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `bitrate` INTEGER DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `sampleRate` INTEGER DEFAULT NULL",
                "ALTER TABLE `local_media_items` ADD COLUMN `bitDepth` INTEGER DEFAULT NULL",
            )
        assertEquals(expected, statements.map { it.trim().removeSuffix(";") }.toSet())
        statements.forEach { sql ->
            assertTrue("18→19 只能 ALTER ADD COLUMN：$sql", sql.trimStart().startsWith("ALTER TABLE"))
            assertTrue("18→19 禁止 NOT NULL：$sql", !sql.contains("NOT NULL", ignoreCase = true))
        }
        return statements
    }

    private fun readGeneratedMigrationStatements(from: Int, to: Int): List<String> {
        val migrationFile = locateGeneratedMigrationFile(from, to)
        assertTrue("缺少 KSP 生成的 AutoMigration $from→$to", migrationFile.isFile)
        if (!migrationFile.isFile) return emptyList()
        val source = migrationFile.readText()
        return Regex("""\bexecSQL\("((?:\\.|[^"\\])*)"\)""")
            .findAll(source)
            .map { match -> match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
    }

    /** 新列存在、可空、默认 NULL（旧行升级后按「未知」处理，不伪造 0）。 */
    private fun Connection.assertNewColumns() {
        val expectedAffinity =
            mapOf(
                "albumArtist" to "TEXT",
                "discNumber" to "INTEGER",
                "genre" to "TEXT",
                "year" to "INTEGER",
                "bitrate" to "INTEGER",
                "sampleRate" to "INTEGER",
                "bitDepth" to "INTEGER",
            )
        val actual = mutableMapOf<String, Triple<String, Int, String?>>()
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$TABLE`)").use { result ->
                while (result.next()) {
                    actual[result.getString("name")] =
                        Triple(
                            result.getString("type"),
                            result.getInt("notnull"),
                            result.getString("dflt_value"),
                        )
                }
            }
        }
        expectedAffinity.forEach { (column, affinity) ->
            val info = actual[column] ?: error("缺列 local_media_items.$column")
            assertEquals("$column affinity", affinity, info.first)
            assertEquals("$column must be nullable", 0, info.second)
            assertTrue(
                "$column default must be NULL, was ${info.third}",
                info.third == null || info.third.equals("NULL", ignoreCase = true),
            )
        }
    }

    private fun Connection.assertLegacyRowPreserved() {
        createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT itemId, name, title, artist, album, trackIndex, durationMs,
                           albumArtist, discNumber, genre, year, bitrate, sampleRate, bitDepth
                    FROM local_media_items
                    """
                )
                .use { result ->
                    assertTrue("v18 旧行必须保留", result.next())
                    assertEquals("00000000-0000-0000-0000-0000000000a1", result.getString("itemId"))
                    assertEquals("old.flac", result.getString("name"))
                    assertEquals("Old Title", result.getString("title"))
                    assertEquals("Old Artist", result.getString("artist"))
                    assertEquals("Old Album", result.getString("album"))
                    assertEquals(3, result.getInt("trackIndex"))
                    assertEquals(210_000L, result.getLong("durationMs"))
                    listOf(
                            "albumArtist",
                            "discNumber",
                            "genre",
                            "year",
                            "bitrate",
                            "sampleRate",
                            "bitDepth",
                        )
                        .forEach { column ->
                            assertNull("旧行的 $column 必须为 NULL", result.getObject(column))
                        }
                }
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private val Connection.userVersion: Int
        get() =
            createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    assertTrue(result.next())
                    result.getInt(1)
                }
            }

    private fun locateSchemaDir(version: Int): File =
        generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
            .map { it.resolve("data/schemas/com.zhangwenkang.cinefin.database.ServerDatabase") }
            .firstOrNull { it.resolve("$version.json").isFile }
            ?: error("找不到 ServerDatabase schema $version")

    private fun locateGeneratedMigrationFile(from: Int, to: Int): File {
        val relative =
            "data/build/generated/ksp/debug/kotlin/com/zhangwenkang/cinefin/database/" +
                "ServerDatabase_AutoMigration_${from}_${to}_Impl.kt"
        return generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) {
                it.parentFile
            }
            .map { it.resolve(relative) }
            .firstOrNull(File::isFile) ?: File(System.getProperty("user.dir")!!, relative)
    }

    private companion object {
        const val TABLE = "local_media_items"
    }
}
