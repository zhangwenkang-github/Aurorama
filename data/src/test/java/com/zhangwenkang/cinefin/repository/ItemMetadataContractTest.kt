package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.FileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T6 契约：元数据写走原始 JSON 通道（`GET /Items/{id}` 原文 → 只替换三个键 → `POST /Items/{id}`）。
 *
 * 根因回归：SDK 类型化 DTO 重序列化会把 DateTime 按设备时区改写（`0001-01-01T00:00:00Z` →
 * `0001-01-01T08:05:43+08:05:43`），服务端无法解析而稳定 400。因此写路径必须保持服务端原文格式，只允许 `Name` / `Overview` /
 * `ForcedSortName` 三个键变化。
 *
 * 排序名写 `ForcedSortName`：Jellyfin 12.4.0 `ItemUpdateController` 只消费该字段，`SortName` 是派生的只读回显值。
 */
class ItemMetadataContractTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val apiClient = mockk<ApiClient>()

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.api } returns apiClient
        }

    private val preferences =
        mockk<SharedPreferences>(relaxed = true).also { preferences ->
            every { preferences.getBoolean(any(), any()) } answers { secondArg() }
            every { preferences.getInt(any(), any()) } answers { secondArg() }
            every { preferences.getLong(any(), any()) } answers { secondArg() }
            every { preferences.getFloat(any(), any()) } answers { secondArg() }
            every { preferences.getString(any(), any()) } answers { secondArg() }
        }

    private val repository =
        JellyfinRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = api,
            database = database,
            appPreferences = AppPreferences(preferences),
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            metadataSnapshotStore = mockk(relaxed = true),
        )

    @Test
    fun `更新只 POST 一次且除三个键外与服务端原文一致`() = runBlocking {
        everyGetReturns(GET_JSON)
        val posted = slot<FileInfo>()
        coEvery {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = capture(posted),
            )
        } returns RawResponse(ByteArray(0), 204, emptyMap())

        val outcome =
            repository.updateItemMetadata(
                itemId = ITEM_ID,
                name = "新名称",
                overview = "新简介",
                sortName = "新排序名称",
            )

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = any(),
            )
        }
        // GET 必须带上当前用户（否则服务器不知道按谁的口径返回 BaseItemDto）。
        coVerify(exactly = 1) {
            apiClient.request(
                method = HttpMethod.GET,
                pathTemplate = ITEM_PATH,
                queryParameters = match { params -> params["userId"] == USER_ID },
            )
        }

        val postedFile = posted.captured
        // 必须走 application/json（String body 分支固定发 text/plain，服务端会 415）。
        assertEquals("application/json", postedFile.mediaType)
        val body = postedFile.content.decodeToString()
        val postJson = Json.parseToJsonElement(body).jsonObject
        assertEquals(JsonPrimitive("新名称"), postJson["Name"])
        assertEquals(JsonPrimitive("新简介"), postJson["Overview"])
        assertEquals(JsonPrimitive("新排序名称"), postJson["ForcedSortName"])

        // 关键回归点：MinValue DateTime 必须以服务端原样提交，不能被 SDK 改写成设备时区历史偏移。
        assertEquals(
            JsonPrimitive("0001-01-01T00:00:00.0000000Z"),
            postJson.chapterImageDateModified(),
        )
        assertTrue(body.contains("\"ImageDateModified\":\"0001-01-01T00:00:00.0000000Z\""))
        assertFalse(body.contains("+08:05:43"))
        assertEquals(JsonPrimitive("2025-12-18T05:09:32.0000000Z"), postJson["DateCreated"])

        // 除三个可编辑键外，其余键值与 GET 原文完全一致（含嵌套结构）。
        val untouched = setOf("Name", "Overview", "ForcedSortName")
        assertEquals(
            JsonObject(originalJson().filterKeys { it !in untouched }),
            JsonObject(postJson.filterKeys { it !in untouched }),
        )
        assertEquals(JsonPrimitive("旧排序名称"), postJson["SortName"])
        coVerify(exactly = 0) { database.deleteMovie(any()) }
    }

    @Test
    fun `强制排序名传 null 时 POST 该键为 JSON null`() = runBlocking {
        everyGetReturns(GET_JSON)
        val posted = slot<FileInfo>()
        coEvery {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = capture(posted),
            )
        } returns RawResponse(ByteArray(0), 204, emptyMap())

        repository.updateItemMetadata(
            itemId = ITEM_ID,
            name = "新名称",
            overview = null,
            sortName = null,
        )

        val postedFile = posted.captured
        // 必须走 application/json（String body 分支固定发 text/plain，服务端会 415）。
        assertEquals("application/json", postedFile.mediaType)
        val body = postedFile.content.decodeToString()
        val postJson = Json.parseToJsonElement(body).jsonObject
        assertEquals(JsonNull, postJson["ForcedSortName"])
        assertEquals(JsonNull, postJson["Overview"])
        assertTrue(body.contains("\"ForcedSortName\":null"))
    }

    @Test
    fun `403 返回权限拒绝且本地零改动`() = runBlocking {
        everyGetReturns(GET_JSON)
        coEvery {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = any(),
            )
        } throws InvalidStatusException(403)

        val outcome =
            repository.updateItemMetadata(
                itemId = ITEM_ID,
                name = "新名称",
                overview = null,
                sortName = null,
            )

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
    }

    @Test
    fun `500 返回服务端错误`() = runBlocking {
        everyGetReturns(GET_JSON)
        coEvery {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = any(),
            )
        } throws InvalidStatusException(500)

        val outcome =
            repository.updateItemMetadata(
                itemId = ITEM_ID,
                name = "新名称",
                overview = null,
                sortName = null,
            )

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.ServerError)
        assertEquals(500, (failure as UserDataWriteFailure.ServerError).status)
    }

    @Test
    fun `网络 IO 异常返回网络失败`() = runBlocking {
        everyGetReturns(GET_JSON)
        coEvery {
            apiClient.request(
                method = HttpMethod.POST,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
                requestBody = any(),
            )
        } throws IOException("connection reset")

        val outcome =
            repository.updateItemMetadata(
                itemId = ITEM_ID,
                name = "新名称",
                overview = null,
                sortName = null,
            )

        assertEquals(
            UserDataWriteOutcome.Failed(UserDataWriteFailure.Network("connection reset")),
            outcome,
        )
    }

    @Test
    fun `快照映射名称简介与排序名称_无自定义排序名时回落派生值`() = runBlocking {
        everyGetReturns(GET_JSON)

        val snapshot = repository.fetchItemMetadataSnapshot(ITEM_ID)

        assertEquals(
            ItemMetadataSnapshot(
                itemId = ITEM_ID,
                name = "旧名称",
                overview = "旧简介",
                sortName = "旧排序名称",
            ),
            snapshot,
        )
    }

    @Test
    fun `快照优先使用强制排序名`() = runBlocking {
        everyGetReturns(
            GET_JSON.replace("\"ForcedSortName\": null", "\"ForcedSortName\": \"自定义排序名\"")
        )

        val snapshot = repository.fetchItemMetadataSnapshot(ITEM_ID)

        assertEquals("自定义排序名", snapshot?.sortName)
    }

    @Test
    fun `读取失败返回 null 而不是抛异常`() = runBlocking {
        coEvery {
            apiClient.request(
                method = HttpMethod.GET,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
            )
        } throws InvalidStatusException(500)

        assertNull(repository.fetchItemMetadataSnapshot(ITEM_ID))
    }

    private fun everyGetReturns(json: String) {
        coEvery {
            apiClient.request(
                method = HttpMethod.GET,
                pathTemplate = ITEM_PATH,
                queryParameters = any(),
            )
        } returns RawResponse(json.encodeToByteArray(), 200, emptyMap())
    }

    private fun originalJson(): JsonObject = Json.parseToJsonElement(GET_JSON).jsonObject

    /** POST 体里第一章的 `ImageDateModified`（MinValue 回归点）。 */
    private fun JsonObject.chapterImageDateModified(): JsonElement? =
        (this["Chapters"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("ImageDateModified")

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ITEM_PATH: String = "/Items/$ITEM_ID"

        /** 真实服务端形状（裁剪）：含 MinValue DateTime 的章节与正常 DateTime。 */
        val GET_JSON: String =
            """
            {
              "Name": "旧名称",
              "ServerId": "33333333-3333-4333-8333-333333333333",
              "Id": "$ITEM_ID",
              "DateCreated": "2025-12-18T05:09:32.0000000Z",
              "Chapters": [
                {
                  "StartPositionTicks": 0,
                  "Name": "Chapter 1",
                  "ImageDateModified": "0001-01-01T00:00:00.0000000Z"
                }
              ],
              "Overview": "旧简介",
              "SortName": "旧排序名称",
              "ForcedSortName": null,
              "OfficialRating": "PG-13",
              "ProductionYear": 2020
            }
            """
                .trimIndent()
    }
}
