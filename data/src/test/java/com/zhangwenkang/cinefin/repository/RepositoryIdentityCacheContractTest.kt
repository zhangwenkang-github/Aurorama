package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T6 ④（WRITE-11）：下载页 60s TTL 缓存必须按 (server baseUrl, userId) 隔离。
 *
 * 同一 App 进程切服务器 / 切用户后，旧身份的封面 URL 与曲库元数据不得在新身份下命中；同身份在 TTL 内仍要命中（语义不变）。
 */
class RepositoryIdentityCacheContractTest {
    private var currentUserId: UUID = USER_A
    private var currentBaseUrl: String = BASE_A

    private val apiClient =
        mockk<ApiClient>(relaxed = true).apply {
            every { this@apply.baseUrl } answers { currentBaseUrl }
        }
    private val userLibrary = mockk<UserLibraryApi>(relaxed = true)
    private val itemsOperation = mockk<ItemsApi>(relaxed = true)
    private val api =
        mockk<JellyfinApi>(relaxed = true).apply {
            every { this@apply.userId } answers { currentUserId }
            every { this@apply.api } returns apiClient
            every { this@apply.userLibraryApi } returns userLibrary
            every { this@apply.itemsApi } returns itemsOperation
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
            database = mockk<ServerDatabaseDao>(relaxed = true),
            appPreferences = AppPreferences(preferences),
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            metadataSnapshotStore = mockk(relaxed = true),
        )

    @Test
    fun `封面缓存按身份隔离且同身份 TTL 内仍命中`() = runBlocking {
        coEvery { userLibrary.getItem(ITEM_ID, USER_A) } returns itemResponse("tag-a")
        coEvery { userLibrary.getItem(ITEM_ID, USER_B) } returns itemResponse("tag-b")

        val urlA = repository.getPrimaryImageUrl(ITEM_ID)
        assertEquals(ImageCacheRules.primaryImageUrl(BASE_A, ITEM_ID, "tag-a"), urlA)

        // 同身份第二次读取命中 60s 缓存：仍只有一次网络请求。
        assertEquals(urlA, repository.getPrimaryImageUrl(ITEM_ID))
        coVerify(exactly = 1) { userLibrary.getItem(ITEM_ID, USER_A) }

        switchIdentity(user = USER_B, base = BASE_B)
        val urlB = repository.getPrimaryImageUrl(ITEM_ID)
        assertEquals(ImageCacheRules.primaryImageUrl(BASE_B, ITEM_ID, "tag-b"), urlB)
        coVerify(exactly = 1) { userLibrary.getItem(ITEM_ID, USER_B) }

        // 切回身份 A：60s 窗口未过期，A 自己的条目仍可命中，不受 B 影响。
        switchIdentity(user = USER_A, base = BASE_A)
        assertEquals(urlA, repository.getPrimaryImageUrl(ITEM_ID))
        coVerify(exactly = 1) { userLibrary.getItem(ITEM_ID, USER_A) }
    }

    @Test
    fun `曲库缓存按身份隔离且同身份 TTL 内仍命中`() = runBlocking {
        coEvery {
            itemsOperation.getItems(
                USER_A,
                includeItemTypes = any(),
                recursive = any(),
                limit = any(),
            )
        } returns tracksResponse(track(TRACK_A, "track-a"))
        coEvery {
            itemsOperation.getItems(
                USER_B,
                includeItemTypes = any(),
                recursive = any(),
                limit = any(),
            )
        } returns tracksResponse(track(TRACK_B, "track-b"))

        val tracksA = repository.getMusicTrackMetadata()
        assertEquals(setOf(TRACK_A), tracksA.keys)
        assertTrue(tracksA.getValue(TRACK_A).imageUri.orEmpty().startsWith(BASE_A))

        // 同身份第二次读取命中 60s 缓存。
        assertEquals(tracksA, repository.getMusicTrackMetadata())
        coVerify(exactly = 1) {
            itemsOperation.getItems(
                USER_A,
                includeItemTypes = any(),
                recursive = any(),
                limit = any(),
            )
        }

        switchIdentity(user = USER_B, base = BASE_B)
        val tracksB = repository.getMusicTrackMetadata()
        assertEquals(setOf(TRACK_B), tracksB.keys)
        assertTrue(tracksB.getValue(TRACK_B).imageUri.orEmpty().startsWith(BASE_B))
        coVerify(exactly = 1) {
            itemsOperation.getItems(
                USER_B,
                includeItemTypes = any(),
                recursive = any(),
                limit = any(),
            )
        }
    }

    private fun switchIdentity(user: UUID, base: String) {
        currentUserId = user
        currentBaseUrl = base
    }

    private fun itemResponse(tag: String): Response<BaseItemDto> =
        mockk<Response<BaseItemDto>>(relaxed = true).apply {
            every { content } returns
                BaseItemDto(
                    id = ITEM_ID,
                    name = "item",
                    type = BaseItemKind.MOVIE,
                    imageTags = mapOf(ImageType.PRIMARY to tag),
                )
        }

    private fun tracksResponse(dto: BaseItemDto): Response<BaseItemDtoQueryResult> =
        mockk<Response<BaseItemDtoQueryResult>>(relaxed = true).apply {
            every { content } returns
                BaseItemDtoQueryResult(
                    items = listOf(dto),
                    totalRecordCount = 1,
                    startIndex = 0,
                )
        }

    private fun track(itemId: UUID, tag: String): BaseItemDto =
        BaseItemDto(
            id = itemId,
            name = "track",
            type = BaseItemKind.AUDIO,
            imageTags = mapOf(ImageType.PRIMARY to tag),
        )

    private companion object {
        val USER_A: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_B: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ITEM_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val TRACK_A: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val TRACK_B: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        const val BASE_A = "https://server-a.example"
        const val BASE_B = "https://server-b.example"
    }
}
