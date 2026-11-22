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
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.ItemUpdateApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T6 契约：编辑元数据只走 SDK 官方 `POST /Items/{itemId}`（[ItemUpdateApi.updateItem]），且提交的 DTO 只改名称 / 简介 /
 * 排序名称。
 *
 * Jellyfin 服务端按提交的 DTO 覆盖更新：任何字段缺失都会被清空，因此必须先 GET 完整条目、只 `copy` 三个可编辑字段。 编辑不可排队补偿：服务器拒绝 /
 * 网络失败时本地库不做任何改动。
 */
class ItemMetadataContractTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
    private val itemUpdateApi = mockk<ItemUpdateApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.userLibraryApi } returns userLibraryApi
            every { jellyfinApi.itemUpdateApi } returns itemUpdateApi
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
    fun `更新只提 updateItem 一次且仅名称简介排序名称三个字段变化`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } returns
            Response(CURRENT_DTO, 200, emptyMap())
        val captured = slot<BaseItemDto>()

        val outcome =
            repository.updateItemMetadata(
                itemId = ITEM_ID,
                name = "新名称",
                overview = "新简介",
                sortName = "新排序名称",
            )

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { itemUpdateApi.updateItem(ITEM_ID, capture(captured)) }
        assertEquals(
            CURRENT_DTO.copy(name = "新名称", overview = "新简介", sortName = "新排序名称"),
            captured.captured,
        )
        assertEquals("PG-13", captured.captured.officialRating)
        assertEquals(2020, captured.captured.productionYear)
        coVerify(exactly = 0) { database.deleteMovie(any()) }
    }

    @Test
    fun `403 返回权限拒绝且本地零改动`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } returns
            Response(CURRENT_DTO, 200, emptyMap())
        coEvery { itemUpdateApi.updateItem(any(), any()) } throws InvalidStatusException(403)

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
    fun `502 返回服务端错误`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } returns
            Response(CURRENT_DTO, 200, emptyMap())
        coEvery { itemUpdateApi.updateItem(any(), any()) } throws InvalidStatusException(502)

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
        assertEquals(502, (failure as UserDataWriteFailure.ServerError).status)
    }

    @Test
    fun `网络 IO 异常返回网络失败`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } returns
            Response(CURRENT_DTO, 200, emptyMap())
        coEvery { itemUpdateApi.updateItem(any(), any()) } throws IOException("connection reset")

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
    fun `快照映射名称简介与排序名称`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } returns
            Response(CURRENT_DTO, 200, emptyMap())

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
    fun `读取失败返回 null 而不是抛异常`() = runBlocking {
        coEvery { userLibraryApi.getItem(itemId = ITEM_ID, userId = USER_ID) } throws
            InvalidStatusException(500)

        assertNull(repository.fetchItemMetadataSnapshot(ITEM_ID))
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val CURRENT_DTO =
            BaseItemDto(
                id = ITEM_ID,
                name = "旧名称",
                type = BaseItemKind.MOVIE,
                overview = "旧简介",
                sortName = "旧排序名称",
                officialRating = "PG-13",
                productionYear = 2020,
            )
    }
}
