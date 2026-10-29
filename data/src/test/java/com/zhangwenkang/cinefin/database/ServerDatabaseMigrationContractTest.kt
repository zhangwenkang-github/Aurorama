package com.zhangwenkang.cinefin.database

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDatabaseMigrationContractTest {

    @Test
    fun `Room 16 到 17 只为 sources 与 downloadIntents 追加可空 origin anchor 文本列`() {
        val schemaDir = locateSchemaDir(minVersion = 16)
        val afterFile = File(schemaDir, "17.json")
        assertTrue("缺少 ServerDatabase 17.json", afterFile.isFile)
        if (!afterFile.isFile) return

        val before = Json.parseToJsonElement(File(schemaDir, "16.json").readText()).jsonObject
        val after = Json.parseToJsonElement(afterFile.readText()).jsonObject
        val beforeTables = before.getValue("database").jsonObject.getValue("entities").jsonArray
        val afterTables = after.getValue("database").jsonObject.getValue("entities").jsonArray
        val oldColumns = tableColumns(beforeTables)
        val newColumns = tableColumns(afterTables)
        val expectedAdded =
            mapOf(
                "sources" to setOf("originServerId", "originUserId"),
                "downloadIntents" to setOf("originServerId", "originUserId"),
            )

        assertEquals(oldColumns.keys, newColumns.keys)
        oldColumns.forEach { (table, columns) ->
            assertTrue("迁移删除旧列 $table", newColumns.getValue(table).containsAll(columns))
            assertEquals(
                "表 $table 的追加列不符合 origin anchor 契约",
                expectedAdded[table].orEmpty(),
                newColumns.getValue(table) - columns,
            )
        }
        assertEquals(
            expectedAdded.getValue("sources"),
            columnNames(afterTables, "sources").takeLast(2).toSet(),
        )
        assertEquals(
            expectedAdded.getValue("downloadIntents"),
            columnNames(afterTables, "downloadIntents").takeLast(2).toSet(),
        )
        expectedAdded.forEach { (table, columns) ->
            assertNullTextFields(afterTables, table, columns)
        }
        assertEquals(schemaStructure(beforeTables), schemaStructure(afterTables))
    }

    @Test
    fun `download intent supersede SQL 写入 anchor 并继续推进 generation`() {
        val sql = ServerDatabaseDao.SUPERSEDE_DOWNLOAD_INTENT_FOR_COMPLETE_REQUEST_SQL

        assertTrue("supersede 未写入 originServerId", sql.contains("originServerId = :originServerId"))
        assertTrue("supersede 未写入 originUserId", sql.contains("originUserId = :originUserId"))
        assertTrue(
            "supersede 未继续推进 generation",
            sql.contains("taskGeneration = taskGeneration + 1"),
        )
    }

    @Test
    fun `Room 15 到 16 仅追加书籍任务与删除代次且保留旧行`() {
        val schemaDir = locateSchemaDir(minVersion = 15)
        val before = Json.parseToJsonElement(File(schemaDir, "15.json").readText()).jsonObject
        val after = Json.parseToJsonElement(File(schemaDir, "16.json").readText()).jsonObject
        val beforeTables = before.getValue("database").jsonObject.getValue("entities").jsonArray
        val afterTables = after.getValue("database").jsonObject.getValue("entities").jsonArray
        val oldColumns = tableColumns(beforeTables)
        val newColumns = tableColumns(afterTables)

        oldColumns.forEach { (table, columns) ->
            assertTrue("迁移删除旧表 $table", newColumns.containsKey(table))
            assertTrue("迁移删除旧列 $table", newColumns.getValue(table).containsAll(columns))
        }
        assertEquals(setOf("bookDownloadTasks"), newColumns.keys - oldColumns.keys)
        oldColumns.forEach { (table, columns) ->
            val expectedAddedColumns =
                if (table == "downloadDeleteReceipts") setOf("verifiedGeneration") else emptySet()
            assertEquals(
                "表 $table 的追加列不符合非破坏迁移合同",
                expectedAddedColumns,
                newColumns.getValue(table) - columns,
            )
        }
        assertEquals(
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
            ),
            newColumns.getValue("bookDownloadTasks"),
        )
        assertNewColumnsNullable(
            afterTables,
            "downloadDeleteReceipts",
            setOf("verifiedGeneration"),
        )
        assertNewColumnsNullable(
            afterTables,
            "bookDownloadTasks",
            setOf("failureReason", "resumeValidator"),
        )
        assertEquals(
            listOf("canonicalKey"),
            primaryKeyColumns(afterTables, "bookDownloadTasks"),
        )
    }

    @Test
    fun `Room 14 到 15 只追加 generation 与持久删除批次表`() {
        val schemaDir = locateSchemaDir(minVersion = 14)
        val before = Json.parseToJsonElement(File(schemaDir, "14.json").readText()).jsonObject
        val after = Json.parseToJsonElement(File(schemaDir, "15.json").readText()).jsonObject
        val beforeTables = before.getValue("database").jsonObject.getValue("entities").jsonArray
        val afterTables = after.getValue("database").jsonObject.getValue("entities").jsonArray
        val oldColumns = tableColumns(beforeTables)
        val newColumns = tableColumns(afterTables)

        oldColumns.forEach { (table, columns) ->
            assertTrue("迁移删除旧表 $table", newColumns.containsKey(table))
            assertTrue("迁移删除旧列 $table", newColumns.getValue(table).containsAll(columns))
        }
        assertEquals(
            setOf("taskGeneration"),
            newColumns.getValue("sources") - oldColumns.getValue("sources"),
        )
        assertEquals(
            setOf("taskGeneration"),
            newColumns.getValue("downloadIntents") - oldColumns.getValue("downloadIntents"),
        )
        assertTrue("downloadDeleteBatches 未新增", newColumns.containsKey("downloadDeleteBatches"))
        assertTrue("downloadDeleteReceipts 未新增", newColumns.containsKey("downloadDeleteReceipts"))
        assertEquals(
            setOf("id", "requestedCount", "createdAt", "completedAt"),
            newColumns.getValue("downloadDeleteBatches"),
        )
        assertEquals(
            setOf(
                "batchId",
                "canonicalKey",
                "itemId",
                "sourceId",
                "serverId",
                "userId",
                "ordinal",
                "mediaKind",
                "outcome",
                "detail",
                "updatedAt",
            ),
            newColumns.getValue("downloadDeleteReceipts"),
        )
        assertField(
            afterTables,
            tableName = "sources",
            columnName = "taskGeneration",
            notNull = true,
            defaultValue = "0",
        )
        assertField(
            afterTables,
            tableName = "downloadIntents",
            columnName = "taskGeneration",
            notNull = true,
            defaultValue = "0",
        )
        assertEquals(
            listOf("batchId", "canonicalKey"),
            primaryKeyColumns(afterTables, "downloadDeleteReceipts"),
        )
    }

    @Test
    fun `Room 13 到 14 只追加可空媒体元数据列并保留全部旧列`() {
        val schemaDir = locateSchemaDir()
        val before = Json.parseToJsonElement(File(schemaDir, "13.json").readText()).jsonObject
        val after = Json.parseToJsonElement(File(schemaDir, "14.json").readText()).jsonObject
        val beforeTables = before.getValue("database").jsonObject.getValue("entities").jsonArray
        val afterTables = after.getValue("database").jsonObject.getValue("entities").jsonArray

        val oldColumns = tableColumns(beforeTables)
        val newColumns = tableColumns(afterTables)

        oldColumns.forEach { (table, columns) ->
            assertTrue("迁移删除旧表 $table", newColumns.containsKey(table))
            assertTrue("迁移删除旧列 $table", newColumns.getValue(table).containsAll(columns))
        }
        assertEquals(
            emptyList<String>(),
            newColumns
                .getValue("sources")
                .filter { it !in oldColumns.getValue("sources") }
                .filterNot { it in setOf("bitrate", "container") },
        )
        assertEquals(
            emptyList<String>(),
            newColumns
                .getValue("mediastreams")
                .filter { it !in oldColumns.getValue("mediastreams") }
                .filterNot {
                    it in
                        setOf(
                            "bitRate",
                            "frameRate",
                            "bitDepth",
                            "channels",
                            "sampleRate",
                            "profile",
                        )
                },
        )
        assertNewColumnsNullable(afterTables, "sources", setOf("bitrate", "container"))
        assertNewColumnsNullable(
            afterTables,
            "mediastreams",
            setOf("bitRate", "frameRate", "bitDepth", "channels", "sampleRate", "profile"),
        )
    }

    private fun locateSchemaDir(minVersion: Int = 13): File {
        val relative = "data/schemas/com.zhangwenkang.cinefin.database.ServerDatabase"
        return generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) {
                it.parentFile
            }
            .map { it.resolve(relative) }
            .firstOrNull {
                it.resolve("${minVersion - 1}.json").isFile && it.resolve("$minVersion.json").isFile
            } ?: error("找不到 ServerDatabase schema ${minVersion - 1}/$minVersion")
    }

    private fun tableColumns(entities: JsonArray): Map<String, Set<String>> = buildMap {
        entities.forEach { entityElement ->
            val entity = entityElement.jsonObject
            val fields = entity.getValue("fields").jsonArray
            put(
                entity.getValue("tableName").jsonPrimitive.content,
                buildSet {
                    fields.forEach { fieldElement ->
                        add(fieldElement.jsonObject.getValue("columnName").jsonPrimitive.content)
                    }
                },
            )
        }
    }

    private fun columnNames(entities: JsonArray, tableName: String): List<String> =
        entities
            .map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == tableName }
            .getValue("fields")
            .jsonArray
            .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }

    private fun assertNullTextFields(
        entities: JsonArray,
        tableName: String,
        expected: Set<String>,
    ) {
        val fields =
            entities
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == tableName }
                .getValue("fields")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.getValue("columnName").jsonPrimitive.content }
        expected.forEach { column ->
            val field = fields.getValue(column)
            assertEquals(
                "$tableName.$column 类型",
                "TEXT",
                field.getValue("affinity").jsonPrimitive.content,
            )
            assertTrue(
                "$tableName.$column 必须可空",
                field["notNull"]?.jsonPrimitive?.boolean != true,
            )
            assertTrue(
                "$tableName.$column 不得设置默认值",
                field["defaultValue"]?.jsonPrimitive?.contentOrNull == null,
            )
        }
    }

    private fun schemaStructure(entities: JsonArray): Map<String, JsonObject> =
        entities.associate { entityElement ->
            val entity = entityElement.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            tableName to
                JsonObject(
                    buildMap {
                        entity["primaryKey"]?.let { put("primaryKey", it) }
                        entity["indices"]?.let { put("indices", it) }
                        entity["foreignKeys"]?.let { put("foreignKeys", it) }
                    }
                )
        }

    private fun assertNewColumnsNullable(
        entities: JsonArray,
        tableName: String,
        expected: Set<String>,
    ) {
        entities.forEach { entityElement ->
            val entity = entityElement.jsonObject
            if (entity.getValue("tableName").jsonPrimitive.contentOrNull != tableName)
                return@forEach
            val fields = entity.getValue("fields").jsonArray
            expected.forEach { column ->
                val field =
                    fields
                        .map { it.jsonObject }
                        .firstOrNull {
                            it.getValue("columnName").jsonPrimitive.contentOrNull == column
                        }
                assertTrue("$tableName.$column 未追加", field != null)
                assertTrue(
                    "$tableName.$column 必须是可空列",
                    field!!["notNull"]?.jsonPrimitive?.boolean != true,
                )
            }
            return
        }
    }

    private fun assertField(
        entities: JsonArray,
        tableName: String,
        columnName: String,
        notNull: Boolean,
        defaultValue: String?,
    ) {
        val table =
            entities
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == tableName }
        val field =
            table
                .getValue("fields")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("columnName").jsonPrimitive.contentOrNull == columnName }
        assertEquals(notNull, field["notNull"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(defaultValue, field["defaultValue"]?.jsonPrimitive?.contentOrNull)
    }

    private fun primaryKeyColumns(entities: JsonArray, tableName: String): List<String> =
        entities
            .map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == tableName }
            .getValue("primaryKey")
            .jsonObject
            .getValue("columnNames")
            .jsonArray
            .map { it.jsonPrimitive.content }
}
